import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {fileURLToPath} from 'node:url';
import {pushSpaHistory} from './spa-history.mjs';

const id = (kind, n) => `509${kind}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const fixture = info => {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  expect(n, 'Use the three frozen viewport projects').toBeTruthy();
  const org = id(0, n);
  return {n, org, api: `/api/v1/organizations/${org}/trace-events`,
    url: `/workspace/${org}/trace`, mission: id(2, n*100+1), task: id(3, n*100+1),
    event: e => id(4, n*1000+e), email: kind => `trace-${info.project.name}-${kind}@example.test`};
};
const hook = (p, name) => p.getByTestId(`trace-${name}`);
const rows = p => hook(p, 'event');
const button = (p, name) => p.getByRole('button', {name, exact: true});
const root = p => p.getByTestId('trace-explorer');
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
    {cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8', timeout: 30000,
      stdio: ['pipe', 'pipe', 'pipe']}).trim();
}
const responseFor = (p, path) => p.waitForResponse(r =>
  new URL(r.url()).pathname === path && r.request().method() === 'GET');
async function settle(p, f, action) {
  const [response] = await Promise.all([responseFor(p, f.api), action()]);
  expect(response.status()).toBe(200);
  const data = (await response.json()).data;
  expect(data.organizationId).toBe(f.org);
  expect(data.runtimeStatus).toBe('UNAVAILABLE');
  await expect(root(p).locator('main')).toHaveAttribute('aria-busy', 'false');
  await dom(p, data);
  return data;
}
async function dom(p, data) {
  await expect(rows(p)).toHaveCount(data.items.length);
  for (const [i, event] of data.items.entries()) {
    await expect(rows(p).nth(i)).toContainText(event.eventType);
    await expect(rows(p).nth(i)).toContainText(event.actorType);
  }
  expect(await root(p).innerText()).not.toMatch(/TRACE_PAYLOAD_MUST_NEVER_LEAK|never-return-this/);
}
async function login(p, f, kind = 'admin') {
  expect(process.env.E2E_PASSWORD, 'Disposable fixture password is required').toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(f.email(kind));
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  return settle(p, f, () => p.goto(f.url));
}
async function filters(p, f, q = '', missionId = '', taskId = '') {
  await p.getByLabel('Loại sự kiện', {exact: true}).fill(q);
  await p.getByLabel('Mission ID', {exact: true}).fill(missionId);
  await p.getByLabel('Task ID', {exact: true}).fill(taskId);
  return settle(p, f, () => button(p, 'Áp dụng').click());
}
async function select(p, f, event) {
  const [response] = await Promise.all([responseFor(p, `${f.api}/${event.id}`),
    rows(p).filter({hasText: event.eventType}).click()]);
  expect(response.status()).toBe(200);
  expect((await response.json()).data.event).toEqual(event);
  await expect(hook(p, 'selected')).toBeVisible();
  for (const value of [event.id, event.eventType, event.actorType, event.mission.id, event.mission.title])
    await expect(hook(p, 'selected')).toContainText(value);
}
async function cleared(p) {
  await expect(rows(p)).toHaveCount(0);
  await expect(hook(p, 'selected')).toHaveCount(0);
  await expect(hook(p, 'empty')).toHaveCount(0);
}
async function capture(p, info, state) {
  expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const folder = new URL('../../qa-visuals/', import.meta.url);
  mkdirSync(folder, {recursive: true});
  await p.screenshot({path: fileURLToPath(new URL(`trace-explorer-${info.project.name}-${state}.png`, folder)), fullPage: true});
}
// Hold only a fetched real 200, with explicit arrival/release/finished gates.
// No sleep, optional interception, synthetic success or unobserved route handler.
async function hold(p, path) {
  let arrived, release, completed;
  const seen = new Promise(r => arrived = r);
  const gate = new Promise(r => release = r);
  const done = new Promise((resolve, reject) => completed = {resolve, reject});
  // Mark the completion rejection observed even if arrival fails first.
  void done.catch(() => {});
  let consumed = false;
  let original;
  const handler = async route => {
    if (consumed) return route.continue();
    consumed = true;
    original = route.request();
    try {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      arrived(await response.json());
      await gate;
      try { await route.fulfill({response}); }
      catch (error) {
        if (!route.request().failure()) throw error;
      }
      completed.resolve();
    } catch (error) { completed.reject(error); arrived(Promise.reject(error)); }
  };
  const match = url => url.pathname === path;
  await p.route(match, handler);
  return {seen, async release() {
    release();
    if (consumed) {
      await done;
      const delivered = await original.response();
      if (delivered) await delivered.finished();
    }
    await p.unroute(match, handler);
    // Drain browser rendering after delivery/cancellation before checking stale DOM.
    await p.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  }};
}
function snapshot() {
  // Global business digest catches writes outside the active fixture org too.
  // SQL hashes raw payload/config/grant/receipt values; none leave PostgreSQL.
  const tables = ['organizations', 'teams', 'agents', 'missions', 'tasks',
    'task_dependencies', 'audit_events', 'approval_grants', 'agent_draft_config',
    'agent_configuration_versions', ...sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND right(tablename,9)='_receipts' ORDER BY tablename;").split('\n').filter(Boolean)];
  const auditColumns = sql("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='audit_events' AND column_name<>'payload';");
  return sql(tables.map(table => {
    const source = table === 'audit_events'
      ? `(SELECT ${auditColumns},md5(payload) AS payload_digest FROM audit_events)` : table;
    return `SELECT md5(coalesce(string_agg(row_digest,'' ORDER BY row_digest),'')) FROM (SELECT md5(row_to_json(x)::text) AS row_digest FROM ${source} x) digests;`;
  }).join('\n'));
}


test.describe('trace explorer independent QC', () => {
  test.describe.configure({mode: 'serial', retries: 0});
  let businessBefore;
  test.beforeEach(async () => {businessBefore = snapshot();});
  test.afterEach(async () => {expect(snapshot(), 'Trace read or isolated restoration changed business state').toBe(businessBefore);});
  test.beforeAll(async ({}, info) => {
    expect(info.config.workers, 'QC must run with one worker').toBe(1);
    expect(info.project.retries, 'QC must not retry').toBe(0);
  });
  test('TE01 overview: actual SQL IDs, two pages, stable order and read-only state', async ({page: p}, info) => {
    const f = fixture(info), before = snapshot();
    const data = await login(p, f);
    expect(data).toMatchObject({total: 33, size: 20, page: 0});
    expect(data.items.map(e => e.id)).toEqual(Array.from({length: 20}, (_, i) => f.event(i+1)));
    await expect(hook(p, 'unavailable')).toBeVisible();
    await expect(hook(p, 'page')).toContainText('33');
    if (info.project.name === 'desktop')
      await expect(p.locator('.desktop-sidebar').getByRole('link', {name: 'Trace', exact: true})).toHaveAttribute('aria-current', 'page');
    await expect(hook(p, 'selected')).toHaveCount(0);
    await expect(button(p, 'Trước')).toBeDisabled();
    await capture(p, info, 'overview');
    const next = await settle(p, f, () => button(p, 'Sau').click());
    expect(next.items.map(e => e.id)).toEqual([...Array.from({length: 12}, (_, i) => f.event(i+21)), f.event(38)]);
    const persisted = sql(`SELECT id FROM audit_events WHERE organization_id='${f.org}' ORDER BY created_at DESC,id;`).split('\n');
    expect([...data.items, ...next.items].map(e => e.id)).toEqual(persisted.filter(x => ![33,34,35,36,37].some(n => x === f.event(n))));
    await expect(button(p, 'Sau')).toBeDisabled();
    await settle(p, f, () => button(p, 'Trước').click());
    expect(snapshot()).toBe(before);
  });

  test('TE02 selected: fresh detail, scoped links, null metadata and keyboard', async ({page: p}, info) => {
    const f = fixture(info), data = await login(p, f, 'member');
    await select(p, f, data.items[0]);
    const selected = hook(p, 'selected');
    await expect(selected.getByRole('link', {name: 'Mở task', exact: true})).toHaveAttribute('href',
      `/workspace/${f.org}/teams/${id(1,f.n*100+1)}/missions/${f.mission}/tasks/${f.task}`);
    await expect(selected.getByRole('link', {name: 'Mở Mission', exact: true})).toHaveAttribute('href',
      `/workspace/${f.org}/teams/${id(1,f.n*100+1)}/missions/${f.mission}/workspace`);
    await capture(p, info, 'selected');
    await selected.getByRole('link', {name: 'Mở task', exact: true}).click();
    await expect(p).toHaveURL(new RegExp(`/tasks/${f.task}$`));
    await settle(p, f, () => p.goBack());
    const second = await settle(p, f, () => button(p, 'Sau').click());
    await select(p, f, second.items.find(e => e.id === f.event(31)));
    await expect(hook(p, 'selected')).toContainText(f.task);
    await select(p, f, second.items.find(e => e.id === f.event(32)));
    await expect(hook(p, 'selected').getByRole('link')).toHaveCount(0);
    await select(p, f, second.items.find(e => e.id === f.event(38)));
    await expect(hook(p, 'selected')).toContainText('literal%_!\\<script>');
    expect(await root(p).locator('script').count()).toBe(0);
    if (info.project.name !== 'desktop') {
      const menu = button(p, 'Menu');
      await menu.click();
      await expect(p.getByRole('dialog')).toBeVisible();
      await expect(p.getByRole('dialog').getByRole('link', {name: 'Trace', exact: true})).toHaveCount(1);
      await p.keyboard.press('Escape');
      await expect(p.getByRole('dialog')).not.toBeVisible();
      await expect(menu).toBeFocused();
    }
  });

  test('TE03 filtered: literal labels, intersecting scopes, draft validation and reset', async ({page: p}, info) => {
    const f = fixture(info);
    await login(p, f);
    for (const literal of ['%', '_', '!', '\\', '<script>']) {
      const data = await filters(p, f, literal);
      expect(data.total).toBe(1);
      expect(data.items[0].id).toBe(f.event(38));
    }
    await capture(p, info, 'filtered');
    const scoped = await filters(p, f, 'TraceEvent', f.mission, f.task);
    expect(scoped.total).toBe(30);
    const requests = [];
    const listener = r => {if (new URL(r.url()).pathname === f.api) requests.push(r.url());};
    p.on('request', listener);
    await p.getByLabel('Mission ID', {exact: true}).fill('bad');
    await button(p, 'Áp dụng').click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(20);
    expect(requests).toEqual([]);
    p.off('request', listener);
    const reset = await settle(p, f, () => button(p, 'Đặt lại').click());
    expect(reset.total).toBe(33);
    await expect(p.getByLabel('Mission ID', {exact: true})).toHaveValue('');
    await expect(hook(p, 'selected')).toHaveCount(0);
  });

  test('TE04 empty: genuine filtered zero, excluded detail and outer scope rejection', async ({page: p}, info) => {
    const f = fixture(info);
    await login(p, f);
    for (const mission of [id(2,501), id(2,f.n*100+2), id(2,999999)]) {
      expect((await filters(p, f, '', mission)).total).toBe(0);
      await expect(hook(p, 'empty')).toBeVisible();
      await expect(rows(p)).toHaveCount(0);
    }
    await capture(p, info, 'empty');
    for (const n of [33,34,35,36,37]) expect((await p.request.get(`${f.api}/${f.event(n)}`)).status()).toBe(404);
    expect((await p.request.get(f.api.replace(f.org,id(0,5)))).status()).toBe(403);
    for (const query of ['size=20','q=a&q=b','page=01','missionId=','taskId=bad'])
      expect((await p.request.get(`${f.api}?${query}`)).status()).toBe(400);
  });

  test('TE05 error: fetched-200 corruption and server error clear private DOM; live retry', async ({page: p}, info) => {
    const f = fixture(info);
    const data = await login(p, f);
    await select(p, f, data.items[0]);
    for (const mode of ['503','scope','shape','detail']) {
      const path = mode === 'detail' ? `${f.api}/${data.items[0].id}` : f.api;
      const match = url => url.pathname === path;
      await p.route(match, async route => {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        const body = await response.json();
        if (mode === '503') return route.fulfill({response, status: 503, json: {error: {code: 'QC_INJECTED'}}});
        if (mode === 'scope') body.data.organizationId = id(0,5);
        if (mode === 'shape') body.data.items[0].payload = 'QC forbidden field';
        if (mode === 'detail') body.data.event.id = f.event(2);
        return route.fulfill({response, json: body});
      });
      try {
        if (mode === 'detail') await rows(p).first().click();
        else await button(p, 'Refresh').click();
        await expect(hook(p, 'error')).toBeVisible();
        await cleared(p);
        if (mode === '503') await capture(p, info, 'error');
      } finally { await p.unroute(match); }
      await settle(p, f, () => button(p, 'Refresh').click());
    }
    for (const mode of ['scope', '503']) {
      const match = url => url.pathname === `/api/v1/auth/workspaces/${f.org}`;
      await p.route(match, async route => {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        const body = await response.json();
        body.data.id = id(0, 5);
        await route.fulfill({response, status: mode === '503' ? 503 : 200, json: body});
      });
      try {
        await button(p, 'Refresh').click();
        await expect(hook(p, 'error')).toBeVisible();
        await cleared(p);
        await expect(hook(p, 'denied')).toHaveCount(0);
      } finally {await p.unroute(match);}
      await settle(p, f, () => button(p, 'Refresh').click());
    }
  });

  test('TE06 denied: held real list after membership loss; disabled and stale credentials restore', async ({page: p}, info) => {
    const f = fixture(info);
    await login(p, f, 'other');
    const original = JSON.parse(sql(`SELECT row_to_json(x) FROM (SELECT id,enabled,credential_version FROM human_accounts WHERE email='${f.email('other')}') x;`));
    const role = sql(`SELECT role FROM organization_memberships WHERE account_id='${original.id}' AND organization_id='${f.org}';`);
    expect(['ADMIN','MEMBER']).toContain(role);
    const restore = () => sql(`UPDATE human_accounts SET enabled=${original.enabled},credential_version=${original.credential_version} WHERE id='${original.id}'; INSERT INTO organization_memberships(account_id,organization_id,role) VALUES ('${original.id}','${f.org}','${role}') ON CONFLICT (account_id,organization_id) DO UPDATE SET role=EXCLUDED.role;`);
    await p.getByLabel('Loại sự kiện', {exact: true}).fill('user draft');
    const held = await hold(p, f.api);
    try {
      await button(p, 'Refresh').click();
      await held.seen;
      await cleared(p);
      sql(`DELETE FROM organization_memberships WHERE account_id='${original.id}' AND organization_id='${f.org}';`);
      const rejected = responseFor(p, `/api/v1/auth/workspaces/${f.org}`);
      await button(p, 'Refresh').click();
      expect((await rejected).status()).toBe(403);
      await expect(hook(p, 'denied')).toBeVisible();
      await held.release();
      await cleared(p);
      await expect(p.getByLabel('Loại sự kiện', {exact: true})).toHaveValue('user draft');
      await capture(p, info, 'denied');
      expect((await p.request.get(f.api)).status()).toBe(403);
    } finally {restore(); await held.release();}
    for (const mode of ['disabled','credentials']) {
      await login(p, f, 'other');
      try {
        sql(`UPDATE human_accounts SET ${mode === 'disabled' ? 'enabled=false' : 'credential_version=credential_version+1'} WHERE id='${original.id}';`);
        await button(p, 'Refresh').click();
        await cleared(p);
        await expect(hook(p, 'denied')).toBeVisible();
        expect([401,403]).toContain((await p.request.get(f.api)).status());
      } finally {restore();}
    }
  });

  test('TE07 reused: stale detail/list cannot cross route, account or pending workspace', async ({page: p}, info) => {
    const f = fixture(info), data = await login(p, f);
    const held = await hold(p, `${f.api}/${data.items[0].id}`);
    try {
      await rows(p).first().click();
      await held.seen;
      await cleared(p);
      await pushSpaHistory(p, `/workspace/${id(0,5)}/trace`);
      await expect(hook(p, 'denied')).toBeVisible();
    } finally {await held.release();}
    await cleared(p);
    await capture(p, info, 'reused');
    await settle(p, f, () => p.goto(f.url));
    const workspace = await hold(p, `/api/v1/auth/workspaces/${f.org}`);
    try {
      await button(p, 'Refresh').click();
      await workspace.seen;
      await cleared(p);
      // Real cookie/session account change while the old authorization is pending.
      const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
      const response = await p.request.post('/api/v1/auth/login', {form: {
        username: f.email('member'), password: process.env.E2E_PASSWORD},
        headers: {[csrf.headerName]: csrf.token}});
      expect(response.status()).toBe(200);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
    } finally {await workspace.release();}
    await cleared(p);
    await login(p, f);
    const list = await hold(p, f.api);
    try {
      await button(p, 'Refresh').click();
      await list.seen;
      if (info.project.name !== 'desktop') await button(p, 'Menu').click();
      await p.getByRole('link', {name: 'Đổi workspace', exact: true}).filter({visible: true}).click();
    } finally {await list.release();}
    await expect(root(p)).toHaveCount(0);
  });

  test('TE08 refreshed: actual inserted audit, held refresh and visible-return revalidation', async ({page: p}, info) => {
    const f = fixture(info), before = snapshot();
    const data = await login(p, f);
    await select(p, f, data.items[0]);
    const fresh = f.event(99);
    try {
      sql(`INSERT INTO audit_events(id,organization_id,mission_id,task_id,actor_type,event_type,payload,created_at) VALUES ('${fresh}','${f.org}','${f.mission}','${f.task}','HUMAN','QC Browser Refresh','{}',now());`);
      const held = await hold(p, f.api);
      try {
        await button(p, 'Refresh').click();
        const fetched = await held.seen;
        expect(fetched.data.total).toBe(34);
        expect(fetched.data.items[0].id).toBe(fresh);
        await cleared(p);
      } finally {await held.release();}
      await expect(root(p).locator('main')).toHaveAttribute('aria-busy', 'false');
      await expect(rows(p).first()).toContainText('QC Browser Refresh');
      await expect(hook(p, 'selected')).toHaveCount(0);
      await capture(p, info, 'refreshed');
      await settle(p, f, () => p.evaluate(() => window.dispatchEvent(new Event('focus'))));
      await settle(p, f, () => p.evaluate(() => document.dispatchEvent(new Event('visibilitychange'))));
    } finally {sql(`DELETE FROM audit_events WHERE id='${fresh}' AND organization_id='${f.org}';`);}
    expect(snapshot()).toBe(before);
  });
});
