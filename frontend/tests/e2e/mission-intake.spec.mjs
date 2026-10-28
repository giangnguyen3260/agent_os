import {test, expect} from '@playwright/test';
import {randomUUID, createHash} from 'node:crypto';
import {mkdirSync, appendFileSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

// Coordinator-owned prefix530 fixture. Never substitute mocked business success.
const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const flag = p =>
  p.getByRole('checkbox', {name: 'Tạm dừng nhận task', exact: true});
const ack = p =>
  p.getByRole('checkbox', {
    name: 'Tôi đã xem lại thay đổi nhận task.',
    exact: true,
  });
const hook = (p, state) => p.getByTestId(`intake-${state}`);
function scope(info, m = 1) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported fixture project: ${info.project.name}`);
  const org = uuid('53000000', n),
    team = uuid('53010000', n * 100 + (m === 5 ? 2 : 1));
  const mission = uuid('53020000', n * 100 + m);
  const path = `/teams/${team}/missions/${mission}`;
  return {
    org,
    team,
    mission,
    api: `/api/v1/organizations/${org}${path}/task-intake`,
    url: `/workspace/${org}${path}/intake`,
    control: `/workspace/${org}${path}/workspace`,
  };
}
async function response(p, path, action, method = 'GET') {
  const pending = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === method,
  );
  await action();
  return pending;
}
async function authenticate(p, info, kind = 'admin') {
  expect(
    process.env.E2E_PASSWORD,
    'Real fixture E2E_PASSWORD is required',
  ).toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`mission-intake-${info.project.name}-${kind}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function read(p, s) {
  const r = await p.request.get(s.api);
  expect(r.status()).toBe(200);
  const data = (await r.json()).data;
  expect(Object.keys(data).sort()).toEqual(
    [
      'organizationId',
      'teamId',
      'missionId',
      'checkedAt',
      'teamName',
      'missionTitle',
      'teamStatus',
      'missionStatus',
      'intakePaused',
      'intakeVersion',
      'fingerprint',
      'canChange',
      'blockers',
    ].sort(),
  );
  expect(data).toMatchObject({
    organizationId: s.org,
    teamId: s.team,
    missionId: s.mission,
  });
  expect(data.intakeVersion).toMatch(/^(0|[1-9][0-9]*)$/);
  expect(BigInt(data.intakeVersion)).toBeLessThanOrEqual(9223372036854775807n);
  expect(data.fingerprint).toMatch(/^[0-9a-f]{64}$/);
  return data;
}
async function open(p, info, m = 1) {
  await authenticate(p, info);
  const s = scope(info, m);
  const r = await response(p, s.api, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  s.source = (await r.json()).data;
  await expect(p.getByTestId('mission-intake')).toBeVisible();
  return s;
}
async function command(p, s, source, paused, key = randomUUID()) {
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  return p.request.post(s.api, {
    headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': key},
    data: {
      expectedVersion: source.intakeVersion,
      expectedFingerprint: source.fingerprint,
      paused,
    },
  });
}
async function review(p) {
  await button(p, 'Xem lại thay đổi').click();
  await expect(hook(p, 'review')).toBeVisible();
  await expect(ack(p)).not.toBeChecked();
  await expect(button(p, 'Lưu thay đổi')).toBeDisabled();
}
function observe(p, s) {
  const posts = [];
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
      posts.push({key: r.headers()['idempotency-key'], body: r.postData()});
  });
  return posts;
}
async function capture(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `mission-intake-${info.project.name}-${state}.png`,
    path = join(dir, file);
  await p.screenshot({path, fullPage: true});
  appendFileSync(
    join(dir, 'mission-intake-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      viewport: p.viewportSize(),
      state,
      file,
      test: info.title,
      url: new URL(p.url()).pathname,
      capturedAt: new Date().toISOString(),
      sha256: createHash('sha256')
        .update((await import('node:fs')).readFileSync(path))
        .digest('hex'),
    }) + '\n',
  );
}
async function fault(p, s, mode) {
  let receipt;
  const attempts = [];
  await p.route(
    url => url.pathname === s.api,
    async route => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      const actual = await route.fetch();
      expect(actual.status()).toBe(attempts.length === 1 ? 201 : 200);
      const data = (await actual.json()).data;
      if (receipt) expect(data).toEqual(receipt);
      receipt = data;
      const persisted = await read(p, s);
      expect(persisted.intakePaused).toBe(receipt.pausedAfter);
      expect(persisted.intakeVersion).toBe(receipt.versionAfter);
      if (attempts.length > 1) return route.fulfill({response: actual});
      if (mode === 'lost') return route.abort('failed');
      return route.fulfill({response: actual, body: '{malformed'});
    },
  );
  return {attempts, receipt: () => receipt};
}
async function erased(p, source) {
  await expect(hook(p, 'review')).toHaveCount(0);
  await expect(hook(p, 'saved')).toHaveCount(0);
  await expect(flag(p)).toHaveCount(0);
  await expect(p.getByTestId('mission-intake')).not.toContainText(
    source.missionTitle,
  );
}
// Hold actual backend data until the authority/scope has changed. All release
// failures must have an observed browser request cancellation, not a text match.
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
  return {
    seen,
    async release() {
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
    },
  };
}
async function switchActor(p, info, kind) {
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  const r = await p.request.post('/api/v1/auth/login', {
    headers: {[csrf.headerName]: csrf.token},
    form: {
      username: `mission-intake-${info.project.name}-${kind}@example.test`,
      password: process.env.E2E_PASSWORD,
    },
  });
  expect(r.status()).toBe(200);
  await p.evaluate(() => window.dispatchEvent(new Event('focus')));
}

test.describe('mission intake independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  test.beforeEach(async ({}, info) => {
    expect(info.config.workers).toBe(1);
  });

  test('edit review cancel acknowledgment save and persisted reload', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      before = await read(p, s),
      posts = observe(p, s);
    await expect(flag(p)).toBeChecked({checked: before.intakePaused});
    await expect(button(p, 'Xem lại thay đổi')).toBeDisabled();
    await capture(p, info, 'edit');
    await flag(p).setChecked(!before.intakePaused);
    await review(p);
    await expect(p.getByTestId('mission-intake')).toContainText(
      before.missionTitle,
    );
    await expect(hook(p, 'review')).toContainText(
      `Trước: ${before.intakePaused ? 'Tạm dừng' : 'Đang nhận'}`,
    );
    await expect(hook(p, 'review')).toContainText(
      `Sau: ${before.intakePaused ? 'Đang nhận' : 'Tạm dừng'}`,
    );
    await expect(p.getByTestId('mission-intake')).toContainText(/draft/i);
    await capture(p, info, 'review');
    await button(p, 'Quay lại chỉnh sửa').click();
    expect(posts).toHaveLength(0);
    expect(await read(p, s)).toMatchObject({
      intakeVersion: before.intakeVersion,
      intakePaused: before.intakePaused,
    });
    await review(p);
    await ack(p).check();
    const r = await response(
      p,
      s.api,
      () => button(p, 'Lưu thay đổi').click(),
      'POST',
    );
    expect(r.status()).toBe(201);
    const receipt = (await r.json()).data;
    expect(Object.keys(receipt).sort()).toEqual(
      [
        'organizationId',
        'teamId',
        'missionId',
        'eventId',
        'versionBefore',
        'versionAfter',
        'pausedBefore',
        'pausedAfter',
        'committedAt',
      ].sort(),
    );
    expect(receipt).toMatchObject({
      organizationId: s.org,
      teamId: s.team,
      missionId: s.mission,
      versionBefore: before.intakeVersion,
      versionAfter: String(BigInt(before.intakeVersion) + 1n),
      pausedBefore: before.intakePaused,
      pausedAfter: !before.intakePaused,
    });
    await expect(hook(p, 'saved')).toBeVisible();
    await capture(p, info, 'saved');
    expect(posts).toHaveLength(1);
    await p.reload();
    await expect(flag(p)).toBeChecked({checked: !before.intakePaused});
    expect(await read(p, s)).toMatchObject({
      intakeVersion: receipt.versionAfter,
      intakePaused: receipt.pausedAfter,
    });
  });

  for (const [m, blocker] of [
    [3, 'MISSION_NOT_EDITABLE'],
    [4, 'REVISION_EXHAUSTED'],
    [5, 'TEAM_ARCHIVED'],
  ]) {
    test(`blocked fixture ${blocker} and exact int64`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info, m),
        source = await read(p, s),
        posts = observe(p, s);
      expect(source.canChange).toBe(false);
      expect(source.blockers).toContain(blocker);
      if (m === 4) expect(source.intakeVersion).toBe('9223372036854775807');
      await expect(hook(p, 'blocked')).toBeVisible();
      if (m === 4) await capture(p, info, 'blocked');
      expect(posts).toHaveLength(0);
      const r = await command(p, s, source, !source.intakePaused);
      expect(r.status()).toBe(409);
      expect((await r.json()).error.code).toBe('MISSION_INTAKE_BLOCKED');
    });
  }

  test('paused eligible mission can resume; server rejects no-op', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 2),
      source = await read(p, s);
    expect(source.canChange).toBe(true);
    expect(source.blockers).toEqual([]);
    const noop = await command(p, s, source, source.intakePaused);
    expect(noop.status()).toBe(409);
    expect((await noop.json()).error.code).toBe('MISSION_INTAKE_NO_CHANGE');
    await flag(p).setChecked(!source.intakePaused);
    await review(p);
    await ack(p).check();
    const r = await response(
      p,
      s.api,
      () => button(p, 'Lưu thay đổi').click(),
      'POST',
    );
    expect(r.status()).toBe(201);
    await expect(hook(p, 'saved')).toBeVisible();
  });

  test('concurrent real change invalidates review and requires reload', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      source = await read(p, s);
    await flag(p).setChecked(!source.intakePaused);
    await review(p);
    await ack(p).check();
    expect((await command(p, s, source, !source.intakePaused)).status()).toBe(
      201,
    );
    await button(p, 'Lưu thay đổi').click();
    await expect(hook(p, 'error')).toBeVisible();
    await expect(hook(p, 'saved')).toHaveCount(0);
    await capture(p, info, 'conflict');
    p.once('dialog', dialog => dialog.accept());
    await button(p, 'Tải lại dữ liệu').click();
    await expect(flag(p)).toBeChecked({checked: !source.intakePaused});
  });

  test('dirty cancel and native reload guards never write', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await flag(p).setChecked(!s.source.intakePaused);
    const cancel = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('confirm');
      await d.dismiss();
    });
    await p.getByRole('link', {name: 'Hủy', exact: true}).click();
    await cancel;
    await expect(p).toHaveURL(s.url);
    const unload = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('beforeunload');
      await d.dismiss();
    });
    // A dismissed beforeunload deliberately has no completed navigation.
    // Trigger the browser action without waiting for a new document to load.
    await p.evaluate(() => location.reload());
    await unload;
    await expect(p).toHaveURL(s.url);
    await expect(flag(p)).toBeChecked({checked: !s.source.intakePaused});
    p.once('dialog', d => d.accept());
    await p.getByRole('link', {name: 'Hủy', exact: true}).click();
    await expect(p).toHaveURL(s.control);
    expect(posts).toHaveLength(0);
    expect(await read(p, s)).toMatchObject({
      intakeVersion: s.source.intakeVersion,
      intakePaused: s.source.intakePaused,
    });
  });

  test('historical replay survives a later flip and actor receipt lookup is isolated', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      source = await read(p, s),
      key = randomUUID();
    const first = await command(p, s, source, !source.intakePaused, key);
    expect(first.status()).toBe(201);
    const receipt = (await first.json()).data;
    const next = await read(p, s);
    expect((await command(p, s, next, source.intakePaused)).status()).toBe(201);
    const replay = await command(p, s, source, !source.intakePaused, key);
    expect(replay.status()).toBe(200);
    expect((await replay.json()).data).toEqual(receipt);
    const conflict = await command(p, s, source, source.intakePaused, key);
    expect(conflict.status()).toBe(409);
    expect((await conflict.json()).error.code).toBe('IDEMPOTENCY_CONFLICT');
    await switchActor(p, info, 'admin2');
    const own = await p.request.get(`${s.api}/receipts/${key}`);
    expect(own.status()).toBe(200);
    expect((await own.json()).data).toEqual({receipt: null});
    expect((await read(p, s)).intakeVersion).toBe(
      String(BigInt(source.intakeVersion) + 2n),
    );
  });

  for (const mode of ['lost', 'malformed']) {
    test(`${mode} committed response freezes exact key and body for identical manual retry`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info),
        source = await read(p, s),
        f = await fault(p, s, mode);
      await flag(p).setChecked(!source.intakePaused);
      await review(p);
      await ack(p).check();
      await button(p, 'Lưu thay đổi').click();
      await expect(hook(p, 'unknown')).toBeVisible();
      expect(f.attempts).toHaveLength(1);
      if (mode === 'lost') await capture(p, info, 'unknown');
      await button(p, 'Gửi lại đúng yêu cầu này').click();
      await expect(hook(p, 'saved')).toBeVisible();
      expect(f.attempts).toHaveLength(2);
      expect(f.attempts[1]).toEqual(f.attempts[0]);
      expect(JSON.parse(f.attempts[0].body)).toEqual({
        expectedVersion: source.intakeVersion,
        expectedFingerprint: source.fingerprint,
        paused: !source.intakePaused,
      });
      expect(f.attempts[0].key).toMatch(
        /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
      );
      expect((await read(p, s)).intakeVersion).toBe(
        String(BigInt(source.intakeVersion) + 1n),
      );
    });
  }

  test('manual receipt recovery is historical and never posts again', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      source = await read(p, s),
      f = await fault(p, s, 'lost');
    await flag(p).setChecked(!source.intakePaused);
    await review(p);
    await ack(p).check();
    await button(p, 'Lưu thay đổi').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    await button(p, 'Kiểm tra biên nhận').click();
    await expect(hook(p, 'saved')).toBeVisible();
    expect(f.attempts).toHaveLength(1);
    await capture(p, info, 'recovered');
    const r = await p.request.get(`${s.api}/receipts/${f.attempts[0].key}`);
    expect(r.status()).toBe(200);
    expect((await r.json()).data).toEqual({receipt: f.receipt()});
  });

  for (const actor of ['member', 'other']) {
    test(`${actor} denied without private mission context`, async ({
      page: p,
    }, info) => {
      const s = scope(info);
      await authenticate(p, info, actor);
      await p.goto(s.url);
      const r = await p.request.get(s.api);
      expect(r.status()).toBe(403);
      await expect(hook(p, 'denied')).toBeVisible();
      await expect(flag(p)).toHaveCount(0);
      await expect(hook(p, 'review')).toHaveCount(0);
      if (actor === 'member') await capture(p, info, 'denied');
    });
  }

  test('fresh actor fence erases review before save, including a different ADMIN', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await flag(p).setChecked(!s.source.intakePaused);
    await review(p);
    await ack(p).check();
    await switchActor(p, info, 'admin2');
    await erased(p, s.source);
    expect(posts).toHaveLength(0);
  });

  test('unknown auth loss erases frozen body; same actor can explicitly recover only by receipt', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      f = await fault(p, s, 'lost');
    await flag(p).setChecked(!s.source.intakePaused);
    await review(p);
    await ack(p).check();
    await button(p, 'Lưu thay đổi').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    await switchActor(p, info, 'member');
    await expect(hook(p, 'denied')).toBeVisible();
    await erased(p, s.source);
    await switchActor(p, info, 'admin');
    await expect(button(p, 'Kiểm tra biên nhận')).toBeVisible();
    await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
    await button(p, 'Kiểm tra biên nhận').click();
    await expect(hook(p, 'saved')).toBeVisible();
    expect(f.attempts).toHaveLength(1);
  });

  test('late real GET cannot repopulate context after actor switch', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      h = await hold(p, s.api, 'GET', 200);
    try {
      await button(p, 'Tải lại dữ liệu').click();
      await h.seen;
      await switchActor(p, info, 'member');
      await expect(hook(p, 'denied')).toBeVisible();
    } finally {
      await h.release();
    }
    await erased(p, s.source);
  });

  test('late committed POST cannot expose saved receipt after route reuse', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      target = scope(info, 3);
    await flag(p).setChecked(!s.source.intakePaused);
    await review(p);
    await ack(p).check();
    const h = await hold(p, s.api, 'POST', 201);
    try {
      await button(p, 'Lưu thay đổi').click();
      await h.seen;
      p.on('dialog', dialog => dialog.accept());
      await pushSpaHistory(p, target.url);
      await expect(hook(p, 'blocked')).toBeVisible();
    } finally {
      await h.release();
    }
    await expect(hook(p, 'saved')).toHaveCount(0);
    await expect(p.getByTestId('mission-intake')).not.toContainText(
      s.source.missionTitle,
    );
  });
});
