import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync, readFileSync} from 'node:fs';
import {createHash, randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

const uuid = (p, n) => `${p}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const field = (p, name) => p.getByLabel(name, {exact: true});
const button = (p, name) => p.getByRole('button', {name, exact: true});
const hook = (p, state) => p.getByTestId(`owner-${state}`);
const ack = p => field(p, 'Tôi đã xem lại thay đổi phân công.');
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
  const org = uuid('55000000', n),
    team = uuid('55010000', n * 100 + (t === 8 ? 2 : 1));
  const mission = uuid('55020000', n * 100 + ({6: 2, 7: 3, 8: 4}[t] || 1));
  const task = uuid('55030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  return {
    n,
    org,
    team,
    mission,
    task,
    api: `/api/v1/organizations/${org}${path}/draft-owner`,
    url: `/workspace/${org}${path}/owner`,
    detail: `/workspace/${org}${path}`,
  };
}
async function response(p, path, action, method = 'GET') {
  const result = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === method,
  );
  await action();
  return result;
}
async function authenticate(p, info, kind = 'admin') {
  await p.goto('/login');
  await field(p, 'Email').fill(
    `task-owner-${info.project.name}-${kind}@example.test`,
  );
  await field(p, 'Mật khẩu').fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, info, t = 1) {
  await authenticate(p, info);
  const s = scope(info, t);
  const r = await response(p, s.api, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  s.source = (await r.json()).data;
  return s;
}
function observe(p, s) {
  const posts = [];
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
      posts.push(r);
  });
  return posts;
}
async function shot(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `task-owner-${info.project.name}-${state}.png`;
  const path = join(dir, file);
  await p.screenshot({path, fullPage: true});
  appendFileSync(
    join(dir, 'task-owner-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      viewport: p.viewportSize(),
      state,
      file,
      test: info.title,
      path: new URL(p.url()).pathname,
      capturetimestamp: new Date().toISOString(),
      imageSHA256: createHash('sha256')
        .update(readFileSync(path))
        .digest('hex'),
    }) + '\n',
  );
}
function row(s) {
  return JSON.parse(
    sql(`SELECT row_to_json(t) FROM tasks t WHERE id='${s.task}';`),
  );
}
function invariants(s) {
  return sql(`SELECT row_to_json(t)::jsonb - ARRAY['owner_agent_id','task_owner_revision','updated_at'] FROM tasks t WHERE id='${s.task}';
SELECT row_to_json(d) FROM task_dependencies d WHERE organization_id='${s.org}' ORDER BY task_id,dependency_task_id;
SELECT row_to_json(m) FROM missions m WHERE organization_id='${s.org}' ORDER BY id;
SELECT row_to_json(a) FROM agents a WHERE organization_id='${s.org}' ORDER BY id;`);
}
function membership(s, info, role) {
  sql(
    `UPDATE organization_memberships SET role='${role}' WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-owner-${info.project.name}-admin@example.test');`,
  );
}
const focus = p => p.evaluate(() => window.dispatchEvent(new Event('focus')));
async function erased(p, secret) {
  for (const state of ['current', 'selection', 'review', 'saved', 'chooser'])
    await expect(hook(p, state)).toHaveCount(0);
  await expect(p.locator('main')).not.toContainText(secret);
}
async function choose(p, name) {
  await button(p, 'Chọn Agent').click();
  await expect(
    p.getByRole('dialog', {name: 'Chọn Agent phụ trách', exact: true}),
  ).toBeVisible();
  await field(p, 'Tìm Agent').fill(name);
  await button(p, 'Tìm kiếm').click();
  await button(p, `Chọn ${name}`).click();
  await expect(hook(p, 'selection')).toContainText(name);
}
async function clearOwner(p) {
  await button(p, 'Chọn Agent').click();
  const dialog = p.getByRole('dialog', {
    name: 'Chọn Agent phụ trách',
    exact: true,
  });
  await expect(dialog).toBeVisible();
  await dialog
    .getByRole('button', {name: 'Không gán Agent', exact: true})
    .click();
  await expect(dialog).not.toBeVisible();
  await expect(hook(p, 'selection')).toContainText('Không gán Agent');
}
async function savedFocus(p, s, receipt) {
  const path = `${s.api}/receipts/`;
  const received = p.waitForResponse(r =>
    new URL(r.url()).pathname.startsWith(path),
  );
  await focus(p);
  expect((await (await received).json()).data).toEqual({receipt});
  await expect(hook(p, 'saved')).toContainText(String(receipt.revision));
  await expect(hook(p, 'loading')).toHaveCount(0);
  await expect(hook(p, 'error')).toHaveCount(0);
}
async function different(p, s) {
  const name =
    s.source.owner.agent?.name === 'Task owner Agent 01'
      ? 'Task owner Agent 02'
      : 'Task owner Agent 01';
  await choose(p, name);
  return name;
}
async function review(p) {
  await button(p, 'Xem lại phân công').click();
  await expect(hook(p, 'review')).toBeVisible();
  await expect(ack(p)).not.toBeChecked();
  await expect(button(p, 'Lưu phân công')).toBeDisabled();
  await ack(p).check();
}
async function save(p, s) {
  const r = await response(
    p,
    s.api,
    () => button(p, 'Lưu phân công').click(),
    'POST',
  );
  expect(r.status()).toBe(201);
  const receipt = (await r.json()).data;
  await expect(hook(p, 'saved')).toBeVisible();
  expect(row(s).task_owner_revision).toBe(receipt.revision);
  return receipt;
}
async function competing(p, s) {
  const source = (await (await p.request.get(s.api)).json()).data;
  const list = (
    await (await p.request.get(`${s.api}/eligible-owners?page=0&query=`)).json()
  ).data;
  const candidate = list.items.find(a => a.id !== source.owner.agent?.id);
  expect(candidate).toBeTruthy();
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  const r = await p.request.post(s.api, {
    headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID()},
    data: {
      expectedRevision: source.revision,
      expectedFingerprint: source.fingerprint,
      ownerAgentId: candidate.id,
      ownerFingerprint: candidate.fingerprint,
    },
  });
  expect(r.status()).toBe(201);
  return (await r.json()).data;
}
async function fault(p, s, mode) {
  const attempts = [];
  let committed;
  await p.route(`**${s.api}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    attempts.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postData(),
    });
    const actual = await route.fetch();
    expect(actual.status()).toBe(attempts.length === 1 ? 201 : 200);
    const receipt = (await actual.json()).data;
    if (committed) expect(receipt).toEqual(committed);
    committed = receipt;
    expect(row(s).task_owner_revision).toBe(receipt.revision);
    if (attempts.length > 1) return route.fulfill({response: actual});
    if (mode === 'lost') return route.abort('failed');
    return route.fulfill({response: actual, body: '{malformed'});
  });
  return {attempts, receipt: () => committed};
}
// Hold one original browser request after a real successful backend response.
// Releasing waits for actual response/body completion (or observed cancellation),
// then drains rendering before any late-response privacy assertion.
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

test.describe('task owner independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  test.beforeEach(async ({}, info) => {
    expect(info.config.workers).toBe(1);
    expect(info.project.retries).toBe(0);
    expect(
      process.env.E2E_PASSWORD,
      'real fixture password is required',
    ).toBeTruthy();
  });

  test('real Task Detail link assignment change clear and reload preserve protected data', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      before = row(s),
      stable = invariants(s);
    await expect(p.getByTestId('task-owner')).toBeVisible();
    await expect(hook(p, 'current')).toBeVisible();
    await expect(hook(p, 'selection')).toHaveCount(0);
    await shot(p, info, 'edit');
    await p.goto(s.detail);
    await p.getByRole('link', {name: 'Phân công Agent', exact: true}).click();
    await expect(p).toHaveURL(s.url);
    await expect(hook(p, 'current')).toBeVisible();
    const name = await different(p, s);
    await review(p);
    await expect(hook(p, 'review')).toContainText(name);
    await shot(p, info, 'review');
    const receipt = await save(p, s);
    expect(receipt.before).toEqual(s.source.owner);
    expect(receipt.after.agent.name).toBe(name);
    expect(receipt.revision).toBe(before.task_owner_revision + 1);
    expect(invariants(s)).toBe(stable);
    await savedFocus(p, s, receipt);
    const unexpectedDialogs = [];
    p.on('dialog', async d => {
      unexpectedDialogs.push(d.type());
      await d.dismiss();
    });
    await shot(p, info, 'saved');
    await p.getByRole('link', {name: 'Chi tiết task', exact: true}).click();
    await expect(p).toHaveURL(s.detail);
    await expect(p.locator('main')).toContainText(name);
    await p.reload();
    await expect(p.locator('main')).toContainText(name);
    await p.getByRole('link', {name: 'Phân công Agent', exact: true}).click();
    await expect(hook(p, 'current')).toContainText(name);
    await choose(
      p,
      name === 'Task owner Agent 01'
        ? 'Task owner Agent 02'
        : 'Task owner Agent 01',
    );
    await review(p);
    await save(p, s);
    await button(p, 'Tải lại dữ liệu').click();
    await expect(hook(p, 'current')).toContainText('Task owner Agent');
    await clearOwner(p);
    await review(p);
    expect((await save(p, s)).after).toEqual({
      state: 'UNASSIGNED',
      agent: null,
    });
    expect(row(s).owner_agent_id).toBeNull();
    expect(invariants(s)).toBe(stable);
    expect(unexpectedDialogs).toEqual([]);
  });

  test('native chooser pages over twenty safe owners searches literally and restores focus on Escape', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await button(p, 'Chọn Agent').click();
    const dialog = p.getByRole('dialog', {
      name: 'Chọn Agent phụ trách',
      exact: true,
    });
    await expect(dialog).toBeVisible();
    await expect(hook(p, 'chooser')).toBeVisible();
    await expect(
      dialog.getByRole('button', {name: /^Chọn Task owner Agent /}),
    ).toHaveCount(20);
    await expect(dialog).not.toContainText('PRIVATE_');
    await expect(dialog).not.toContainText('Task owner Ineligible');
    await shot(p, info, 'chooser');
    await button(p, 'Trang sau').click();
    await expect(button(p, 'Chọn Task owner Agent 25')).toBeVisible();
    await expect(
      dialog.getByRole('button', {name: /^Chọn Task owner Agent /}),
    ).toHaveCount(5);
    await button(p, 'Trang trước').click();
    await expect(button(p, 'Chọn Task owner Agent 01')).toBeVisible();
    for (const query of ['%', '_', "'", 'PRIVATE_']) {
      await field(p, 'Tìm Agent').fill(query);
      const r = await response(p, `${s.api}/eligible-owners`, () =>
        button(p, 'Tìm kiếm').click(),
      );
      const data = (await r.json()).data;
      expect(data.query).toBe(query);
      expect(data.total).toBe(0);
      expect(data.items).toEqual([]);
    }
    await field(p, 'Tìm Agent').fill('  agent 25  ');
    const r = await response(p, `${s.api}/eligible-owners`, () =>
      button(p, 'Tìm kiếm').click(),
    );
    expect((await r.json()).data.items.map(a => a.name)).toEqual([
      'Task owner Agent 25',
    ]);
    await p.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(button(p, 'Chọn Agent')).toBeFocused();
    await expect(hook(p, 'selection')).toHaveCount(0);
    const workspaceNavigation = p.getByRole('navigation', {
      name: 'Workspace navigation',
      exact: true,
    });
    if (p.viewportSize().width < 1024) {
      await expect(workspaceNavigation).toBeHidden();
      await button(p, 'Menu').click();
      const menu = p.getByRole('dialog', {
        name: 'Workspace navigation',
        exact: true,
      });
      await expect(menu).toBeVisible();
      await expect(
        menu.getByRole('link', {name: 'Change workspace', exact: true}),
      ).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(menu).not.toBeVisible();
      await expect(button(p, 'Menu')).toBeFocused();
    } else {
      await expect(button(p, 'Menu')).toBeHidden();
      await expect(workspaceNavigation).toBeVisible();
      for (const name of [
        'Overview',
        'Organization map',
        'Teams',
        'Agents',
        'Missions',
        'Trace',
      ])
        await expect(
          workspaceNavigation.getByRole('link', {name, exact: true}),
        ).toBeVisible();
    }
    expect(posts).toHaveLength(0);
  });

  test('unavailable raw foreign owner requires deliberate clear without private projection', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 3),
      before = row(s);
    expect(s.source.owner).toEqual({state: 'UNAVAILABLE', agent: null});
    expect(before.owner_agent_id).not.toBeNull();
    await expect(hook(p, 'current')).toContainText(
      'Agent đã được cấu hình nhưng không còn khả dụng trong team này',
    );
    await expect(p.locator('main')).not.toContainText('PRIVATE_FOREIGN_OWNER');
    await expect(p.locator('main')).not.toContainText(uuid('55040000', 5001));
    await expect(button(p, 'Xem lại phân công')).toBeDisabled();
    await shot(p, info, 'unavailable');
    await clearOwner(p);
    await review(p);
    const r = await save(p, s);
    expect(r.before).toEqual({state: 'UNAVAILABLE', agent: null});
    expect(r.after).toEqual({state: 'UNASSIGNED', agent: null});
    expect(row(s).owner_agent_id).toBeNull();
  });

  test('current ineligible owner remains visible but cannot be selected as a new grant', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 4);
    expect(s.source.owner.agent.status).toBe('SUSPENDED');
    await expect(hook(p, 'current')).toContainText('Task owner Ineligible');
    await expect(hook(p, 'current')).toContainText('Tạm ngừng');
    const r = await p.request.get(
      `${s.api}/eligible-owners/${uuid('55040000', s.n * 1000 + 27)}`,
    );
    expect(r.status()).toBe(409);
    for (const id of [s.n * 1000 + 26, 5001])
      expect(
        (
          await p.request.get(
            `${s.api}/eligible-owners/${uuid('55040000', id)}`,
          )
        ).status(),
      ).toBe(404);
    await different(p, s);
    await review(p);
    await save(p, s);
  });

  test('five lifecycle and revision blockers expose safe state with zero POST', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const posts = [];
    p.on('request', r => {
      if (r.method() === 'POST' && r.url().includes('/draft-owner'))
        posts.push(r);
    });
    for (const t of [5, 6, 7, 8, 9]) {
      const s = scope(info, t),
        before = row(s);
      const r = await response(p, s.api, () => p.goto(s.url));
      const c = (await r.json()).data;
      expect(c.canEdit).toBe(false);
      expect(c.blockers.length).toBeGreaterThan(0);
      await expect(hook(p, 'blocked')).toBeVisible();
      await expect(button(p, 'Xem lại phân công')).toBeDisabled();
      await expect(p.locator('main')).not.toContainText(
        /TASK_OWNER_|TASK_NOT_DRAFT|REVISION_EXHAUSTED/,
      );
      if (t === 5) await shot(p, info, 'blocked');
      expect(row(s)).toEqual(before);
    }
    expect(posts).toHaveLength(0);
  });

  test('reserved legacy null input is retained by owner-only persistence', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 10),
      before = row(s),
      stable = invariants(s);
    expect(before.description).toBeNull();
    expect(before.acceptance_criteria).toBeNull();
    expect(before.priority).toBeNull();
    await different(p, s);
    await review(p);
    await save(p, s);
    expect(invariants(s)).toBe(stable);
  });

  test('no-op and review back require renewed acknowledgment and no write', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s),
      before = row(s);
    if (s.source.owner.state === 'UNASSIGNED') await clearOwner(p);
    else await choose(p, s.source.owner.agent.name);
    await expect(button(p, 'Xem lại phân công')).toBeDisabled();
    await different(p, s);
    await review(p);
    await button(p, 'Quay lại chỉnh sửa').click();
    await review(p);
    await focus(p);
    await expect(ack(p)).not.toBeChecked();
    await expect(button(p, 'Lưu phân công')).toBeDisabled();
    expect(posts).toHaveLength(0);
    expect(row(s)).toEqual(before);
  });

  test('native dirty Back and canceled unload retain selection then accepted route discards', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await p.goto(s.detail);
    await p.getByRole('link', {name: 'Phân công Agent', exact: true}).click();
    const name = await different(p, s);
    const back = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('confirm');
      await d.dismiss();
    });
    await p.evaluate(() => history.back());
    await back;
    await expect(p).toHaveURL(s.url);
    await expect(hook(p, 'selection')).toContainText(name);
    const unload = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('beforeunload');
      await d.dismiss();
    });
    await p.evaluate(() => location.reload());
    await unload;
    await expect(hook(p, 'selection')).toContainText(name);
    const other = scope(info, 2);
    p.once('dialog', d => d.accept());
    await response(p, other.api, () => pushSpaHistory(p, other.url));
    await expect(hook(p, 'selection')).toHaveCount(0);
    expect(posts).toHaveLength(0);
  });

  for (const stage of ['review', 'save'])
    test(`real competing owner POST invalidates source at ${stage} preflight`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info),
        posts = observe(p, s);
      await different(p, s);
      if (stage === 'save') await review(p);
      const winner = await competing(p, s);
      await button(
        p,
        stage === 'save' ? 'Lưu phân công' : 'Xem lại phân công',
      ).click();
      await expect(hook(p, 'error')).toHaveText(
        'Dữ liệu nguồn đã thay đổi. Bản nháp được giữ; tải lại dữ liệu rồi xem lại.',
      );
      await expect(hook(p, 'review')).toHaveCount(0);
      await expect(button(p, 'Xem lại phân công')).toBeDisabled();
      await expect(button(p, 'Chọn Agent')).toBeDisabled();
      await expect(p.locator('main')).not.toContainText('TASK_OWNER_CONFLICT');
      if (stage === 'save') await shot(p, info, 'conflict');
      expect(posts).toHaveLength(0); // APIRequestContext competitor is not a browser write.
      expect(row(s).task_owner_revision).toBe(winner.revision);
      p.once('dialog', d => d.accept());
      await button(p, 'Tải lại dữ liệu').click();
      await expect(hook(p, 'selection')).toHaveCount(0);
      await expect(hook(p, 'current')).toContainText(winner.after.agent.name);
    });

  for (const mode of ['lost', 'malformed'])
    for (const recovery of ['receipt', 'retry'])
      test(`committed ${mode} reply UNKNOWN explicit ${recovery} preserves exact command`, async ({
        page: p,
      }, info) => {
        const s = await open(p, info),
          stable = invariants(s);
        await different(p, s);
        await review(p);
        const f = await fault(p, s, mode);
        await button(p, 'Lưu phân công').click();
        await expect(hook(p, 'unknown')).toBeVisible();
        expect(f.attempts).toHaveLength(1);
        const committed = row(s);
        expect(JSON.parse(f.attempts[0].body)).toEqual({
          expectedRevision: s.source.revision,
          expectedFingerprint: s.source.fingerprint,
          ownerAgentId: f.receipt().after.agent.id,
          ownerFingerprint: expect.stringMatching(/^[0-9a-f]{64}$/),
        });
        expect(f.attempts[0].key).toMatch(/^[0-9a-f-]{36}$/);
        if (mode === 'lost' && recovery === 'receipt')
          await shot(p, info, 'unknown');
        if (recovery === 'receipt') {
          const path = `${s.api}/receipts/${f.attempts[0].key}`;
          const r = await response(p, path, () =>
            button(p, 'Kiểm tra biên nhận').click(),
          );
          expect((await r.json()).data).toEqual({receipt: f.receipt()});
        } else {
          const r = await response(
            p,
            s.api,
            () => button(p, 'Gửi lại đúng yêu cầu này').click(),
            'POST',
          );
          expect(r.status()).toBe(200);
          expect(f.attempts).toHaveLength(2);
          expect(f.attempts[1]).toEqual(f.attempts[0]);
        }
        await expect(hook(p, 'saved')).toBeVisible();
        expect(f.attempts).toHaveLength(recovery === 'receipt' ? 1 : 2);
        expect(row(s)).toEqual(committed);
        expect(invariants(s)).toBe(stable);
        await savedFocus(p, s, f.receipt());
        if (mode === 'lost' && recovery === 'receipt')
          await shot(p, info, 'recovered');
        const storage = await p.evaluate(() => ({
          local: {...localStorage},
          session: {...sessionStorage},
        }));
        const persisted = JSON.stringify(storage);
        for (const secret of [
          s.source.taskTitle,
          f.attempts[0].body,
          f.attempts[0].key,
          f.receipt().after.agent.name,
        ])
          expect(persisted).not.toContain(secret);
      });

  test('member has existing detail read but no owner UI or context access', async ({
    page: p,
  }, info) => {
    await authenticate(p, info, 'member');
    const s = scope(info);
    await p.goto(s.detail);
    await expect(
      p.getByRole('link', {name: 'Phân công Agent', exact: true}),
    ).toHaveCount(0);
    expect((await p.request.get(s.api)).status()).toBe(403);
    await p.goto(s.url);
    await expect(hook(p, 'denied')).toBeVisible();
    await expect(hook(p, 'current')).toHaveCount(0);
  });

  for (const fence of ['role', 'actor', 'route'])
    test(`held committed response drains safely after ${fence} fence`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info);
      const name = await different(p, s);
      await review(p);
      const posts = observe(p, s);
      const held = await hold(p, s.api, 'POST', 201, async actual => {
        const receipt = (await actual.json()).data;
        expect(row(s).task_owner_revision).toBe(receipt.revision);
        expect(receipt.after.agent.name).toBe(name);
      });
      let sibling;
      try {
        await button(p, 'Lưu phân công').click();
        await held.seen;
        if (fence === 'role') {
          membership(s, info, 'MEMBER');
          await focus(p);
          await expect(hook(p, 'denied')).toBeVisible();
          await erased(p, name);
          await shot(p, info, 'denied');
        } else if (fence === 'actor') {
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
          await authenticate(sibling, info, 'admin2');
          await response(p, '/api/v1/auth/session', () => focus(p));
          await erased(p, name);
        } else {
          const other = scope(info, 2);
          p.once('dialog', d => d.accept());
          await response(p, other.api, () => pushSpaHistory(p, other.url));
          await expect(p).toHaveURL(other.url);
          await expect(hook(p, 'saved')).toHaveCount(0);
          await expect(hook(p, 'selection')).toHaveCount(0);
        }
        await held.release();
        await expect(hook(p, 'saved')).toHaveCount(0);
        if (fence !== 'route') await erased(p, name);
        expect(posts).toHaveLength(1);
      } finally {
        try {
          await held.release();
        } finally {
          if (fence === 'role') membership(s, info, 'ADMIN');
          if (sibling) await sibling.close();
        }
      }
    });
  test('selected-candidate save preflight racing real competing POST rejects stale write', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    await different(p, s);
    await review(p);
    const selected = await hook(p, 'review').textContent();
    expect(selected).toContain('Task owner Agent');
    const posts = observe(p, s);
    let winner;
    // Read a genuine candidate response, commit a competing owner write before
    // delivering it, then let the original browser command reach the backend.
    // This checks the window AFTER source preflight, not a fake candidate DTO.
    const pattern = `**${s.api}/eligible-owners/*`;
    await p.route(pattern, async route => {
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      winner = await competing(p, s);
      await route.fulfill({response: actual});
    });
    try {
      const r = await response(
        p,
        s.api,
        () => button(p, 'Lưu phân công').click(),
        'POST',
      );
      expect(r.status()).toBe(409);
      await expect(hook(p, 'error')).toHaveText(
        'Yêu cầu bị từ chối. Tải lại dữ liệu rồi xem lại.',
      );
      await expect(hook(p, 'review')).toHaveCount(0);
      await expect(button(p, 'Xem lại phân công')).toBeDisabled();
      await expect(hook(p, 'saved')).toHaveCount(0);
      expect(posts).toHaveLength(1);
      expect(row(s).task_owner_revision).toBe(winner.revision);
    } finally {
      await p.unroute(pattern);
    }
  });

  for (const stage of ['review', 'save'])
    test(`current permission loss at ${stage} preflight erases private choice without POST`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info),
        posts = observe(p, s);
      const name = await different(p, s);
      if (stage === 'save') await review(p);
      try {
        membership(s, info, 'MEMBER');
        await button(
          p,
          stage === 'save' ? 'Lưu phân công' : 'Xem lại phân công',
        ).click();
        await expect(hook(p, 'denied')).toBeVisible();
        await erased(p, name);
        expect(posts).toHaveLength(0);
      } finally {
        membership(s, info, 'ADMIN');
      }
    });

  test('UNKNOWN role restoration allows only explicit own receipt after command erasure', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    const name = await different(p, s);
    await review(p);
    const f = await fault(p, s, 'lost');
    await button(p, 'Lưu phân công').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    try {
      membership(s, info, 'MEMBER');
      await focus(p);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, name);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
      membership(s, info, 'ADMIN');
      const path = `${s.api}/receipts/${f.attempts[0].key}`;
      const r = await response(p, path, () =>
        button(p, 'Kiểm tra biên nhận').click(),
      );
      expect((await r.json()).data).toEqual({receipt: f.receipt()});
      await expect(hook(p, 'saved')).toBeVisible();
      expect(f.attempts).toHaveLength(1);
    } finally {
      membership(s, info, 'ADMIN');
    }
  });

  for (const mode of ['failed', 'malformed'])
    for (const stage of ['review', 'unknown'])
      test(`${stage} ${mode} authority revalidation clears private review without write`, async ({
        page: p,
      }, info) => {
        const s = await open(p, info);
        const name = await different(p, s);
        await review(p);
        let f;
        if (stage === 'unknown') {
          f = await fault(p, s, 'lost');
          await button(p, 'Lưu phân công').click();
          await expect(hook(p, 'unknown')).toBeVisible();
        }
        const before = row(s),
          posts = observe(p, s);
        const path = `/api/v1/auth/workspaces/${s.org}`;
        let faults = 0;
        const match = url => url.pathname === path;
        await p.route(match, async route => {
          faults++;
          if (mode === 'failed')
            return route.fulfill({
              status: 503,
              contentType: 'text/plain',
              body: 'Unavailable',
            });
          const actual = await route.fetch();
          expect(actual.status()).toBe(200);
          // Corrupt only delivery of a real authority read; no invented business DTO.
          await route.fulfill({response: actual, body: '{malformed'});
        });
        try {
          if (stage === 'review') await focus(p);
          else await button(p, 'Kiểm tra biên nhận').click();
          await expect(hook(p, 'error')).toHaveText(
            'Không thể xác minh quyền hiện tại. Vui lòng thử kiểm tra lại.',
          );
          await expect(hook(p, 'loading')).toHaveCount(0);
          expect(faults).toBeGreaterThan(0);
          await erased(p, name);
          await expect(button(p, 'Lưu phân công')).toHaveCount(0);
          if (stage === 'unknown') {
            await expect(hook(p, 'unknown')).toBeVisible();
            await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
            expect(f.attempts).toHaveLength(1);
          }
          expect(posts).toHaveLength(0);
          expect(row(s)).toEqual(before);
        } finally {
          await p.unroute(match);
        }
      });

  test('malformed real receipt remains UNKNOWN until explicit valid lookup', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    await different(p, s);
    await review(p);
    const f = await fault(p, s, 'lost');
    await button(p, 'Lưu phân công').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    const path = `${s.api}/receipts/${f.attempts[0].key}`;
    await p.route(`**${path}`, async route => {
      const actual = await route.fetch();
      expect((await actual.json()).data).toEqual({receipt: f.receipt()});
      await route.fulfill({response: actual, body: '{malformed'});
    });
    await response(p, path, () => button(p, 'Kiểm tra biên nhận').click());
    await expect(hook(p, 'unknown')).toBeVisible();
    await expect(hook(p, 'saved')).toHaveCount(0);
    expect(f.attempts).toHaveLength(1);
    await p.unroute(`**${path}`);
    await response(p, path, () => button(p, 'Kiểm tra biên nhận').click());
    await expect(hook(p, 'saved')).toBeVisible();
    expect(f.attempts).toHaveLength(1);
  });
});
