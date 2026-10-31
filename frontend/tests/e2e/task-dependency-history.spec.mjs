import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync, readFileSync} from 'node:fs';
import {createHash, randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

const id = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const hook = (p, state) => p.getByTestId(`dependency-history-${state}`);
const button = (p, name) => p.getByRole('button', {name, exact: true});
const quote = s => "'" + s.replaceAll("'", "''") + "'";
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
function scope(info, t = 19) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  const org = id('58000000', n),
    team = id('58010000', n * 100 + 1),
    mission = id('58020000', n * 100 + 1),
    task = id('58030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  return {
    n,
    org,
    team,
    mission,
    task,
    api: `/api/v1/organizations/${org}${path}/draft-dependencies`,
    url: `/workspace/${org}${path}/dependency-history`,
    detail: `/workspace/${org}${path}`,
  };
}
// Snapshot all organization-owned rows in every business table, including tuple identity.
function business(s) {
  const tables = sql(
    "select table_name from information_schema.columns where table_schema='public' and column_name='organization_id' order by table_name;",
  ).split('\n');
  const parts = tables.map(
    t =>
      `select '${t}' as name,md5(coalesce(string_agg(row_to_json(x)::text||'@'||xmin::text||':'||ctid::text,'|' order by row_to_json(x)::text,ctid),'')) as digest from ${t} x where organization_id='${s.org}'`,
  );
  return sql(
    'select json_object_agg(name,digest order by name) from (' +
      parts.join(' union all ') +
      ') x;',
  );
}
async function login(p, info, kind = 'admin') {
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`task-dependency-history-${info.project.name}-${kind}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, s) {
  await p.goto(s.url);
  await expect(hook(p, 'list')).toBeVisible();
}
async function save(p, s, target) {
  const context = (await (await p.request.get(s.api)).json()).data;
  const candidate = (
    await (
      await p.request.get(
        `${s.api}/eligible-dependencies/${id('58030000', s.n * 1000 + target)}`,
      )
    ).json()
  ).data;
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  const r = await p.request.post(s.api, {
    headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID()},
    data: {
      expectedRevision: context.revision,
      expectedFingerprint: context.fingerprint,
      dependencies: [
        {taskId: candidate.id, fingerprint: candidate.fingerprint},
      ],
    },
  });
  expect(r.status()).toBe(201);
  return (await r.json()).data;
}
async function capture(p, info, s, state) {
  await expect(p.locator('body')).not.toBeEmpty();
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.QA_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `task-dependency-history-${info.project.name}-${state}.png`;
  await p.screenshot({path: join(dir, file), fullPage: true});
  const bytes = readFileSync(join(dir, file));
  appendFileSync(
    join(dir, 'task-dependency-history-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      viewport: info.project.use.viewport,
      state,
      file,
      path: new URL(p.url()).pathname,
      scope: {
        organizationId: s.org,
        teamId: s.team,
        missionId: s.mission,
        taskId: s.task,
      },
      capturetimestamp: new Date().toISOString(),
      imageSHA256: createHash('sha256').update(bytes).digest('hex'),
      width: bytes.readUInt32BE(16),
      height: bytes.readUInt32BE(20),
    }) + '\n',
  );
}
async function held(p, url) {
  let seen, unblock, settled, requestEnded, request;
  const arrived = new Promise(r => {
    seen = r;
  });
  const gate = new Promise(r => {
    unblock = r;
  });
  const drained = new Promise(r => {
    settled = r;
  });
  const ended = new Promise(r => {
    requestEnded = r;
  });
  const completed = new WeakSet();
  const onEnd = r => {
    completed.add(r);
    if (r !== request) return;
    p.off('requestfinished', onEnd);
    p.off('requestfailed', onEnd);
    requestEnded();
  };
  p.on('requestfinished', onEnd);
  p.on('requestfailed', onEnd);
  let first = true;
  await p.route(url + '?page=0', async route => {
    if (!first) {
      await route.continue();
      return;
    }
    first = false;
    request = route.request();
    if (completed.has(request)) onEnd(request);
    try {
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      seen();
      await gate;
      try {
        await route.fulfill({response});
      } catch (e) {
        if (!/closed|cancel|handled|disposed/i.test(String(e))) throw e;
      }
    } finally {
      settled();
    }
  });
  return {
    arrived,
    release: async () => {
      unblock();
      if (!request) return;
      await Promise.all([drained, ended]);
      if (!p.isClosed())
        await p.evaluate(
          () =>
            new Promise(resolve => {
              requestAnimationFrame(() => requestAnimationFrame(resolve));
            }),
        );
    },
  };
}
function membership(s, info, role) {
  sql(
    `update organization_memberships set role='${role}' where organization_id='${s.org}' and account_id=(select id from human_accounts where email='task-dependency-history-${info.project.name}-admin@example.test');`,
  );
}

test.describe('task dependency history independent QC', () => {
  test('01 legacy configuration has genuine empty history and no writes', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info, 20);
    const before = business(s);
    let posts = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && r.url().includes(s.api)) posts++;
    });
    await p.goto(s.url);
    await expect(hook(p, 'empty')).toHaveText(
      'Chưa có lịch sử thay đổi phụ thuộc.',
    );
    await capture(p, info, s, 'empty');
    expect(business(s)).toBe(before);
    expect(posts).toBe(0);
  });
  test('02 actual21 revisions match database, page and native Back', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    for (let i = 0; i < 21; i++) await save(p, s, i % 2 ? 6 : 5);
    const before = business(s);
    await p.goto(s.detail);
    await p.getByRole('link', {name: 'Lịch sử phụ thuộc', exact: true}).click();
    await expect(hook(p, 'row')).toHaveCount(20);
    const actual = (await (await p.request.get(s.api + '/history')).json())
      .data;
    const stored = JSON.parse(
      sql(
        `select json_agg(json_build_object('eventId',id,'revision',revision,'before',before_json::json,'after',after_json::json) order by revision desc) from task_dependency_events where task_id='${s.task}';`,
      ),
    );
    expect(actual.total).toBe(21);
    expect(
      actual.items.map(({eventId, revision, before, after}) => ({
        eventId,
        revision,
        before,
        after,
      })),
    ).toEqual(stored.slice(0, 20));
    await capture(p, info, s, 'populated');
    await button(p, 'Trang sau').click();
    await expect(p).toHaveURL(s.url + '?page=1');
    await expect(hook(p, 'row')).toHaveCount(1);
    await capture(p, info, s, 'second-page');
    await p.goBack();
    await expect(hook(p, 'row')).toHaveCount(20);
    expect(business(s)).toBe(before);
  });
  test('03 current graph corruption and changed target metadata preserve snapshots', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info),
      target = id('58030000', s.n * 1000 + 5);
    const old = JSON.parse(
      sql(`select row_to_json(x) from tasks x where id='${target}';`),
    );
    try {
      sql(
        `update tasks set title='Changed live target',status='COMPLETED' where id='${target}';insert into task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id) values('${s.org}','${s.team}','${s.mission}','${target}','${s.task}');`,
      );
      const before = business(s);
      expect((await p.request.get(s.api)).status()).toBe(500);
      await open(p, s);
      await expect(hook(p, 'list')).not.toContainText('Changed live target');
      expect(business(s)).toBe(before);
    } finally {
      sql(
        `delete from task_dependencies where task_id='${target}' and dependency_task_id='${s.task}';update tasks set title=${quote(old.title)},status=${quote(old.status)} where id='${target}';`,
      );
    }
  });
  test('04 current member has no history entry or direct authority', async ({
    page: p,
  }, info) => {
    await login(p, info, 'member');
    const s = scope(info);
    const before = business(s);
    await p.goto(s.detail);
    await expect(p.getByRole('heading', {level: 1})).toHaveText(
      'Task dependency history Task 19',
    );
    await expect(
      p.getByRole('link', {name: 'Lịch sử phụ thuộc', exact: true}),
    ).toHaveCount(0);
    expect((await p.request.get(s.api + '/history?unknown=1')).status()).toBe(
      403,
    );
    await p.goto(s.url);
    await expect(hook(p, 'denied')).toBeVisible();
    await expect(hook(p, 'row')).toHaveCount(0);
    await capture(p, info, s, 'denied');
    expect(business(s)).toBe(before);
  });
  test('05 transport/malformed read is error, GET reload recovers without writes', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    const before = business(s);
    await p.route(s.api + '/history?*', r => r.abort());
    await p.goto(s.url);
    await expect(hook(p, 'error')).toBeVisible();
    await expect(hook(p, 'empty')).toHaveCount(0);
    await capture(p, info, s, 'error');
    await p.unrouteAll();
    await button(p, 'Tải lại lịch sử').click();
    await expect(hook(p, 'row')).toHaveCount(20);
    await p.route(s.api + '/history?*', async route => {
      const r = await route.fetch();
      const b = await r.json();
      b.data.items[0].after = null;
      await route.fulfill({response: r, json: b});
    });
    await button(p, 'Tải lại lịch sử').click();
    await expect(hook(p, 'error')).toBeVisible();
    await expect(hook(p, 'row')).toHaveCount(0);
    expect(business(s)).toBe(before);
  });
  test('06 held actual response cannot restore rows after role loss', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    const h = await held(p, s.api + '/history');
    try {
      await p.goto(s.url);
      await h.arrived;
      membership(s, info, 'MEMBER');
      const before = business(s);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
      await h.release();
      await expect(hook(p, 'row')).toHaveCount(0);
      expect(business(s)).toBe(before);
    } finally {
      await h.release();
      membership(s, info, 'ADMIN');
    }
  });
  test('07 held actual response cannot overwrite reused task route', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info),
      next = scope(info, 20);
    const before = business(s);
    const h = await held(p, s.api + '/history');
    await p.goto(s.url);
    await h.arrived;
    await pushSpaHistory(p, next.url);
    await expect(hook(p, 'empty')).toBeVisible();
    await h.release();
    await expect(hook(p, 'row')).toHaveCount(0);
    await expect(p).toHaveURL(next.url);
    expect(business(s)).toBe(before);
  });
  test('08 invalid page query rejects locally and out-of-range keeps actual total', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    const before = business(s);
    for (const query of [
      '?page=-1',
      '?page=01',
      '?page=0&page=1',
      '?extra=1',
    ]) {
      await p.goto(s.url + query);
      await expect(hook(p, 'error')).toHaveText('Trang lịch sử không hợp lệ.');
      await expect(hook(p, 'row')).toHaveCount(0);
    }
    await p.goto(s.url + '?page=100000');
    await expect(hook(p, 'empty')).toHaveText(
      'Không có bản ghi trên trang này.',
    );
    await expect(
      p.getByText('21 lần thay đổi phụ thuộc · Trang 100001'),
    ).toBeVisible();
    await button(p, 'Đặt lại trang đầu').click();
    await expect(hook(p, 'row')).toHaveCount(20);
    expect(business(s)).toBe(before);
  });
  test('09 current author rename/removal shows explicit missing member', async ({
    page: p,
  }, info) => {
    await login(p, info, 'admin2');
    const s = scope(info);
    const email = `task-dependency-history-${info.project.name}-admin@example.test`;
    const author = JSON.parse(
      sql(
        `select row_to_json(x) from human_accounts x where email=${quote(email)};`,
      ),
    );
    try {
      sql(
        `update human_accounts set display_name='Current author name' where id='${author.id}';`,
      );
      await open(p, s);
      await expect(hook(p, 'row').first()).toContainText('Current author name');
      sql(
        `delete from organization_memberships where organization_id='${s.org}' and account_id='${author.id}';`,
      );
      const before = business(s);
      await button(p, 'Tải lại lịch sử').click();
      await expect(hook(p, 'row').first()).toContainText(
        'Không còn thông tin thành viên trong tổ chức này',
      );
      expect(business(s)).toBe(before);
    } finally {
      sql(
        `insert into organization_memberships(organization_id,account_id,role) values('${s.org}','${author.id}','ADMIN') on conflict(organization_id,account_id) do update set role='ADMIN';update human_accounts set display_name=${quote(author.display_name)} where id='${author.id}';`,
      );
    }
  });
  test('10 compact Menu Escape returns focus without changing business state', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    const before = business(s);
    await open(p, s);
    if (info.project.name !== 'desktop') {
      const menu = button(p, 'Menu');
      await menu.click();
      await expect(p.getByRole('dialog')).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(p.getByRole('dialog')).not.toBeVisible();
      await expect(menu).toBeFocused();
    } else {
      await expect(
        p
          .locator('.desktop-sidebar')
          .getByRole('link', {name: 'Approvals', exact: true}),
      ).toBeVisible();
    }
    expect(business(s)).toBe(before);
  });
  test('11 held response cannot restore another account private history', async ({
    page: p,
  }, info) => {
    await login(p, info);
    const s = scope(info);
    const h = await held(p, s.api + '/history');
    let sibling;
    try {
      await p.goto(s.url);
      await h.arrived;
      sibling = await p.context().newPage();
      const csrf = (
        await (await sibling.request.get('/api/v1/auth/csrf')).json()
      ).data;
      expect([200, 204]).toContain(
        (
          await sibling.request.post('/api/v1/auth/logout', {
            headers: {[csrf.headerName]: csrf.token},
          })
        ).status(),
      );
      await login(sibling, info, 'admin2');
      const before = business(s);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
      await h.release();
      await expect(hook(p, 'row')).toHaveCount(0);
      expect(business(s)).toBe(before);
    } finally {
      await h.release();
      if (sibling) await sibling.close();
    }
  });
});
