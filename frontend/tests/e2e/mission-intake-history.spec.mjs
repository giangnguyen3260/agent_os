import {test, expect} from '@playwright/test';
import {randomUUID, createHash} from 'node:crypto';
import {mkdirSync, appendFileSync, readFileSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

// Main owns the disposable prefix540 fixture. No seeded or fabricated receipts.
const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const hook = (p, state) => p.getByTestId(`mission-intake-history-${state}`);
const rows = p => hook(p, 'event');
const focus = p => p.evaluate(() => window.dispatchEvent(new Event('focus')));
function scope(info, m = 1) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('54000000', n),
    team = uuid('54010000', n * 100 + 1),
    mission = uuid('54020000', n * 100 + m);
  const path = `/teams/${team}/missions/${mission}`;
  const intake = `/workspace/${org}${path}/intake`;
  const source = `/api/v1/organizations/${org}${path}/task-intake`;
  return {
    org,
    team,
    mission,
    source,
    api: `${source}/history`,
    intake,
    url: `${intake}/history`,
    control: `/workspace/${org}${path}/workspace`,
  };
}
async function authenticate(p, info, kind = 'admin') {
  expect(process.env.E2E_PASSWORD).toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`intake-history-${info.project.name}-${kind}@example.test`);
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
async function get(p, path) {
  const r = await p.request.get(path);
  expect(r.status()).toBe(200);
  return (await r.json()).data;
}
async function open(p, info, m = 1) {
  await authenticate(p, info);
  const s = scope(info, m);
  const r = await response(p, s, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  return {s, data: (await r.json()).data};
}
function validate(s, d, page) {
  expect(Object.keys(d).sort()).toEqual(
    'organizationId teamId missionId teamName missionTitle page pageSize total items'
      .split(' ')
      .sort(),
  );
  expect(d).toMatchObject({
    organizationId: s.org,
    teamId: s.team,
    missionId: s.mission,
    page,
    pageSize: 20,
  });
  expect(Number.isSafeInteger(d.total) && d.total >= 0).toBe(true);
  expect(d.items).toHaveLength(Math.min(20, Math.max(0, d.total - page * 20)));
  for (const name of [d.teamName, d.missionTitle])
    expect(typeof name === 'string' && !!name.trim()).toBe(true);
  const instant = value => {
    expect(value).toMatch(
      /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/,
    );
    expect(Number.isFinite(Date.parse(value))).toBe(true);
    return (
      value.slice(0, 19) +
      '.' +
      value.slice(19, -1).replace('.', '').padEnd(9, '0')
    );
  };
  for (const [i, item] of d.items.entries()) {
    expect(Object.keys(item).sort()).toEqual(
      'eventId versionBefore versionAfter pausedBefore pausedAfter committedAt actorName'
        .split(' ')
        .sort(),
    );
    expect(item.eventId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
    for (const v of [item.versionBefore, item.versionAfter]) {
      expect(v).toMatch(/^(0|[1-9][0-9]*)$/);
      expect(BigInt(v)).toBeLessThanOrEqual(9223372036854775807n);
    }
    expect(BigInt(item.versionAfter)).toBe(BigInt(item.versionBefore) + 1n);
    expect(typeof item.pausedBefore).toBe('boolean');
    expect(item.pausedAfter).toBe(!item.pausedBefore);
    expect(
      item.actorName === null ||
        (typeof item.actorName === 'string' &&
          !!item.actorName.trim() &&
          [...item.actorName].length <= 255),
    ).toBe(true);
    const current = instant(item.committedAt);
    if (i) {
      const previous = d.items[i - 1],
        prior = instant(previous.committedAt);
      expect(
        prior > current ||
          (prior === current && previous.eventId > item.eventId),
      ).toBe(true);
    }
  }
  expect(new Set(d.items.map(x => x.eventId)).size).toBe(d.items.length);
  // Deliberately no adjacency assertion: Task Create may produce version gaps.
}
async function render(p, s, d) {
  validate(s, d, d.page);
  await expect(rows(p)).toHaveCount(d.items.length);
  const main = p.getByTestId('mission-intake-history');
  await expect(main).toContainText(d.teamName);
  await expect(main).toContainText(d.missionTitle);
  await expect(main).toContainText('tên hiển thị hiện tại');
  await expect(main).toContainText('Mỗi trang là một lần đọc độc lập');
  for (const [i, item] of d.items.entries()) {
    const row = rows(p).nth(i);
    await expect(
      row.getByRole('heading', {
        name: `Phiên bản ${item.versionBefore} → ${item.versionAfter}`,
        exact: true,
      }),
    ).toBeVisible();
    await expect(row.locator('time')).toHaveAttribute(
      'datetime',
      item.committedAt,
    );
    await expect(row).toContainText(
      item.actorName ?? 'Không còn thông tin người thay đổi',
    );
    for (const [index, paused] of [
      item.pausedBefore,
      item.pausedAfter,
    ].entries())
      await expect(row.locator('.comparison > div').nth(index)).toContainText(
        paused ? 'Tạm dừng nhận task' : 'Đang nhận task',
      );
  }
  await expect(
    p.getByRole('link', {name: 'Mission Control', exact: true}),
  ).toHaveAttribute('href', s.control);
  await expect(
    p.getByRole('link', {name: 'Quản lý nhận task', exact: true}),
  ).toHaveAttribute('href', s.intake);
}
async function erased(p, secret) {
  await expect(rows(p)).toHaveCount(0);
  await expect(p.getByTestId('mission-intake-history')).not.toContainText(
    secret,
  );
}
async function capture(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `intake-history-${info.project.name}-${state}.png`,
    path = join(dir, file);
  await p.screenshot({path, fullPage: true});
  appendFileSync(
    join(dir, 'intake-history-manifest.jsonl'),
    JSON.stringify({
      file,
      project: info.project.name,
      viewport: p.viewportSize(),
      state,
      test: info.title,
      path: new URL(p.url()).pathname + new URL(p.url()).search,
      capturetimestamp: new Date().toISOString(),
      imageSHA256: createHash('sha256')
        .update(readFileSync(path))
        .digest('hex'),
    }) + '\n',
  );
}
async function hold(p, path, method, status) {
  let arrive, fail, release, complete, rejectDone, original;
  const seen = new Promise((yes, no) => {
    arrive = yes;
    fail = no;
  });
  const done = new Promise((yes, no) => {
    complete = yes;
    rejectDone = no;
  });
  void seen.catch(() => {});
  void done.catch(() => {});
  const gate = new Promise(yes => {
    release = yes;
  });
  const match = url => url.pathname === path;
  const handler = async route => {
    if (original || route.request().method() !== method)
      return route.continue();
    original = route.request();
    try {
      const actual = await route.fetch();
      expect(actual.status()).toBe(status);
      arrive();
      await gate;
      try {
        await route.fulfill({response: actual});
      } catch (error) {
        if (original.failure() === null) throw error;
      }
      complete();
    } catch (error) {
      fail(error);
      rejectDone(error);
    }
  };
  await p.route(match, handler);
  let drained;
  return {
    seen,
    release() {
      if (drained) return drained;
      drained = (async () => {
        release();
        if (original) {
          await done;
          const r = await original.response();
          if (r) {
            const error = await r.finished();
            if (error && original.failure() === null) throw error;
          } else expect(original.failure()).not.toBeNull();
        }
        await p.unroute(match, handler);
        await p.evaluate(
          () =>
            new Promise(resolve =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
        );
      })();
      return drained;
    },
  };
}

test.describe('mission intake history independent QC', () => {
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

  test('fresh zero history then 21 accepted real receipts, paging URL back reload and intake link', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const s = scope(info);
    const initial = await get(p, s.api);
    validate(s, initial, 0);
    expect(initial).toMatchObject({total: 0, items: []});
    const receipts = [];
    for (let i = 0; i < 21; i++) {
      const source = await get(p, s.source);
      expect(source.intakeVersion).toBe(String(7 + i));
      expect(source.intakePaused).toBe(i % 2 === 1);
      expect(source.canChange).toBe(true);
      const csrf = await get(p, '/api/v1/auth/csrf');
      const r = await p.request.post(s.source, {
        headers: {
          [csrf.headerName]: csrf.token,
          'Idempotency-Key': randomUUID(),
        },
        data: {
          expectedVersion: source.intakeVersion,
          expectedFingerprint: source.fingerprint,
          paused: !source.intakePaused,
        },
      });
      expect(r.status()).toBe(201);
      const receipt = (await r.json()).data;
      expect(Object.keys(receipt).sort()).toEqual(
        'organizationId teamId missionId eventId versionBefore versionAfter pausedBefore pausedAfter committedAt'
          .split(' ')
          .sort(),
      );
      expect(receipt).toMatchObject({
        organizationId: s.org,
        teamId: s.team,
        missionId: s.mission,
        versionBefore: source.intakeVersion,
        versionAfter: String(8 + i),
        pausedBefore: source.intakePaused,
        pausedAfter: !source.intakePaused,
      });
      receipts.push(receipt);
      expect(await get(p, s.source)).toMatchObject({
        intakeVersion: receipt.versionAfter,
        intakePaused: receipt.pausedAfter,
      });
    }
    await p.goto(s.intake);
    const r = await response(p, s, () =>
      p.getByRole('link', {name: 'Lịch sử nhận task', exact: true}).click(),
    );
    expect(r.status()).toBe(200);
    const first = (await r.json()).data;
    expect(first.total).toBe(21);
    await render(p, s, first);
    await expect(button(p, 'Trang trước')).toBeDisabled();
    await capture(p, info, 'populated');
    const secondResponse = await response(
      p,
      s,
      () => button(p, 'Trang sau').click(),
      1,
    );
    expect(secondResponse.status()).toBe(200);
    const second = (await secondResponse.json()).data;
    expect(second).toMatchObject({total: 21, page: 1});
    await render(p, s, second);
    await expect(p).toHaveURL(`${s.url}?page=1`);
    await expect(button(p, 'Trang sau')).toBeDisabled();
    await capture(p, info, 'second-page');
    const all = [...first.items, ...second.items];
    const ordered = [...receipts].sort((a, b) => {
      const key = v =>
        v.slice(0, 19) + '.' + v.slice(19, -1).replace('.', '').padEnd(9, '0');
      const left = key(a.committedAt),
        right = key(b.committedAt);
      return left === right
        ? a.eventId > b.eventId
          ? -1
          : 1
        : left > right
          ? -1
          : 1;
    });
    expect(all.map(x => x.eventId)).toEqual(ordered.map(x => x.eventId));

    expect(new Set(all.map(x => x.eventId)).size).toBe(21);
    validate(s, {...first, pageSize: 20, items: first.items}, 0);
    for (const receipt of receipts) {
      const {organizationId, teamId, missionId, ...provenance} = receipt;
      expect(all.find(x => x.eventId === receipt.eventId)).toEqual({
        ...provenance,
        actorName: `Intake history ${info.project.name} admin`,
      });
    }
    const dialogs = [];
    p.on('dialog', async d => {
      dialogs.push(d.type());
      await d.dismiss();
    });
    await response(p, s, () => p.evaluate(() => location.reload()), 1);
    await render(p, s, second);
    expect(dialogs).toEqual([]);
    await response(p, s, () => button(p, 'Trang trước').click());
    await render(p, s, first);
    await response(p, s, () => p.goBack(), 1);
    await render(p, s, second);
    const session = p.waitForResponse(
      r =>
        new URL(r.url()).pathname === '/api/v1/auth/session' &&
        r.request().method() === 'GET',
    );
    const role = p.waitForResponse(
      r =>
        new URL(r.url()).pathname === `/api/v1/auth/workspaces/${s.org}` &&
        r.request().method() === 'GET',
    );
    await response(p, s, () => button(p, 'Tải lại lịch sử').click(), 1);
    expect((await session).status()).toBe(200);
    expect((await role).status()).toBe(200);
    expect((await (await role).json()).data.role).toBe('ADMIN');

    await render(p, s, second);
  });

  test('actual empty and out of range never invent baseline', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info, 2);
    expect(data).toMatchObject({total: 0, items: []});
    await render(p, s, data);
    await expect(hook(p, 'empty')).toHaveText(
      'Chưa có thay đổi nhận task nào được ghi nhận.',
    );
    await capture(p, info, 'empty');
    const populated = scope(info);
    for (const page of [2, 100000])
      expect(await get(p, `${populated.api}?page=${page}`)).toMatchObject({
        page,
        total: 21,
        items: [],
      });
  });

  test('malformed actual backend DTO clears private data and GET retry recovers', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    await render(p, s, data);
    for (const mode of [
      'json',
      'scope',
      'private-key',
      'duplicate',
      'order',
      'count',
      'version',
      'flag',
      'actor',
      'time',
    ]) {
      const match = url => url.pathname === s.api;
      await p.route(match, async route => {
        expect(route.request().method()).toBe('GET');
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        const body = await actual.json();
        validate(s, body.data, 0);
        if (mode === 'scope') body.data.missionId = scope(info, 2).mission;
        if (mode === 'private-key')
          body.data.items[0].actorId = 'PRIVATE_HISTORY_PAYLOAD';
        if (mode === 'duplicate') body.data.items[1] = body.data.items[0];
        if (mode === 'order') body.data.items.reverse();
        if (mode === 'count') body.data.total = -1;
        if (mode === 'version')
          body.data.items[0].versionAfter = body.data.items[0].versionBefore;
        if (mode === 'flag')
          body.data.items[0].pausedAfter = body.data.items[0].pausedBefore;
        if (mode === 'actor') body.data.items[0].actorName = '\u0001';
        if (mode === 'time') body.data.items[0].committedAt = 'invalid';
        await route.fulfill({
          response: actual,
          body: mode === 'json' ? '{malformed' : JSON.stringify(body),
        });
      });
      await response(p, s, () => button(p, 'Tải lại lịch sử').click());
      await expect(hook(p, 'error')).toBeVisible();
      await erased(p, data.missionTitle);
      await expect(p.locator('main')).not.toContainText(
        'PRIVATE_HISTORY_PAYLOAD',
      );
      if (mode === 'json') await capture(p, info, 'error');
      await p.unroute(match);
      await response(p, s, () => button(p, 'Tải lại lịch sử').click());
      await render(p, s, data);
    }
  });

  for (const kind of ['member', 'other'])
    test(`${kind} real denial and no intake history link`, async ({
      page: p,
    }, info) => {
      await authenticate(p, info, kind);
      const s = scope(info);
      const denial = await p.request.get(s.api);
      expect(denial.status()).toBe(403);
      expect(await denial.text()).not.toContain('versionBefore');
      await p.goto(s.url);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, 'Intake history Mission 1');
      if (kind === 'member') await capture(p, info, 'denied');
      await p.goto(s.intake);
      await expect(
        p.getByRole('link', {name: 'Lịch sử nhận task', exact: true}),
      ).toHaveCount(0);
    });

  for (const kind of ['admin2', 'member'])
    test(`fresh shared-session ${kind} fences held actual GET and drains`, async ({
      page: p,
    }, info) => {
      const {s, data} = await open(p, info);
      await render(p, s, data);
      const held = await hold(p, s.api, 'GET', 200);
      const sibling = await p.context().newPage();
      try {
        await focus(p);
        await held.seen;
        await erased(p, data.missionTitle);
        const csrf = await get(sibling, '/api/v1/auth/csrf');
        const logout = await sibling.request.post('/api/v1/auth/logout', {
          headers: {[csrf.headerName]: csrf.token},
        });
        expect([200, 204]).toContain(logout.status());
        await authenticate(sibling, info, kind);
        expect((await sibling.request.get(s.api)).status()).toBe(
          kind === 'admin2' ? 200 : 403,
        );
        await focus(p);
        await expect(hook(p, 'denied')).toBeVisible();
        await erased(p, data.missionTitle);
        await held.release();
        await expect(hook(p, 'denied')).toBeVisible();
        await erased(p, data.missionTitle);
      } finally {
        await held.release();
        await sibling.close();
      }
    });

  test('route reuse discards held old scope GET after actual empty scope load', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    const held = await hold(p, s.api, 'GET', 200);
    try {
      await focus(p);
      await held.seen;
      const other = scope(info, 2);
      const r = await response(p, other, () => pushSpaHistory(p, other.url));
      expect(r.status()).toBe(200);
      await render(p, other, (await r.json()).data);
      await expect(hook(p, 'empty')).toBeVisible();
      await held.release();
      await expect(p).toHaveURL(other.url);
      await expect(hook(p, 'empty')).toBeVisible();
      await erased(p, data.missionTitle);
    } finally {
      await held.release();
    }
  });

  test('native compact menu keyboard close returns focus', async ({
    page: p,
  }, info) => {
    const {s, data} = await open(p, info);
    await render(p, s, data);
    if (p.viewportSize().width < 1024) {
      const menu = button(p, 'Menu');
      await expect(menu).toBeVisible();
      await menu.focus();
      await p.keyboard.press('Enter');
      const dialog = p.getByRole('dialog', {name: 'Workspace navigation'});
      await expect(dialog).toBeVisible();
      await expect(menu).toHaveAttribute('aria-expanded', 'true');
      await p.keyboard.press('Escape');
      await expect(dialog).not.toBeVisible();
      await expect(menu).toBeFocused();
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
    } else
      await expect(
        p.getByRole('navigation', {name: 'Workspace navigation'}),
      ).toBeVisible();
  });
});
