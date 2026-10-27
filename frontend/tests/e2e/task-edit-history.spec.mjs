import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

// Synthetic persisted fixture provenance; actual HTTP edit coverage is main's native probe.
const uuid = (p, n) => `${p}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const hook = (p, state) => p.getByTestId(`task-history-${state}`);
const button = (p, name) => p.getByRole('button', {name, exact: true});
const rows = p => hook(p, 'event');
function scope(info, t = 1) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('52000000', n),
    team = uuid('52010000', n * 100 + 1),
    mission = uuid('52020000', n * 100 + 1),
    task = uuid('52030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  return {
    org,
    team,
    mission,
    task,
    api: `/api/v1/organizations/${org}${path}/draft-settings/history`,
    url: `/workspace/${org}${path}/history`,
    detail: `/workspace/${org}${path}`,
  };
}
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
function membership(s, info, role) {
  sql(
    `UPDATE organization_memberships SET role='${role}' WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-history-${info.project.name}-admin@example.test');`,
  );
}
async function authenticate(p, info, kind = 'admin') {
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`task-history-${info.project.name}-${kind}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function response(p, s, action, page = 0) {
  const pending = p.waitForResponse(r => {
    const u = new URL(r.url());
    return (
      u.pathname === s.api &&
      r.request().method() === 'GET' &&
      Number(u.searchParams.get('page') || 0) === page
    );
  });
  await action();
  return pending;
}
async function open(p, info, t = 1) {
  await authenticate(p, info);
  const s = scope(info, t);
  const r = await response(p, s, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  return {s, data: (await r.json()).data};
}
async function render(p, s, d) {
  expect(Object.keys(d).sort()).toEqual(
    'organizationId teamId missionId taskId page pageSize total items'
      .split(' ')
      .sort(),
  );
  expect(d).toMatchObject({
    organizationId: s.org,
    teamId: s.team,
    missionId: s.mission,
    taskId: s.task,
    pageSize: 20,
  });
  await expect(rows(p)).toHaveCount(d.items.length);
  for (const [i, item] of d.items.entries()) {
    expect(Object.keys(item).sort()).toEqual(
      'eventId revision committedAt actorName before after'.split(' ').sort(),
    );
    const row = rows(p).nth(i);
    await expect(
      row.getByRole('heading', {
        name: `Revision ${item.revision}`,
        exact: true,
      }),
    ).toBeVisible();
    await expect(row).toContainText(item.actorName);
    const time = row.locator('time').first();
    expect(Date.parse(await time.getAttribute('datetime'))).toBe(
      Date.parse(item.committedAt),
    );
    for (const side of ['before', 'after']) {
      expect(Object.keys(item[side]).sort()).toEqual(
        'title description acceptanceCriteria priority'.split(' ').sort(),
      );
      for (const [fieldIndex, value] of [
        'title',
        'description',
        'acceptanceCriteria',
        'priority',
      ]
        .map(k => item[side][k])
        .entries()) {
        const comparison = row
          .locator('section.diff')
          .nth(fieldIndex)
          .locator('.comparison > div')
          .nth(side === 'before' ? 0 : 1);
        await expect(comparison.locator('p')).toHaveText(
          value === null ? 'Chưa có dữ liệu' : value,
        );
      }
    }
  }
}
async function shot(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `task-edit-history-${info.project.name}-${state}.png`;
  await p.screenshot({path: join(dir, file), fullPage: true});
  appendFileSync(
    join(dir, 'task-edit-history-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      width: p.viewportSize().width,
      state,
      file,
      test: info.title,
    }) + '\n',
  );
}
async function focus(p) {
  await p.evaluate(() => window.dispatchEvent(new Event('focus')));
}
async function erased(p, secret) {
  await expect(rows(p)).toHaveCount(0);
  await expect(p.locator('main')).not.toContainText(secret);
}
async function hold(p, path, method, status, committed = () => {}) {
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
      const actual = await route.fetch();
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

test.describe('task edit history independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  const writes = new WeakMap();
  test.beforeEach(async ({page: p}, info) => {
    expect(info.config.workers).toBe(1);
    const requests = [];
    writes.set(p, requests);
    p.on('request', r => {
      if (
        !['GET', 'HEAD', 'OPTIONS'].includes(r.method()) &&
        /\/api\/v1\/organizations\//.test(r.url())
      )
        requests.push(`${r.method()} ${r.url()}`);
    });
  });
  test.afterEach(async ({page: p}) => {
    expect(writes.get(p)).toEqual([]);
  });

  test('real persisted 25 events render 20 then5 without duplicate and scoped links', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    expect(data.total).toBe(25);
    expect(data.items.map(x => x.revision)).toEqual(
      Array.from({length: 20}, (_, i) => 25 - i),
    );
    await render(p, s, data);
    await expect(button(p, 'Trang trước')).toBeDisabled();
    await shot(p, info, 'populated');
    const r = await response(p, s, () => button(p, 'Trang sau').click(), 1);
    expect(r.status()).toBe(200);
    const second = (await r.json()).data;
    expect(second).toMatchObject({total: 25, page: 1});
    expect(second.items.map(x => x.revision)).toEqual([5, 4, 3, 2, 1]);
    expect(
      new Set([...data.items, ...second.items].map(x => x.eventId)).size,
    ).toBe(25);
    await render(p, s, second);
    await expect(button(p, 'Trang sau')).toBeDisabled();
    await shot(p, info, 'secondpage');
    const back = await response(p, s, () => button(p, 'Trang trước').click());
    expect((await back.json()).data).toEqual(data);
    await render(p, s, data);
    await p.getByRole('link', {name: 'Quay lại task', exact: true}).click();
    await expect(p).toHaveURL(s.detail);
    await p.getByRole('link', {name: 'Lịch sử chỉnh sửa', exact: true}).click();
    await expect(p).toHaveURL(s.url);
    await expect(rows(p)).toHaveCount(20);
  });

  test('actual empty and out-of-range never fabricate baseline', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info, 2);
    expect(data).toMatchObject({total: 0, page: 0, items: []});
    await expect(hook(p, 'empty')).toBeVisible();
    await render(p, s, data);
    await shot(p, info, 'empty');
    const populated = scope(info);
    for (const page of [2, 100000]) {
      const r = await p.request.get(`${populated.api}?page=${page}`);
      expect(r.status()).toBe(200);
      expect((await r.json()).data).toMatchObject({total: 25, page, items: []});
    }
  });

  test('malformed actual success and unavailable clear private rows and retry', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const secret = data.items[0].after.description;
    expect(typeof secret).toBe('string');
    for (const mode of [
      'malformed',
      'wrong-scope',
      'extra-key',
      'bad-time',
      'bad-count',
      'bad-value',
      'duplicate-event',
      'wrong-order',
      'unavailable',
    ]) {
      await p.route(`**${s.api}*`, async route => {
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        const body = await actual.json();
        if (mode === 'wrong-scope') body.data.taskId = scope(info, 2).task;
        if (mode === 'extra-key') body.data.secret = 'unexpected';
        if (mode === 'bad-time')
          body.data.items[0].committedAt = '2026-02-30T00:00:00Z';
        if (mode === 'bad-count') body.data.total = -1;
        if (mode === 'bad-value') body.data.items[0].after.title = 42;
        if (mode === 'duplicate-event')
          body.data.items[1].eventId = body.data.items[0].eventId;
        if (mode === 'wrong-order') body.data.items.reverse();
        await route.fulfill(
          mode === 'unavailable'
            ? {response: actual, status: 503, body: '{}'}
            : {
                response: actual,
                body:
                  mode === 'malformed' ? '{malformed' : JSON.stringify(body),
              },
        );
      });
      await response(p, s, () => focus(p));
      await expect(hook(p, 'error')).toBeVisible();
      await erased(p, secret);
      if (mode === 'malformed') await shot(p, info, 'error');
      await p.unroute(`**${s.api}*`);
      await response(p, s, () => button(p, 'Tải lại lịch sử').click());
      await render(p, s, data);
    }
  });

  test('corrupt persisted provenance fails closed through real HTTP and clears private UI', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const eventId = data.items[0].eventId;
    const original = sql(
      `SELECT before_json FROM task_edit_events WHERE id='${eventId}';`,
    );
    sql(
      `UPDATE task_edit_events SET before_json='PRIVATE_CORRUPT_HISTORY' WHERE id='${eventId}';`,
    );
    try {
      const r = await response(p, s, () => focus(p));
      expect(r.status()).toBe(500);
      const body = await r.json();
      expect(body.error.code).toBe('TASK_HISTORY_UNAVAILABLE');
      expect(JSON.stringify(body)).not.toContain('PRIVATE_CORRUPT_HISTORY');
      await expect(hook(p, 'error')).toBeVisible();
      await erased(p, data.items[0].after.description);
      await expect(p.locator('main')).not.toContainText(
        'PRIVATE_CORRUPT_HISTORY',
      );
    } finally {
      sql(
        `UPDATE task_edit_events SET before_json='${original.replaceAll("'", "''")}' WHERE id='${eventId}';`,
      );
    }
    await response(p, s, () => button(p, 'Tải lại lịch sử').click());
    await render(p, s, data);
  });

  test('held actual200 cannot restore rows after actual403 role loss', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const held = await hold(p, s.api, 'GET', 200);
    try {
      await focus(p);
      await held.seen;
      // Authority refresh must erase even before the held history body arrives.
      await erased(p, data.items[0].after.description);
      membership(s, info, 'MEMBER');
      const denial = await p.request.get(s.api);
      expect(denial.status()).toBe(403);
      await focus(p);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, data.items[0].after.description);
      await shot(p, info, 'denied');
      await held.release();
      await erased(p, data.items[0].after.description);
    } finally {
      try {
        await held.release();
      } finally {
        membership(s, info, 'ADMIN');
      }
    }
  });

  test('held old scope response fenced after SPA change to empty task', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const held = await hold(p, s.api, 'GET', 200);
    try {
      await focus(p);
      await held.seen;
      const other = scope(info, 2);
      await response(p, other, () => pushSpaHistory(p, other.url));
      await expect(hook(p, 'empty')).toBeVisible();
      await held.release();
      await expect(p).toHaveURL(other.url);
      await expect(hook(p, 'empty')).toBeVisible();
      await erased(p, data.items[0].after.description);
      await expect(
        p.getByRole('link', {name: 'Quay lại task', exact: true}),
      ).toHaveAttribute('href', other.detail);
    } finally {
      await held.release();
    }
  });

  test('shared-session actor change fences held real history even for another ADMIN', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const held = await hold(p, s.api, 'GET', 200);
    const sibling = await p.context().newPage();
    try {
      await focus(p);
      await held.seen;
      const csrfResponse = await sibling.request.get('/api/v1/auth/csrf');
      expect(csrfResponse.status()).toBe(200);
      const csrf = (await csrfResponse.json()).data;
      const logout = await sibling.request.post('/api/v1/auth/logout', {
        headers: {[csrf.headerName]: csrf.token},
      });
      expect([200, 204]).toContain(logout.status());
      await authenticate(sibling, info, 'admin2');
      // admin2 is independently entitled, but the old viewer's response must not cross identity.
      expect((await sibling.request.get(s.api)).status()).toBe(200);
      await focus(p);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, data.items[0].after.description);
      await held.release();
      await erased(p, data.items[0].after.description);
    } finally {
      try {
        await held.release();
      } finally {
        await sibling.close();
      }
    }
  });

  test('MEMBER and foreign user cannot read real private history or see detail history link', async ({
    page: p,
  }, info) => {
    for (const kind of ['member', 'other']) {
      await authenticate(p, info, kind);
      const s = scope(info);
      const r = await p.request.get(s.api);
      expect(r.status()).toBe(403);
      expect(await r.text()).not.toContain('before');
      await p.goto(s.url);
      await expect(hook(p, 'denied')).toBeVisible();
      await expect(rows(p)).toHaveCount(0);
      await p.goto(s.detail);
      await expect(
        p.getByRole('link', {name: 'Lịch sử chỉnh sửa', exact: true}),
      ).toHaveCount(0);
    }
  });
});
