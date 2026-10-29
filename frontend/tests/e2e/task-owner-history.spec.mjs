import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync, readFileSync, existsSync} from 'node:fs';
import {createHash, randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

const uuid = (p, n) => `${p}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const hook = (p, state) => p.getByTestId(`owner-history-${state}`);
const rows = p => hook(p, 'row');
const quote = value => "'" + value.replaceAll("'", "''") + "'";
const focus = p => p.evaluate(() => window.dispatchEvent(new Event('focus')));
const actorMissing = 'Không còn thông tin thành viên trong tổ chức này';
const errorText = 'Không thể tải lịch sử phân công. Vui lòng thử lại.';
const deniedText = 'Cần quyền ADMIN hiện tại để xem lịch sử phân công.';
const canonical =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
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
      'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      input,
      encoding: 'utf8',
      timeout: 15000,
    },
  ).trim();
}
function scope(info, t = 1) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('56000000', n),
    team = uuid('56010000', n * 100 + 1),
    mission = uuid('56020000', n * 100 + 1),
    task = uuid('56030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  const owner = `/api/v1/organizations/${org}${path}/draft-owner`;
  return {
    n,
    org,
    team,
    mission,
    task,
    owner,
    api: `${owner}/history`,
    url: `/workspace/${org}${path}/owner-history`,
    detail: `/workspace/${org}${path}`,
  };
}
const email = (info, kind) =>
  `owner-history-${info.project.name}-${kind}@example.test`;
async function authenticate(p, info, kind = 'admin') {
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(email(info, kind));
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function csrf(request) {
  const r = await request.get('/api/v1/auth/csrf');
  expect(r.status()).toBe(200);
  return (await r.json()).data;
}
async function logout(request) {
  const c = await csrf(request);
  expect([200, 204]).toContain(
    (
      await request.post('/api/v1/auth/logout', {
        headers: {[c.headerName]: c.token},
      })
    ).status(),
  );
}
async function response(p, s, action, page = 0) {
  const stable = business(s);
  const pending = p.waitForResponse(r => {
    const u = new URL(r.url());
    return (
      u.pathname === s.api &&
      r.request().method() === 'GET' &&
      u.searchParams.get('page') === String(page)
    );
  });
  await action();
  const r = await pending;
  await r.finished();
  expect(business(s)).toBe(stable);
  return r;
}
async function open(p, info, t = 1) {
  await authenticate(p, info);
  const s = scope(info, t);
  const r = await response(p, s, () => p.goto(`${s.url}?page=0`));
  expect(r.status()).toBe(200);
  return {s, data: (await r.json()).data};
}
function persisted(s) {
  return JSON.parse(
    sql(`SELECT coalesce(json_agg(x ORDER BY revision DESC),'[]'::json) FROM (
    SELECT e.id AS "eventId",e.revision,e.created_at AS "committedAt",
    CASE WHEN m.account_id IS NOT NULL THEN a.display_name ELSE NULL END AS "actorName",
    e.before_json::json AS before,e.after_json::json AS after
    FROM task_owner_events e LEFT JOIN organization_memberships m ON m.organization_id=e.organization_id AND m.account_id=e.actor_id
    LEFT JOIN human_accounts a ON a.id=m.account_id
    WHERE e.organization_id='${s.org}' AND e.team_id='${s.team}' AND e.mission_id='${s.mission}' AND e.task_id='${s.task}') x;`),
  );
}
// Includes tuple transaction identity: an UPDATE writing the same value must also fail.
function business(s) {
  return sql(
    [
      'tasks',
      'teams',
      'missions',
      'agents',
      'task_dependencies',
      'task_owner_events',
      'task_owner_receipts',
      'task_edit_events',
      'audit_events',
    ]
      .map(
        table =>
          `SELECT '${table}',coalesce(jsonb_agg(x ORDER BY x::text),'[]'::jsonb) FROM (SELECT row_to_json(t)::jsonb || jsonb_build_object('_xmin',xmin::text,'_ctid',ctid::text) AS x FROM ${table} t WHERE organization_id='${s.org}') q;`,
      )
      .join('\n'),
  );
}
async function read(request, s, page = 0) {
  const before = business(s);
  const r = await request.get(`${s.api}?page=${page}`);
  expect(business(s)).toBe(before);
  expect(r.status()).toBe(200);
  const d = (await r.json()).data;
  validate(s, d, page);
  return d;
}
function exact(value, keys) {
  expect(Object.keys(value).sort()).toEqual(keys.split(' ').sort());
}
function owner(value, after = false) {
  exact(value, 'state agent');
  expect(
    after
      ? ['ASSIGNED', 'UNASSIGNED']
      : ['ASSIGNED', 'UNASSIGNED', 'UNAVAILABLE'],
  ).toContain(value.state);
  if (value.state !== 'ASSIGNED') {
    expect(value.agent).toBeNull();
    return;
  }
  exact(value.agent, 'id name level status');
  expect(value.agent.id).toMatch(canonical);
  for (const [key, max] of [
    ['name', 255],
    ['level', 50],
    ['status', 50],
  ]) {
    const text = value.agent[key];
    expect(typeof text).toBe('string');
    expect(text.trim()).not.toBe('');
    expect([...text].length).toBeLessThanOrEqual(max);
    expect(text).not.toMatch(/[\u0000-\u001f\u007f-\u009f\u2028\u2029]/);
    expect(text.isWellFormed()).toBe(true);
  }
  if (after) expect(['DRAFT', 'ACTIVE']).toContain(value.agent.status);
}
function validate(s, d, page) {
  // Frozen History11 / item6 enumerations are asserted exactly.
  exact(
    d,
    'organizationId teamId missionId taskId teamName missionTitle taskTitle page pageSize total items',
  );
  expect(d).toMatchObject({
    organizationId: s.org,
    teamId: s.team,
    missionId: s.mission,
    taskId: s.task,
    page,
    pageSize: 20,
  });
  expect(Number.isSafeInteger(d.total)).toBe(true);
  expect(d.total).toBeGreaterThanOrEqual(0);
  const db = persisted(s);
  expect(d.total).toBe(db.length);
  expect(d.items).toHaveLength(Math.min(20, Math.max(0, d.total - page * 20)));
  expect(d.teamName).toBe('Owner history Team 1');
  expect(d.missionTitle).toBe('Owner history Mission 1');
  expect(d.taskTitle).toBe(
    `Owner history Task ${s.task.endsWith('002') ? 2 : 1}`,
  );
  expect(new Set(d.items.map(x => x.eventId)).size).toBe(d.items.length);
  for (const [i, item] of d.items.entries()) {
    exact(item, 'eventId revision committedAt actorName before after');
    expect(item.eventId).toMatch(canonical);
    expect(Number.isInteger(item.revision)).toBe(true);
    expect(item.revision).toBeGreaterThan(0);
    expect(item.revision).toBeLessThanOrEqual(2147483647);
    if (i) expect(item.revision).toBeLessThan(d.items[i - 1].revision);
    expect(item.committedAt).toMatch(/T.*Z$/);
    expect(Number.isFinite(Date.parse(item.committedAt))).toBe(true);
    const expected = db[page * 20 + i];
    expect({...item, committedAt: Date.parse(item.committedAt)}).toEqual({
      ...expected,
      committedAt: Date.parse(expected.committedAt),
    });
    owner(item.before);
    owner(item.after, true);
  }
}
async function render(p, s, d) {
  validate(s, d, d.page);
  await expect(p.getByTestId('task-owner-history')).toBeVisible();
  await expect(
    p.getByRole('heading', {name: 'Lịch sử phân công Agent', exact: true}),
  ).toBeVisible();
  await expect(rows(p)).toHaveCount(d.items.length);
  for (const [i, item] of d.items.entries()) {
    const row = rows(p).nth(i);
    await expect(row).toContainText(String(item.revision));
    await expect(row).toContainText(
      item.actorName === null ? actorMissing : item.actorName,
    );
    expect(Date.parse(await row.locator('time').getAttribute('datetime'))).toBe(
      Date.parse(item.committedAt),
    );
    for (const [side, snap] of [item.before, item.after].entries()) {
      const description = row.locator('section').nth(side);
      await expect(description.getByRole('heading')).toHaveText(
        side === 0 ? 'Trước' : 'Sau',
      );
      if (snap.state === 'ASSIGNED') {
        await expect(description).toContainText(snap.agent.name);
        await expect(description).toContainText(snap.agent.level);
        await expect(description).toContainText(
          {DRAFT: 'Bản nháp', ACTIVE: 'Hoạt động', SUSPENDED: 'Tạm ngừng'}[
            snap.agent.status
          ] || 'Trạng thái khác',
        );
      } else
        await expect(description).toContainText(
          snap.state === 'UNASSIGNED'
            ? 'Không gán Agent'
            : 'Agent đã được cấu hình nhưng không còn khả dụng trong team này',
        );
    }
  }
  await expect(p.locator('main')).not.toContainText(
    /PRIVATE_OWNER_HISTORY_FOREIGN|560[0-9a-f-]{30,}|UNASSIGNED|UNAVAILABLE|ASSIGNED|before_owner_agent_id|idempotency|fingerprint|original_response/,
  );
  await expect(
    p.getByRole('link', {name: 'Về chi tiết task', exact: true}),
  ).toHaveAttribute('href', s.detail);
}
async function erased(p) {
  await expect(rows(p)).toHaveCount(0);
  await expect(hook(p, 'list')).toHaveCount(0);
  await expect(p.locator('main')).not.toContainText(
    /Owner history (Team|Mission|Task|Agent)|560[0-9a-f-]{30,}/,
  );
}
async function shot(p, info, s, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `owner-history-${info.project.name}-${state}.png`,
    path = join(dir, file),
    manifest = join(dir, 'owner-history-manifest.jsonl');
  const records = existsSync(manifest)
    ? readFileSync(manifest, 'utf8')
        .trim()
        .split('\n')
        .filter(Boolean)
        .map(x => JSON.parse(x))
    : [];
  expect(
    records.filter(x => x.project === info.project.name && x.state === state),
    'use a clean visual output directory',
  ).toEqual([]);
  await p.screenshot({path, fullPage: true});
  const bytes = readFileSync(path);
  const imageSHA256 = createHash('sha256').update(bytes).digest('hex');
  expect(records.map(x => x.imageSHA256)).not.toContain(imageSHA256);
  expect(bytes.readUInt32BE(16)).toBe(p.viewportSize().width);
  appendFileSync(
    manifest,
    JSON.stringify({
      project: info.project.name,
      width: p.viewportSize().width,
      state,
      file,
      organizationId: s.org,
      teamId: s.team,
      missionId: s.mission,
      taskId: s.task,
      url: p.url(),
      test: info.title,
      capturetimestamp: new Date().toISOString(),
      imageSHA256,
    }) + '\n',
  );
}
function membership(s, info, role) {
  sql(
    `UPDATE organization_memberships SET role='${role}' WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${email(info, 'admin')}');`,
  );
}
async function seed(browser, info) {
  const s = scope(info);
  const count = persisted(s).length;
  if (count !== 0) {
    expect(count, 'dirty fixture: never synthesize/reset history').toBe(21);
    return;
  }
  const task = JSON.parse(
    sql(`SELECT row_to_json(t) FROM tasks t WHERE id='${s.task}';`),
  );
  expect(task.owner_agent_id).toBe(uuid('56040000', 5001));
  expect(task.task_owner_revision).toBe(0);
  const context = await browser.newContext({
    baseURL: info.project.use.baseURL,
  });
  const p = await context.newPage();
  try {
    await authenticate(p, info);
    for (let i = 0; i < 21; i++) {
      if (i === 10) {
        await logout(p.request);
        await authenticate(p, info, 'admin2');
      }
      const r = await p.request.get(s.owner);
      expect(r.status()).toBe(200);
      const source = (await r.json()).data;
      if (i === 0)
        expect(source.owner).toEqual({state: 'UNAVAILABLE', agent: null});
      const target =
        i % 3 === 0
          ? null
          : uuid('56040000', s.n * 1000 + (i % 3 === 1 ? 1 : 2));
      let candidate = null;
      if (target) {
        const c = await p.request.get(`${s.owner}/eligible-owners/${target}`);
        expect(c.status()).toBe(200);
        candidate = (await c.json()).data;
      }
      const c = await csrf(p.request);
      const command = await p.request.post(s.owner, {
        headers: {[c.headerName]: c.token, 'Idempotency-Key': randomUUID()},
        data: {
          expectedRevision: source.revision,
          expectedFingerprint: source.fingerprint,
          ownerAgentId: target,
          ownerFingerprint: candidate?.fingerprint ?? null,
        },
      });
      expect(command.status()).toBe(201);
      expect((await command.json()).data.revision).toBe(i + 1);
    }
    expect(persisted(s)).toHaveLength(21);
  } finally {
    await context.close();
  }
}
async function hold(
  p,
  path,
  method,
  status,
  committed = () => {},
  fetchReal = route => route.fetch(),
) {
  let arrive, arrivalError, complete, completionError, openGate;
  const seen = new Promise((resolve, reject) => {
    arrive = resolve;
    arrivalError = reject;
  });
  const done = new Promise((resolve, reject) => {
    complete = resolve;
    completionError = reject;
  });
  void seen.catch(() => {});
  void done.catch(() => {});
  const gate = new Promise(resolve => {
    openGate = resolve;
  });
  let original,
    released = false,
    releaseResult;
  const match = url => url.pathname === path;
  const handler = async route => {
    const request = route.request();
    if (original || request.method() !== method) return route.continue();
    original = request; // Capture before route.fetch or any authority change.
    try {
      const actual = await fetchReal(route);
      expect(actual.status()).toBe(status);
      await committed(actual);
      arrive();
      await gate;
      expect(released).toBe(true);
      try {
        await route.fulfill({response: actual});
      } catch (error) {
        // A message match alone is never evidence of cancellation. Only the
        // captured original request's actual failure permits this transport race.
        if (!released || original.failure() === null) throw error;
      }
      complete();
    } catch (error) {
      arrivalError(error);
      completionError(error);
    }
  };
  await p.route(match, handler);
  return {
    seen,
    release() {
      if (releaseResult) return releaseResult;
      released = true;
      openGate();
      releaseResult = (async () => {
        if (original) {
          await done;
          const delivered = await original.response();
          if (delivered) {
            const bodyError = await delivered.finished();
            if (bodyError && original.failure() === null) throw bodyError;
          } else {
            expect(original.failure()).not.toBeNull();
          }
        }
        await p.unroute(match, handler);
        await p.evaluate(
          () =>
            new Promise(resolve =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
        );
      })();
      return releaseResult;
    },
  };
}

test.describe('task owner history independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  const writes = new WeakMap(),
    baseline = new WeakMap();
  test.beforeAll(async ({browser}, info) => {
    expect(info.config.workers).toBe(1);
    expect(info.project.retries).toBe(0);
    expect(process.env.E2E_PASSWORD).toBeTruthy();
    await seed(browser, info);
  });
  test.afterAll(async ({}, info) => {
    const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
    const records = readFileSync(
      join(dir, 'owner-history-manifest.jsonl'),
      'utf8',
    )
      .trim()
      .split('\n')
      .filter(Boolean)
      .map(x => JSON.parse(x))
      .filter(x => x.project === info.project.name);
    expect(records.map(x => x.state).sort()).toEqual(
      ['empty', 'populated', 'second-page', 'error', 'denied'].sort(),
    );
    expect(new Set(records.map(x => x.imageSHA256)).size).toBe(5);
    const s = scope(info);
    for (const record of records) {
      expect(record).toMatchObject({
        organizationId: s.org,
        teamId: s.team,
        missionId: s.mission,
        width: {desktop: 1440, tablet: 768, mobile: 390}[info.project.name],
      });
      expect(record.taskId).toBe(
        scope(info, record.state === 'empty' ? 2 : 1).task,
      );
      const bytes = readFileSync(join(dir, record.file));
      expect(bytes.readUInt32BE(16)).toBe(record.width);
      expect(createHash('sha256').update(bytes).digest('hex')).toBe(
        record.imageSHA256,
      );
    }
  });
  test.beforeEach(async ({page: p}, info) => {
    const requests = [];
    writes.set(p, requests);
    baseline.set(p, business(scope(info)));
    p.on('request', r => {
      if (
        !['GET', 'HEAD', 'OPTIONS'].includes(r.method()) &&
        new URL(r.url()).pathname.startsWith('/api/v1/organizations/')
      )
        requests.push(`${r.method()} ${r.url()}`);
    });
  });
  test.afterEach(async ({page: p}, info) => {
    expect(writes.get(p)).toEqual([]);
    expect(business(scope(info))).toBe(baseline.get(p));
  });

  test('real persisted events paginate twenty plus one, native Back, detail entry and responsive shell', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    expect(data.total).toBe(21);
    expect(data.items.map(x => x.revision)).toEqual(
      Array.from({length: 20}, (_, i) => 21 - i),
    );
    await render(p, s, data);
    await expect(button(p, 'Trang trước')).toBeDisabled();
    await shot(p, info, s, 'populated');
    const next = await response(p, s, () => button(p, 'Trang sau').click(), 1);
    const second = (await next.json()).data;
    await render(p, s, second);
    expect(second.items.map(x => x.revision)).toEqual([1]);
    expect(second.items[0].before).toEqual({state: 'UNAVAILABLE', agent: null});
    expect(
      new Set([...data.items, ...second.items].map(x => x.eventId)).size,
    ).toBe(21);
    await expect(button(p, 'Trang sau')).toBeDisabled();
    await shot(p, info, s, 'second-page');
    await response(p, s, () => p.goBack());
    await render(p, s, data);
    const nav = p.getByRole('navigation', {
      name: 'Workspace navigation',
      exact: true,
    });
    if (p.viewportSize().width < 1024) {
      await expect(nav).toBeHidden();
      await button(p, 'Menu').click();
      const dialog = p.getByRole('dialog', {
        name: 'Workspace navigation',
        exact: true,
      });
      await expect(dialog).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(dialog).not.toBeVisible();
      await expect(button(p, 'Menu')).toBeFocused();
      await button(p, 'Menu').click();
      await expect(dialog).toBeVisible();
      await button(p, 'Close menu').click();
      await expect(dialog).not.toBeVisible();
    } else {
      await expect(button(p, 'Menu')).toBeHidden();
      await expect(nav).toBeVisible();
      expect(
        (await p.locator('aside.desktop-sidebar').boundingBox()).width,
      ).toBe(224);
    }
    for (const name of ['Trang trước', 'Trang sau', 'Tải lại lịch sử']) {
      const box = await button(p, name).boundingBox();
      expect(box.height).toBeGreaterThanOrEqual(44);
    }
    const dialogs = [];
    p.on('dialog', async d => {
      dialogs.push(d.type());
      await d.dismiss();
    });
    await p.getByRole('link', {name: 'Về chi tiết task', exact: true}).click();
    await expect(p).toHaveURL(s.detail);
    await response(p, s, () =>
      p.getByRole('link', {name: 'Lịch sử phân công', exact: true}).click(),
    );
    await render(p, s, data);
    expect(dialogs).toEqual([]);
    expect(await read(p.request, s)).toEqual(data);
  });

  test('every refresh focus visibility and page reads fresh session then raw ADMIN workspace without persistence', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const reads = [];
    p.on('request', r => {
      const path = new URL(r.url()).pathname;
      if (
        [
          s.api,
          '/api/v1/auth/session',
          `/api/v1/auth/workspaces/${s.org}`,
        ].includes(path)
      )
        reads.push(path);
    });
    for (const [page, action] of [
      [0, () => focus(p)],
      [
        0,
        () =>
          p.evaluate(() =>
            document.dispatchEvent(new Event('visibilitychange')),
          ),
      ],
      [0, () => button(p, 'Tải lại lịch sử').click()],
      [1, () => button(p, 'Trang sau').click()],
    ]) {
      reads.length = 0;
      const r = await response(p, s, action, page);
      expect(r.status()).toBe(200);
      expect(reads).toEqual([
        '/api/v1/auth/session',
        `/api/v1/auth/workspaces/${s.org}`,
        s.api,
      ]);
      await render(p, s, (await r.json()).data);
    }
    const storage = await p.evaluate(() =>
      JSON.stringify({local: {...localStorage}, session: {...sessionStorage}}),
    );
    for (const item of data.items) expect(storage).not.toContain(item.eventId);
    expect(storage).not.toMatch(/"(?:before|after|actorName|owner-history)"/);
  });

  test('genuine empty and out-of-range retain actual total with reset', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info, 2);
    expect(data.total).toBe(0);
    await expect(hook(p, 'empty')).toHaveText('Chưa có lịch sử phân công.');
    await render(p, s, data);
    await shot(p, info, s, 'empty');
    const populated = scope(info);
    for (const page of [2, 100000]) {
      const r = await response(
        p,
        populated,
        () => p.goto(`${populated.url}?page=${page}`),
        page,
      );
      const d = (await r.json()).data;
      expect(d).toMatchObject({total: 21, items: []});
      await render(p, populated, d);
      await expect(hook(p, 'empty')).toHaveText(
        'Không có bản ghi trên trang này.',
      );
      await expect(button(p, 'Trang sau')).toBeDisabled();
      await response(p, populated, () =>
        button(p, 'Đặt lại trang đầu').click(),
      );
      await expect(rows(p)).toHaveCount(20);
    }
  });

  test('current actor rename and removed same-org membership null; immutable Agent snapshots survive metadata move', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const account = JSON.parse(
      sql(
        `SELECT row_to_json(a) FROM human_accounts a WHERE email='${email(info, 'admin2')}';`,
      ),
    );
    const memberships = sql(
      `SELECT coalesce(json_agg(m),'[]'::json) FROM organization_memberships m WHERE account_id='${account.id}';`,
    );
    const agent = JSON.parse(
      sql(
        `SELECT row_to_json(a) FROM agents a WHERE id='${uuid('56040000', s.n * 1000 + 1)}';`,
      ),
    );
    const actorRevisions = persisted(s)
      .filter(x => x.actorName === account.display_name)
      .map(x => x.revision);
    expect(actorRevisions.length).toBeGreaterThan(0);
    try {
      sql(
        `UPDATE human_accounts SET display_name='Renamed history writer',enabled=false WHERE id='${account.id}';UPDATE agents SET name='Changed current Agent',status='SUSPENDED',team_id='${uuid('56010000', s.n * 100 + 2)}' WHERE id='${agent.id}';`,
      );
      let r = await response(p, s, () => focus(p));
      let d = (await r.json()).data;
      await render(p, s, d);
      for (const item of d.items) {
        expect(item.before).toEqual(
          data.items.find(x => x.revision === item.revision).before,
        );
        expect(item.after).toEqual(
          data.items.find(x => x.revision === item.revision).after,
        );
        if (actorRevisions.includes(item.revision))
          expect(item.actorName).toBe('Renamed history writer');
      }
      sql(
        `DELETE FROM organization_memberships WHERE organization_id='${s.org}' AND account_id='${account.id}';INSERT INTO organization_memberships(account_id,organization_id,role) VALUES('${account.id}','${uuid('56000000', 5)}','MEMBER') ON CONFLICT DO NOTHING;`,
      );
      r = await response(p, s, () => focus(p));
      d = (await r.json()).data;
      await render(p, s, d);
      for (const item of d.items.filter(x =>
        actorRevisions.includes(x.revision),
      )) {
        expect(Object.hasOwn(item, 'actorName')).toBe(true);
        expect(item.actorName).toBeNull();
      }
      await expect(p.locator('main')).not.toContainText(
        'Renamed history writer',
      );
    } finally {
      sql(
        `DELETE FROM organization_memberships WHERE account_id='${account.id}';INSERT INTO organization_memberships SELECT * FROM json_populate_recordset(NULL::organization_memberships,${quote(memberships)});UPDATE human_accounts SET display_name=${quote(account.display_name)},enabled=${account.enabled} WHERE id='${account.id}';UPDATE agents SET name=${quote(agent.name)},status=${quote(agent.status)},team_id=${quote(agent.team_id)},organization_id=${quote(agent.organization_id)} WHERE id='${agent.id}';`,
      );
      // Controlled mutations replace xmin/ctid; preserve a fresh restored baseline.
      baseline.set(p, business(s));
    }
    await response(p, s, () => focus(p));
    await render(p, s, data);
  });

  for (const mode of ['failed', 'malformed'])
    test(`${mode} real history delivery erases metadata and retry is GET only`, async ({
      page: p,
    }, info) => {
      const {s, data} = await open(p, info);
      const match = u => u.pathname === s.api;
      await p.route(match, async route => {
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        await route.fulfill({
          response: actual,
          ...(mode === 'failed'
            ? {status: 503, body: 'Unavailable'}
            : {body: '{malformed'}),
        });
      });
      try {
        await response(p, s, () => button(p, 'Tải lại lịch sử').click());
        await expect(hook(p, 'error')).toHaveText(errorText);
        await erased(p);
        await expect(hook(p, 'empty')).toHaveCount(0);
        if (mode === 'failed') await shot(p, info, s, 'error');
      } finally {
        await p.unroute(match);
      }
      await response(p, s, () => button(p, 'Tải lại lịch sử').click());
      await render(p, s, data);
    });

  test('held real denial cannot erase a newer authorized page', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const held = await hold(
      p,
      s.api,
      'GET',
      403,
      () => {},
      async route => {
        membership(s, info, 'MEMBER');
        try {
          return await route.fetch();
        } finally {
          membership(s, info, 'ADMIN');
        }
      },
    );
    try {
      await focus(p);
      await held.seen;
      await erased(p);
      await response(p, s, () => focus(p));
      await render(p, s, data);
      await held.release();
      await render(p, s, data);
      await expect(hook(p, 'denied')).toHaveCount(0);
    } finally {
      try {
        await held.release();
      } finally {
        membership(s, info, 'ADMIN');
      }
    }
  });

  test('corrupted delivery of real DTO fails closed for strict scope keys snapshots order and count', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const match = u => u.pathname === s.api;
    for (const mode of [
      'scope',
      'extra',
      'missing-actor',
      'duplicate',
      'order',
      'count',
      'time',
      'after-unavailable',
      'snapshot-extra',
    ]) {
      await p.route(match, async route => {
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        const body = await actual.json();
        if (mode === 'scope') body.data.taskId = scope(info, 2).task;
        if (mode === 'extra') body.data.private = 'unexpected';
        if (mode === 'missing-actor') delete body.data.items[0].actorName;
        if (mode === 'duplicate')
          body.data.items[1].eventId = body.data.items[0].eventId;
        if (mode === 'order') body.data.items.reverse();
        if (mode === 'count') body.data.total = -1;
        if (mode === 'time') body.data.items[0].committedAt = 'not-an-instant';
        if (mode === 'after-unavailable')
          body.data.items[0].after = {state: 'UNAVAILABLE', agent: null};
        if (mode === 'snapshot-extra')
          body.data.items[0].before.rawOwner = 'PRIVATE_RAW';
        await route.fulfill({response: actual, body: JSON.stringify(body)});
      });
      try {
        await response(p, s, () => focus(p));
        await expect(hook(p, 'error')).toHaveText(errorText);
        await erased(p);
        await expect(hook(p, 'empty')).toHaveCount(0);
      } finally {
        await p.unroute(match);
      }
      await response(p, s, () => button(p, 'Tải lại lịch sử').click());
      await render(p, s, data);
    }
  });

  test('strict scoped privacy rejects member foreign and wrong scoped parents without GET writes', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await authenticate(p, info);
    for (const [url, status] of [
      [s.api.replace(s.team, uuid('56010000', 501)), 404],
      [s.api.replace(s.mission, uuid('56020000', 501)), 404],
      [s.api.replace(s.task, uuid('56030000', 5001)), 404],
    ]) {
      const before = business(s);
      const r = await p.request.get(url);
      expect(r.status()).toBe(status);
      expect(await r.text()).not.toMatch(/Owner history|before_json|56040000/);
      expect(business(s)).toBe(before);
    }
    for (const kind of ['member', 'other']) {
      await logout(p.request);
      await authenticate(p, info, kind);
      const before = business(s);
      const r = await p.request.get(s.api);
      expect(r.status()).toBe(403);
      expect(await r.text()).not.toMatch(/Owner history|before_json|56040000/);
      expect(business(s)).toBe(before);
      await p.goto(`${s.url}?page=0`);
      await expect(hook(p, 'denied')).toHaveText(deniedText);
      await erased(p);
      await p.goto(s.detail);
      await expect(
        p.getByRole('link', {name: 'Lịch sử phân công', exact: true}),
      ).toHaveCount(0);
    }
  });

  test('invalid local page queries never load history', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const s = scope(info);
    const requests = [];
    p.on('request', r => {
      if (new URL(r.url()).pathname === s.api) requests.push(r.url());
    });
    for (const query of [
      'page=-1',
      'page=01',
      'page=100001',
      'page=1&page=2',
      'page=1e0',
      'page=0&unknown=1',
    ]) {
      await p.goto(`${s.url}?${query}`);
      await expect(hook(p, 'error')).toHaveText('Trang lịch sử không hợp lệ.');
      await erased(p);
    }
    expect(requests).toEqual([]);
  });

  for (const fence of ['role', 'account', 'route', 'page'])
    test(`held real response drained after ${fence} fence`, async ({
      page: p,
    }, info) => {
      const {s, data} = await open(p, info);
      const held = await hold(p, s.api, 'GET', 200);
      let sibling;
      try {
        await focus(p);
        await held.seen;
        await erased(p);
        await expect(hook(p, 'loading')).toBeVisible();
        if (fence === 'role') {
          membership(s, info, 'MEMBER');
          expect((await p.request.get(s.api)).status()).toBe(403);
          await focus(p);
          await expect(hook(p, 'denied')).toHaveText(deniedText);
          await erased(p);
          await shot(p, info, s, 'denied');
        }
        if (fence === 'account') {
          sibling = await p.context().newPage();
          await logout(sibling.request);
          await authenticate(sibling, info, 'admin2');
          expect((await sibling.request.get(s.api)).status()).toBe(200);
          await focus(p);
          await expect(hook(p, 'denied')).toBeVisible();
          await erased(p);
        }
        if (fence === 'route') {
          const other = scope(info, 2);
          await response(p, other, () =>
            pushSpaHistory(p, `${other.url}?page=0`),
          );
          await expect(hook(p, 'empty')).toHaveText(
            'Chưa có lịch sử phân công.',
          );
        }
        if (fence === 'page') {
          const r = await response(
            p,
            s,
            () => pushSpaHistory(p, `${s.url}?page=1`),
            1,
          );
          await render(p, s, (await r.json()).data);
        }
        await held.release();
        if (fence === 'role' || fence === 'account') {
          await erased(p);
          await expect(hook(p, 'denied')).toBeVisible();
        }
        if (fence === 'route') {
          await expect(rows(p)).toHaveCount(0);
          await expect(hook(p, 'empty')).toHaveText(
            'Chưa có lịch sử phân công.',
          );
          await expect(p).toHaveURL(`${scope(info, 2).url}?page=0`);
        }
        if (fence === 'page') {
          await expect(rows(p)).toHaveCount(1);
          await expect(rows(p).first()).toContainText(String(data.total - 20));
          await expect(p).toHaveURL(`${s.url}?page=1`);
        }
      } finally {
        try {
          await held.release();
        } finally {
          if (fence === 'role') membership(s, info, 'ADMIN');
          if (sibling) await sibling.close();
        }
      }
    });
});
