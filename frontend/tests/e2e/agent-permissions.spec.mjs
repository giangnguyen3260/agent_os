import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

const button = (p, name) => p.getByRole('button', {name, exact: true});
const agent = (n, a) =>
  `50820000-0000-4000-8000-${String(n * 1000 + a).padStart(12, '0')}`;
const keys = [
  'organizationId',
  'agentId',
  'name',
  'status',
  'configurationRevision',
  'checkedAt',
  'teamState',
  'team',
  'configurationPresent',
  'sourceStatus',
  'requests',
];
const rowKeys = ['kind', 'configured', 'effective', 'grantedBy', 'scope'];
const kinds = ['MODEL', 'TOOLS', 'SKILLS'];
const fields = ['model', 'tools', 'skills'];
function sql(input) {
  return execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'postgres',
      'sh',
      '-c',
      'psql -X -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      input,
      encoding: 'utf8',
      timeout: 15000,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  ).trim();
}
const accountWhere = email =>
  `account_id=(SELECT id FROM human_accounts WHERE email='${email}')`;
function fixture(info) {
  const surface = info.project.name,
    n = {desktop: 2, tablet: 3, mobile: 4}[surface];
  expect(n).toBeTruthy();
  const email = role => `permissions-${surface}-${role}@example.test`;
  const org = sql(
    `SELECT organization_id FROM organization_memberships WHERE ${accountWhere(email('admin'))} ORDER BY organization_id;`,
  );
  expect(org).toMatch(/^[0-9a-f-]{36}$/);
  const root = a => `/api/v1/organizations/${org}/agents/${agent(n, a)}`;
  return {
    surface,
    n,
    org,
    email,
    root,
    api: a => `${root(a)}/permissions`,
    url: a => `/workspace/${org}/agents/${agent(n, a)}/permissions`,
    studio: a => `/workspace/${org}/agents/${agent(n, a)}/studio`,
  };
}
async function login(p, email) {
  expect(
    process.env.E2E_PASSWORD,
    'E2E_PASSWORD required; no embedded password',
  ).toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(email);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function data(p, path) {
  const r = await p.request.get(path);
  expect(r.status(), path).toBe(200);
  return (await r.json()).data;
}
async function read(p, path, action, status = 200) {
  const pending = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === 'GET',
  );
  await action();
  const r = await pending;
  expect(r.status(), path).toBe(status);
  return r;
}
function persisted(f, a) {
  return JSON.parse(
    sql(`SELECT jsonb_build_object('name',a.name,'status',a.status,'revision',a.configuration_revision,
    'present',c.agent_id IS NOT NULL,'model',c.model,'tools',c.tools,'skills',c.skills,
    'teamState',CASE WHEN a.team_id IS NULL THEN 'UNASSIGNED' WHEN t.id IS NULL THEN 'UNAVAILABLE' ELSE 'ASSIGNED' END,
    'team',CASE WHEN t.id IS NULL THEN NULL ELSE jsonb_build_object('id',t.id,'name',t.name,'status',t.status) END)
    FROM agents a LEFT JOIN agent_draft_config c ON c.agent_id=a.id
    LEFT JOIN teams t ON t.id=a.team_id AND t.organization_id=a.organization_id
    WHERE a.id='${agent(f.n, a)}' AND a.organization_id='${f.org}';`),
  );
}
function wire(d, f, a) {
  const stored = persisted(f, a);
  expect(Object.keys(d).sort()).toEqual([...keys].sort());
  expect(d).toMatchObject({
    organizationId: f.org,
    agentId: agent(f.n, a),
    name: stored.name,
    status: stored.status,
    configurationRevision: stored.revision,
    configurationPresent: stored.present,
    sourceStatus: 'UNAVAILABLE',
    teamState: stored.teamState,
    team: stored.team,
  });
  expect(Number.isInteger(d.configurationRevision)).toBe(true);
  expect(d.configurationRevision).toBeGreaterThanOrEqual(0);
  expect(d.configurationRevision).toBeLessThanOrEqual(2147483647);
  expect(d.checkedAt).toMatch(/^\d{4}-\d\d-\d\dT.*Z$/);
  expect(Math.abs(Date.now() - Date.parse(d.checkedAt))).toBeLessThan(60000);
  expect(['ASSIGNED', 'UNASSIGNED', 'UNAVAILABLE']).toContain(d.teamState);
  if (stored.team) {
    expect(d.teamState).toBe('ASSIGNED');
    expect(Object.keys(d.team).sort()).toEqual(['id', 'name', 'status']);
  }
  expect(d.requests).toHaveLength(3);
  expect(d.requests.map(r => r.kind)).toEqual(kinds);
  d.requests.forEach((r, i) => {
    expect(Object.keys(r).sort()).toEqual([...rowKeys].sort());
    expect(r).toEqual({
      kind: kinds[i],
      configured: stored[fields[i]],
      effective: null,
      grantedBy: null,
      scope: null,
    });
  });
}
async function visible(p, d) {
  await expect(
    p.getByRole('heading', {name: `${d.name} / Permissions`, exact: true}),
  ).toBeVisible();
  await expect(p.getByTestId('permissions-unavailable')).toBeVisible();
  const rows = p.getByTestId('permission-request');
  await expect(rows).toHaveCount(3);
  for (let i = 0; i < 3; i++) {
    const row = rows.nth(i),
      r = d.requests[i];
    await expect(row).toContainText(
      {MODEL: 'Mô hình', TOOLS: 'Công cụ', SKILLS: 'Kỹ năng'}[r.kind],
    );
    if (r.configured === null) await expect(row).toContainText('Chưa cấu hình');
    else if (r.configured === '')
      await expect(row).toContainText('Chưa nhập nội dung');
    else
      expect(
        await row.evaluate(
          (el, value) =>
            [...el.querySelectorAll('*')].some(
              node => node.textContent === value,
            ),
          r.configured,
        ),
      ).toBe(true);
    await expect(row).toContainText('Chưa thể xác minh');
    await expect(row).toContainText('Không có dữ liệu');
  }
  await expect(p.getByTestId('agent-permissions')).toContainText(d.status);
  await expect(p.locator('.metadata')).toContainText(
    `Phiên bản cấu hình: ${d.configurationRevision}`,
  );
  await expect(p.locator('time')).toHaveAttribute('datetime', d.checkedAt);
  if (d.team)
    await expect(p.getByTestId('agent-permissions')).toContainText(d.team.name);
  await expect(p.getByTestId('permissions-error')).toHaveCount(0);
  await expect(p.getByTestId('permissions-denied')).toHaveCount(0);
}
function businessSnapshot(f) {
  return sql(`SELECT jsonb_build_object(
    'agents',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM agents a WHERE organization_id='${f.org}'),
    'config',(SELECT jsonb_agg(to_jsonb(c) ORDER BY c.agent_id) FROM agent_draft_config c JOIN agents a ON a.id=c.agent_id WHERE a.organization_id='${f.org}'),
    'versions',(SELECT jsonb_agg(to_jsonb(v) ORDER BY agent_id,revision) FROM agent_configuration_versions v WHERE organization_id='${f.org}'),
    'receipts',(SELECT jsonb_agg(to_jsonb(r) ORDER BY agent_id,idempotency_key) FROM agent_configuration_receipts r WHERE organization_id='${f.org}'),
    'grants',(SELECT jsonb_agg(to_jsonb(g) ORDER BY id) FROM approval_grants g WHERE organization_id='${f.org}'),
    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM tasks t WHERE organization_id='${f.org}'),
    'audit',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM audit_events e WHERE organization_id='${f.org}')
  )::text;`);
}
async function open(p, f, a = 1) {
  const before = businessSnapshot(f);
  const r = await read(p, f.api(a), () => p.goto(f.url(a)));
  const d = (await r.json()).data;
  wire(d, f, a);
  await visible(p, d);
  expect(businessSnapshot(f)).toBe(before);
  return d;
}
async function refresh(p, f, a = 1) {
  const r = await read(p, f.api(a), () => button(p, 'Refresh').click());
  const d = (await r.json()).data;
  wire(d, f, a);
  await visible(p, d);
  return d;
}
async function privateGone(p, d) {
  await expect(p.getByTestId('permission-request')).toHaveCount(0);
  for (const value of [
    d.name,
    d.team?.name,
    ...d.requests.map(r => r.configured),
  ].filter(Boolean))
    await expect(p.locator('main')).not.toContainText(value);
}
async function shot(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const directory = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(directory, {recursive: true});
  await p.screenshot({
    path: join(
      directory,
      `agent-permissions-${info.project.name}-${state}.png`,
    ),
    fullPage: true,
  });
}
function membership(f, operation) {
  const where = `organization_id='${f.org}' AND ${accountWhere(f.email('admin'))}`;
  if (operation === 'delete')
    sql(`DELETE FROM organization_memberships WHERE ${where};`);
  else
    sql(
      `UPDATE organization_memberships SET role='${operation}' WHERE ${where};`,
    );
}
function restoreMembership(f) {
  sql(`INSERT INTO organization_memberships(account_id,organization_id,role)
    SELECT id,'${f.org}','ADMIN' FROM human_accounts WHERE email='${f.email('admin')}'
    ON CONFLICT(account_id,organization_id) DO UPDATE SET role='ADMIN';`);
}
async function focus(p) {
  await p.evaluate(() => window.dispatchEvent(new Event('focus')));
}
function signal() {
  let resolve;
  const promise = new Promise(r => {
    resolve = r;
  });
  return {
    resolve,
    wait: () =>
      new Promise((ok, fail) => {
        const timer = setTimeout(
          () => fail(new Error('Actual backend response barrier timed out')),
          15000,
        );
        promise.then(value => {
          clearTimeout(timer);
          ok(value);
        });
      }),
  };
}
// Fetch a real response before delaying or corrupting it. Preserve handler errors;
// cancellation is allowed only after the test explicitly releases a stale request.
async function hold(p, path, fault = false) {
  const arrived = signal(),
    release = signal(),
    finished = signal();
  let originalRequest,
    failure,
    released = false;
  const pattern = `**${path}`;
  await p.route(pattern, async route => {
    originalRequest = route.request();
    try {
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      arrived.resolve((await actual.json()).data);
      await release.wait();
      if (fault)
        await route.fulfill({
          response: actual,
          status: 503,
          json: {error: {code: 'QC_TRANSPORT_FAILURE'}},
        });
      else await route.fulfill({response: actual});
    } catch (e) {
      if (
        !(
          released &&
          /closed|cancel|invalid interception|already handled/i.test(String(e))
        )
      )
        failure = e;
    } finally {
      finished.resolve();
    }
  });
  return {
    arrived,
    release: () => {
      released = true;
      release.resolve();
    },
    async done() {
      await finished.wait();
      if (failure) throw failure;
      // Observe delivery to the page (or its legitimate cancellation), not just
      // completion of the interceptor, before checking late-response privacy.
      const response = await originalRequest.response();
      if (response) await response.finished();
      await p.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
    },
    async cleanup() {
      released = true;
      release.resolve();
      await finished.wait();
      await p.unroute(pattern);
      if (failure) throw failure;
    },
  };
}

test.describe('agent permissions independent QC', () => {
  test.describe.configure({mode: 'serial'});
  let f;
  test.beforeEach(async ({page: p}, info) => {
    f = fixture(info);
    await login(p, f.email('admin'));
  });

  test('AP-B01 MEMBER opaque requests, human grant isolation and Studio navigation', async ({
    page: p,
  }, info) => {
    await login(p, f.email('member'));
    expect(
      Number(
        sql(
          `SELECT count(*) FROM approval_grants g JOIN human_accounts h ON h.id=g.account_id WHERE h.email='permissions-native-member@example.test';`,
        ),
      ),
      'Fixture must include an unrelated real human grant',
    ).toBeGreaterThan(0);
    const grantId = randomUUID();
    sql(`INSERT INTO approval_grants(id,organization_id,account_id,team_id,action_type,risk_level,expires_at)
      SELECT '${grantId}','${f.org}',h.id,a.team_id,'PUBLISH_PREVIEW','LOW',now()+interval '1 day'
      FROM human_accounts h CROSS JOIN agents a WHERE h.email='${f.email('member')}' AND a.id='${agent(f.n, 1)}';`);
    try {
      const d = await data(p, f.api(1));
      wire(d, f, 1);
      expect(
        d.requests.every(
          r => typeof r.configured === 'string' && r.configured.length > 0,
        ),
      ).toBe(true);
      await p.goto(f.studio(1));
      await expect(p.getByTestId('studio-view')).toBeVisible();
      const r = await read(p, f.api(1), () =>
        p.getByRole('link', {name: 'Permissions', exact: true}).click(),
      );
      await visible(p, (await r.json()).data);
      if (p.viewportSize().width < 1000) {
        await button(p, 'Menu').click();
        await expect(p.getByRole('dialog')).toBeVisible();
        await p.keyboard.press('Escape');
        await expect(p.getByRole('dialog')).not.toBeVisible();
        await expect(button(p, 'Menu')).toBeFocused();
      }
      await shot(p, info, 'overview');
      await p.getByRole('link', {name: 'Back', exact: true}).click();
      await expect(p).toHaveURL(f.studio(1));
      await open(p, f);
      await p
        .getByRole('link', {name: 'Open Agent Studio', exact: true})
        .click();
      await expect(p).toHaveURL(f.studio(1));
    } finally {
      sql(`DELETE FROM approval_grants WHERE id='${grantId}';`);
    }
  });

  test('AP-B02 legacy missing, present null/empty and every stored lifecycle', async ({
    page: p,
  }, info) => {
    for (const [a, status] of [
      [2, 'DRAFT'],
      [3, 'ACTIVE'],
      [4, 'SUSPENDED'],
      [5, 'ARCHIVED'],
    ]) {
      const d = await open(p, f, a);
      expect(d.status).toBe(status);
      if (a === 2) {
        expect(d.configurationPresent).toBe(false);
        expect(d.requests.map(r => r.configured)).toEqual([null, null, null]);
        await expect(p.getByTestId('permissions-missing-config')).toBeVisible();
        await shot(p, info, 'missing');
      } else {
        expect(d.configurationPresent).toBe(true);
        await expect(p.getByTestId('permissions-missing-config')).toHaveCount(
          0,
        );
        if (a === 3) {
          expect(d.requests.map(r => r.configured)).toEqual([null, null, null]);
          await shot(p, info, 'active');
        }
        if (a === 4) {
          expect(d.requests.map(r => r.configured)).toEqual(['', '', '   ']);
          await shot(p, info, 'empty');
        }
      }
      await p
        .getByRole('link', {name: 'Open Agent Studio', exact: true})
        .click();
      await expect(p.getByTestId('studio-view')).toBeVisible();
      await expect(
        p.getByRole('link', {name: 'Permissions', exact: true}),
      ).toBeVisible();
    }
  });

  test('AP-B03 real Studio API write advances revision and Refresh reads persisted opaque text', async ({
    page: p,
  }, info) => {
    const before = await open(p, f),
      current = await data(p, `${f.root(1)}/configuration`);
    const csrf = await data(p, '/api/v1/auth/csrf');
    const requested = `QC requested ${randomUUID()}\n  <script>opaque only</script>, never a grant  `;
    const body = Object.fromEntries(
      [
        'responsibilities',
        'expectedInput',
        'outputFormat',
        'acceptanceCriteria',
        'model',
        'tools',
        'skills',
        'limits',
      ].map(k => [k, current.agent[k]]),
    );
    Object.assign(body, {
      model: requested,
      concurrency: current.concurrency,
      maxTokensPerRun: current.maxTokensPerRun,
      monthlyBudget: current.monthlyBudget,
      expectedRevision: current.revision,
    });
    const saved = await p.request.put(`${f.root(1)}/configuration`, {
      headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID()},
      data: body,
    });
    expect(saved.status()).toBe(200);
    const after = await refresh(p, f);
    expect(after.configurationRevision).toBe(before.configurationRevision + 1);
    expect(after.requests[0].configured).toBe(requested);
    await expect(p.locator('main script')).toHaveCount(0);
    await shot(p, info, 'refreshed');
  });

  test('AP-B04 actual foreign Agent and unauthorized organization never expose private data', async ({
    page: p,
  }, info) => {
    const d = await open(p, f);
    const foreignId = agent(5, 1),
      foreignOrg = sql(
        `SELECT organization_id FROM agents WHERE id='${foreignId}';`,
      );
    expect(foreignOrg).toMatch(/^[0-9a-f-]{36}$/);
    expect(foreignOrg).not.toBe(f.org);
    for (const [org, id, status] of [
      [f.org, foreignId, 404],
      [foreignOrg, foreignId, 403],
    ]) {
      const api = `/api/v1/organizations/${org}/agents/${id}/permissions`;
      expect((await p.request.get(api)).status()).toBe(status);
      await p.goto(`/workspace/${org}/agents/${id}/permissions`);
      await expect(p.getByTestId('permissions-denied')).toBeVisible();
      await privateGone(p, d);
    }
    await shot(p, info, 'denied');
  });

  test('AP-B05 fetched actual GET then 503/malformed; retry clears error with GET only', async ({
    page: p,
  }, info) => {
    const d = await open(p, f),
      writes = [];
    p.on('request', r => {
      if (!['GET', 'HEAD', 'OPTIONS'].includes(r.method()))
        writes.push(r.method());
    });
    for (const fault of ['503', 'malformed']) {
      let attempts = 0;
      const pattern = `**${f.api(1)}`;
      await p.route(pattern, async route => {
        expect(route.request().method()).toBe('GET');
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        wire((await actual.json()).data, f, 1);
        attempts++;
        if (attempts === 1)
          await route.fulfill({
            response: actual,
            status: fault === '503' ? 503 : 200,
            json:
              fault === '503'
                ? {error: {code: 'QC_TRANSPORT_FAILURE'}}
                : {data: null},
          });
        else await route.fulfill({response: actual});
      });
      try {
        await button(p, 'Refresh').click();
        await expect(p.getByTestId('permissions-error')).toBeVisible();
        await privateGone(p, d);
        if (fault === '503') await shot(p, info, 'error');
        const r = await read(p, f.api(1), () => button(p, 'Refresh').click());
        await visible(p, (await r.json()).data);
        expect(attempts).toBe(2);
      } finally {
        await p.unroute(pattern);
      }
    }
    expect(writes).toEqual([]);
  });

  test('AP-B06 ADMIN to MEMBER remains readable; legal membership deletion denies', async ({
    page: p,
  }) => {
    const d = await open(p, f);
    try {
      membership(f, 'MEMBER');
      await refresh(p, f);
      expect((await data(p, `/api/v1/auth/workspaces/${f.org}`)).role).toBe(
        'MEMBER',
      );
      membership(f, 'delete');
      expect((await p.request.get(f.api(1))).status()).toBe(403);
      await focus(p);
      await expect(p.getByTestId('permissions-denied')).toBeVisible();
      await privateGone(p, d);
    } finally {
      restoreMembership(f);
    }
  });

  test('AP-B07 delayed actual success and transport error cannot revive after revocation', async ({
    page: p,
  }) => {
    for (const fault of [false, true]) {
      const d = await open(p, f),
        held = await hold(p, f.api(1), fault);
      try {
        await button(p, 'Refresh').click();
        wire(await held.arrived.wait(), f, 1);
        await expect(p.getByTestId('permissions-loading')).toBeVisible();
        await privateGone(p, d);
        membership(f, 'delete');
        expect((await p.request.get(f.api(1))).status()).toBe(403);
        await focus(p);
        await expect(p.getByTestId('permissions-denied')).toBeVisible();
        await privateGone(p, d);
        held.release();
        await held.done();
        await expect(p.getByTestId('permissions-denied')).toBeVisible();
        await expect(p.getByTestId('permissions-error')).toHaveCount(0);
        await privateGone(p, d);
      } finally {
        restoreMembership(f);
        await held.cleanup();
      }
    }
  });

  test('AP-B08 shared-session actor change and same-component Agent history reuse fence old GET', async ({
    page: p,
  }, info) => {
    const d = await open(p, f),
      actorBefore = await data(p, '/api/v1/auth/session');
    const held = await hold(p, f.api(1)),
      sibling = await p.context().newPage();
    try {
      await button(p, 'Refresh').click();
      wire(await held.arrived.wait(), f, 1);
      await sibling.goto('/workspace');
      await button(sibling, 'Đăng xuất').click();
      await expect(sibling).toHaveURL(/\/login$/);
      sql(
        `DELETE FROM organization_memberships WHERE organization_id='${f.org}' AND ${accountWhere(f.email('other'))};`,
      );
      await login(sibling, f.email('other'));
      const actorAfter = await data(sibling, '/api/v1/auth/session');
      expect(actorAfter.id).not.toBe(actorBefore.id);
      await focus(p);
      await expect(p.getByTestId('permissions-denied')).toBeVisible();
      await privateGone(p, d);
      held.release();
      await held.done();
      await privateGone(p, d);
    } finally {
      sql(
        `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${f.org}','ADMIN' FROM human_accounts WHERE email='${f.email('other')}' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='ADMIN';`,
      );
      await held.cleanup();
      await sibling.close();
    }
    await login(p, f.email('admin'));
    await open(p, f);
    // The marker survives only same-document navigation; the helper drives the real router and real APIs.
    await p.evaluate(() => {
      window.__permissionsQcDocument = 'same-document';
    });
    const delayed = await hold(p, f.api(1));
    try {
      await button(p, 'Refresh').click();
      await delayed.arrived.wait();
      const r = await read(p, f.api(3), () => pushSpaHistory(p, f.url(3)));
      const current = (await r.json()).data;
      wire(current, f, 3);
      await visible(p, current);
      delayed.release();
      await delayed.done();
      await visible(p, current);
      expect(await p.evaluate(() => window.__permissionsQcDocument)).toBe(
        'same-document',
      );
      await expect(
        p.getByRole('heading', {name: `${d.name} / Permissions`, exact: true}),
      ).toHaveCount(0);
      await shot(p, info, 'reused');
    } finally {
      await delayed.cleanup();
    }
    const back = await read(p, f.api(1), () =>
      p.evaluate(() => history.back()),
    );
    await visible(p, (await back.json()).data);
    const forward = await read(p, f.api(3), () =>
      p.evaluate(() => history.forward()),
    );
    await visible(p, (await forward.json()).data);
  });
});
