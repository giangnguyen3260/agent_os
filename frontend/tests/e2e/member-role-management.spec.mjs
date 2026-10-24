import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';

// Disposable role fixture only; browser targets 101..120/201..220/301..320.
const org = '80808080-8080-4080-8080-808080808080';
const account = n => `80800000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const base = n => `/api/v1/organizations/${org}/members/${account(n)}/role-management`;
const route = n => `/workspace/${org}/members/${account(n)}/role`;
const index = i => ({desktop: 100, tablet: 200, mobile: 300})[i.project.name];
const actor = i => ({desktop: 3, tablet: 4, mobile: 5})[i.project.name];
const button = (p, name) => p.getByRole('button', {name, exact: true});
const reason = p => p.getByLabel('Lý do', {exact: true});
const selection = p => p.getByLabel('Vai trò mới', {exact: true});
const ack = p => p.getByRole('checkbox', {name: /^Tôi xác nhận thay đổi vai trò tổ chức này\.?$/});
const result = p => p.getByTestId('role-result');
const unknown = p => button(p, 'Kiểm tra biên nhận');
const dialogs = new WeakMap();
const matches = (r, path, method) => new URL(r.url()).pathname === path && r.request().method() === method;
const response = (p, path, method = 'GET') => p.waitForResponse(r => matches(r, path, method));
async function read(p, path, action, method = 'GET') {
  const [r] = await Promise.all([response(p, path, method), action()]);
  return r;
}
async function data(p, path) {
  const r = await p.request.get(path); expect(r.status(), path).toBe(200);
  return (await r.json()).data;
}
async function login(p, who) {
  expect(process.env.E2E_PASSWORD, 'Disposable E2E_PASSWORD required').toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(`role-management-${who}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click(); await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, n) {
  dialogs.set(p, 'accept');
  try {
    const loadedHistory = response(p, `${base(n)}/history`);
    const r = await read(p, `${base(n)}/context`, () => p.goto(route(n)));
    expect(r.status()).toBe(200); await expect(p.getByTestId('role-member')).toBeVisible();
    await expect(selection(p)).toBeVisible(); expect((await loadedHistory).status()).toBe(200);
    await expect(p.getByRole('region', {name: 'Lịch sử vai trò', exact: true})).toBeVisible();
    return (await r.json()).data;
  } finally { dialogs.set(p, 'dismiss'); }
}
async function fill(p, text = 'Synthetic role change', role = 'ADMIN') {
  await selection(p).selectOption(role); await reason(p).fill(text);
}
async function review(p, n) {
  const r = await read(p, `${base(n)}/context`, () => button(p, 'Xem lại thay đổi').click());
  expect(r.status()).toBe(200); await expect(p.getByTestId('role-review')).toBeVisible();
  await expect(ack(p)).not.toBeChecked(); return (await r.json()).data;
}
async function save(p, n) {
  await ack(p).check();
  const r = await read(p, base(n), () => button(p, 'Lưu vai trò').click(), 'POST');
  expect(r.status()).toBe(201); await expect(result(p)).toBeVisible();
  return {value: (await r.json()).data, request: r.request()};
}
async function change(p, n, role) {
  const c = await data(p, `${base(n)}/context?newRole=${role}`);
  const csrf = await data(p, '/api/v1/auth/csrf');
  const r = await p.request.post(base(n), {headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': randomUUID()},
    data: {expectedRevision: c.revision, expectedCurrentRole: c.member.currentRole, newRole: role, reason: 'Synthetic independent role writer', acknowledgement: true}});
  expect(r.status()).toBe(201); return (await r.json()).data;
}
const history = (p, n) => data(p, `${base(n)}/history?page=0`);
async function shot(p, info, state) {
  expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const directory = process.env.E2E_VISUAL_DIR || 'qa-visuals'; mkdirSync(directory, {recursive: true});
  await p.screenshot({path: join(directory, `member-role-management-${info.project.name}-${state}.png`), fullPage: true});
}
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c', 'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
    {cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8', timeout: 15000, stdio: ['pipe', 'pipe', 'pipe']}).trim();
}
function restoreRole(n, role) {
  sql(`UPDATE organization_memberships SET role='${role}' WHERE organization_id='${org}' AND account_id='${account(n)}';`);
}
function barrier() {
  let release; const promise = new Promise(resolve => { release = resolve; });
  return {promise: () => Promise.race([promise, new Promise((_, reject) => { const t = setTimeout(() => reject(new Error('Real-response barrier timed out')), 15000); promise.finally(() => clearTimeout(t)); })]), resolve: () => release()};
}
async function privateGone(p, secrets) {
  for (const secret of secrets) await expect(p.locator('main')).not.toContainText(secret);
  await expect(reason(p)).toHaveCount(0); await expect(result(p)).toHaveCount(0);
  await expect(button(p, 'Gửi lại đúng yêu cầu này')).toHaveCount(0);
}
async function spa(p, path) {
  await p.evaluate(url => { history.pushState({}, '', url); dispatchEvent(new PopStateEvent('popstate', {state: {}})); }, path);
}
function writes(p) {
  const items = []; p.on('request', r => { if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/role-management')) items.push(r); }); return items;
}
async function lose(p, n, malformed = false) {
  let committed; let command;
  const pattern = `**${base(n)}`;
  await p.route(pattern, async interception => {
    const request = interception.request();
    if (request.method() !== 'POST') return interception.continue();
    command = {body: request.postData(), key: request.headers()['idempotency-key']};
    const actual = await interception.fetch(); expect(actual.status()).toBe(201); committed = (await actual.json()).data;
    if (malformed) await interception.fulfill({response: actual, json: {data: {unexpected: true}}});
    else await interception.abort('failed');
  });
  try { await ack(p).check(); await button(p, 'Lưu vai trò').click(); await expect(unknown(p)).toBeVisible(); }
  finally { await p.unroute(pattern); }
  await expect(result(p)).toHaveCount(0); return {committed, command};
}

test.describe('real member role management', () => {
  test.describe.configure({timeout: 120000});
  test.beforeEach(async ({page: p}, info) => {
    dialogs.set(p, 'dismiss'); p.on('dialog', async d => d[dialogs.get(p) || 'dismiss']());
    await login(p, info.project.name);
  });

  test('MR-B01 blank edit, fresh review, promotion, exact result and retained history', async ({page: p}, info) => {
    const n = index(info) + 1, observed = writes(p);
    const initial = await open(p, n);
    expect(initial).toMatchObject({selectedRole: null, canChange: false});
    await expect(selection(p)).toHaveValue(''); await expect(reason(p)).toHaveValue('');
    await selection(p).focus(); await p.keyboard.press('Tab'); await expect(reason(p)).toBeFocused();
    await expect(button(p, 'Xem lại thay đổi')).toBeDisabled(); await shot(p, info, 'edit');
    await fill(p, '  Synthetic promotion\nRestricted reason.  ');
    const reviewed = await review(p, n); expect(reviewed).toMatchObject({selectedRole: 'ADMIN', canChange: true});
    expect(observed).toHaveLength(0); await expect(p.getByTestId('role-review').locator('..')).toContainText(/grant|quyền duyệt/i);
    await expect(button(p, 'Lưu vai trò')).toBeDisabled(); await shot(p, info, 'review');
    const saved = await save(p, n), r = saved.value;
    expect(Object.keys(r).sort()).toEqual('operation organizationId accountId actorId actorName idempotencyKey eventId revision recordedAt beforeRole afterRole'.split(' ').sort());
    expect(r).toMatchObject({operation: 'CHANGE_ROLE', organizationId: org, accountId: account(n), actorId: account(actor(info)), beforeRole: 'MEMBER', afterRole: 'ADMIN', revision: reviewed.revision + 1});
    expect(r.idempotencyKey).toBe(saved.request.headers()['idempotency-key']);
    expect(saved.request.postDataJSON()).toEqual({expectedRevision: reviewed.revision, expectedCurrentRole: 'MEMBER', newRole: 'ADMIN', reason: 'Synthetic promotion\nRestricted reason.', acknowledgement: true});
    for (const text of [r.eventId, r.actorName, r.recordedAt]) await expect(result(p)).toContainText(text);
    await expect(result(p)).not.toContainText('Restricted reason'); await shot(p, info, 'recorded');
    expect((await data(p, `${base(n)}/context`)).member.currentRole).toBe('ADMIN');
    expect((await history(p, n)).items[0]).toMatchObject({id: r.eventId, reason: 'Synthetic promotion\nRestricted reason.', revision: r.revision});
    await open(p, n); await expect(p.getByTestId('role-history-event')).toHaveCount(1); await shot(p, info, 'history');
    expect(observed).toHaveLength(1);
  });

  test('MR-B02 no-op and invalid reason never review; acknowledgement resets after edit', async ({page: p}, info) => {
    const n = index(info) + 2, observed = writes(p); await open(p, n);
    await fill(p, 'Synthetic no-op', 'MEMBER'); await expect(button(p, 'Xem lại thay đổi')).toBeDisabled();
    await selection(p).selectOption('ADMIN');
    for (const text of [' ', 'Invalid\u007fcontrol', '😀'.repeat(1001)]) {
      await reason(p).fill(text); await expect(button(p, 'Xem lại thay đổi')).toBeDisabled();
    }
    await reason(p).fill('\u00a0' + '😀'.repeat(998) + '\u00a0'); await review(p, n); await ack(p).check();
    await button(p, 'Quay lại chỉnh sửa').click(); await review(p, n); await expect(ack(p)).not.toBeChecked();
    expect(observed).toHaveLength(0);
  });

  test('MR-B03 self, disabled and foreign contexts disclose no unauthorized profile', async ({page: p}, info) => {
    const target = index(info) + 3, pattern = `**${base(target)}/context*`;
    for (const mode of ['scope', 'extra', 'revision', 'member', 'blockers']) {
      await p.route(pattern, async interception => {
        const actual = await interception.fetch(); expect(actual.status()).toBe(200); const body = await actual.json();
        if (mode === 'scope') body.data.organizationId = '81818181-8181-4181-8181-818181818181';
        if (mode === 'extra') body.data.member.email = 'SYNTHETIC_PRIVATE_EXTRA@example.test';
        if (mode === 'revision') body.data.revision = '0';
        if (mode === 'member') body.data.member.accountId = account(99);
        if (mode === 'blockers') body.data.blockers = ['SELF_CHANGE'];
        await interception.fulfill({response: actual, json: body});
      });
      try {
        await p.goto(route(target));
        await expect(p.getByRole('alert').filter({hasText: 'Không thể xác thực dữ liệu vai trò.'})).toBeVisible();
        await expect(p.getByTestId('role-member')).toHaveCount(0);
        await expect(p.locator('main')).not.toContainText('SYNTHETIC_PRIVATE_EXTRA@example.test');
      } finally { await p.unroute(pattern); }
    }
    for (const [n, blocker] of [[actor(info), 'SELF_CHANGE'], [6, 'TARGET_DISABLED']]) {
      const c = await data(p, `${base(n)}/context?newRole=MEMBER`); expect(c.blockers).toContain(blocker); expect(c.canChange).toBe(false);
      await p.goto(route(n)); await expect(button(p, 'Lưu vai trò')).toHaveCount(0);
    }
    expect((await p.request.get(`${base(99)}/context`)).status()).toBe(404);
    await p.goto(route(99));
    await expect(p.getByRole('alert').filter({hasText: 'Không thể truy cập dữ liệu này.'})).toBeVisible();
    await expect(p.getByTestId('role-member')).toHaveCount(0);
    await expect(p.getByTestId('role-review')).toHaveCount(0);
    await expect(button(p, 'Lưu vai trò')).toHaveCount(0);
    await expect(p.locator('main')).not.toContainText('FOREIGN_ROLE_PRIVATE'); await shot(p, info, 'error');
  });

  test('MR-B04 demotion readback preserves existing grant rows and separate authority warning', async ({page: p}, info) => {
    const n = index(info) + 2; await change(p, n, 'ADMIN');
    const grants = () => data(p, `/api/v1/organizations/${org}/members/${account(n)}/approval-grants?status=ALL&page=0`);
    const before = await grants(); expect(before.total, 'Fixture must provide an explicit grant').toBeGreaterThan(0);
    await open(p, n); await fill(p, 'Synthetic admin demotion preserves explicit grant', 'MEMBER'); await review(p, n);
    await expect(p.getByTestId('role-review').locator('..')).toContainText(/quyền duyệt|grant/i); await save(p, n);
    expect((await data(p, `${base(n)}/context`)).member.currentRole).toBe('MEMBER');
    expect((await grants()).items).toEqual(before.items);
  });

  test('MR-B05 actual RouterLink entry, canceled Back/reload and accepted dirty exit', async ({page: p}, info) => {
    const n = index(info) + 5, observed = writes(p);
    await p.goto(`/workspace/${org}/members/${account(n)}/approval-grants`);
    const entered = await read(p, `${base(n)}/context`, () => p.getByRole('link', {name: 'Sửa vai trò tổ chức', exact: true}).click()); expect(entered.status()).toBe(200);
    await fill(p, 'Synthetic dirty navigation reason');
    const back = p.waitForEvent('dialog'); await p.evaluate(() => history.back()); expect((await back).message()).toContain('chưa lưu');
    await expect(p).toHaveURL(new RegExp(`${route(n)}$`)); await expect(reason(p)).toHaveValue('Synthetic dirty navigation reason');
    const reload = p.waitForEvent('dialog'); await p.evaluate(() => location.reload()); expect((await reload).type()).toBe('beforeunload');
    await expect(reason(p)).toHaveValue('Synthetic dirty navigation reason');
    dialogs.set(p, 'accept'); await p.getByRole('link', {name: 'Về thành viên & quyền', exact: true}).click();
    await expect(p).toHaveURL(new RegExp(`/workspace/${org}/members$`)); expect(observed).toHaveLength(0);
  });

  test('MR-B06 committed lost reply retries identical body and key exactly once', async ({page: p}, info) => {
    const n = index(info) + 6; await open(p, n); await fill(p); await review(p, n);
    const {committed, command} = await lose(p, n); await shot(p, info, 'unknown');
    const storage = await p.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]));
    expect(storage).not.toContain(command.key); expect(storage).not.toContain('Synthetic role change');
    const replay = await read(p, base(n), () => button(p, 'Gửi lại đúng yêu cầu này').click(), 'POST');
    expect(replay.status()).toBe(200); expect(replay.request().postData()).toBe(command.body);
    expect(replay.request().headers()['idempotency-key']).toBe(command.key); expect((await replay.json()).data).toEqual(committed);
    await expect(result(p)).toBeVisible(); expect((await history(p, n)).total).toBe(1);
  });

  test('MR-B07 malformed committed reply and null receipt remain unknown through advanced-state replay', async ({page: p}, info) => {
    const n = index(info) + 7; await open(p, n); await fill(p); await review(p, n);
    const {committed, command} = await lose(p, n, true); const advanced = await change(p, n, 'MEMBER'); expect(advanced.revision).toBe(committed.revision + 1);
    const receiptPath = `${base(n)}/receipts/${command.key}`, pattern = `**${receiptPath}`;
    await p.route(pattern, async interception => {
      const actual = await interception.fetch(); expect(actual.status()).toBe(200); expect((await actual.json()).data).toEqual(committed);
      await interception.fulfill({response: actual, json: {data: null}});
    });
    try {
      await read(p, receiptPath, () => unknown(p).click()); await expect(result(p)).toHaveCount(0); await expect(unknown(p)).toBeVisible();
      const replay = await read(p, base(n), () => button(p, 'Gửi lại đúng yêu cầu này').click(), 'POST');
      expect(replay.status()).toBe(200); expect(replay.request().postData()).toBe(command.body); expect(replay.request().headers()['idempotency-key']).toBe(command.key);
      expect((await replay.json()).data).toEqual(committed); await expect(result(p)).toBeVisible(); expect((await history(p, n)).total).toBe(2);
    } finally { await p.unroute(pattern); }
  });

  test('MR-B08 observed same-ID denial erases body; restored ADMIN recovers by receipt GET only', async ({page: p}, info) => {
    const n = index(info) + 8, secret = 'Synthetic private denied reason', observed = writes(p);
    await open(p, n); await fill(p, secret); await review(p, n); const {committed} = await lose(p, n);
    try {
      restoreRole(actor(info), 'MEMBER');
      // Real workspace response observes loss of ADMIN before any private receipt request.
      const denied = await read(p, `/api/v1/auth/workspaces/${org}`, () => unknown(p).click());
      expect(denied.status()).toBe(200); expect((await denied.json()).data.role).toBe('MEMBER');
      await privateGone(p, [secret, 'Role Management Target ' + n]); await expect(unknown(p)).toBeVisible(); await shot(p, info, 'denied');
    } finally { restoreRole(actor(info), 'ADMIN'); }
    const before = observed.length;
    const recovered = await read(p, `${base(n)}/receipts/${committed.idempotencyKey}`, () => unknown(p).click());
    expect((await recovered.json()).data).toEqual(committed); await expect(result(p)).toContainText(committed.eventId); expect(observed).toHaveLength(before);
  });

  test('MR-B09 same-ID demotion observed on window focus removes private draft', async ({page: p}, info) => {
    const n = index(info) + 9, secret = 'Synthetic same-ID draft'; await open(p, n); await fill(p, secret);
    try {
      restoreRole(actor(info), 'MEMBER');
      const checked = response(p, `/api/v1/auth/workspaces/${org}`);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      expect((await (await checked).json()).data.role).toBe('MEMBER');
      await expect(p.getByRole('alert').filter({hasText: 'Không thể truy cập dữ liệu này.'})).toBeVisible();
      await privateGone(p, [secret]); await expect(unknown(p)).toHaveCount(0);
    } finally { restoreRole(actor(info), 'ADMIN'); }
  });

  test('MR-B10 reused route Back/Forward discards late actual context response', async ({page: p}, info) => {
    const n = index(info) + 10; await open(p, n);
    await fill(p, 'Synthetic reused-route draft');
    const canceled = p.waitForEvent('dialog'); await spa(p, route(n + 10));
    expect((await canceled).message()).toContain('chưa lưu'); await expect(p).toHaveURL(new RegExp(`${route(n)}$`));
    await expect(reason(p)).toHaveValue('Synthetic reused-route draft'); dialogs.set(p, 'accept');

    const forwardContext = await read(p, `${base(n + 10)}/context`, () => spa(p, route(n + 10)));
    await expect(p.getByTestId('role-member')).toContainText((await forwardContext.json()).data.member.displayName); await expect(selection(p)).toBeVisible(); dialogs.set(p, 'dismiss');
    const back = await read(p, `${base(n)}/context`, () => p.evaluate(() => history.back())); expect(back.status()).toBe(200); await expect(p.getByTestId('role-member')).toContainText((await back.json()).data.member.displayName); await expect(selection(p)).toBeVisible();
    const arrived = barrier(), release = barrier(), finished = barrier(), pattern = `**${base(n + 10)}/context*`;
    let lateName;
    await p.route(pattern, async interception => {
      try { const actual = await interception.fetch(); expect(actual.status()).toBe(200); lateName = (await actual.json()).data.member.displayName; arrived.resolve(); await release.promise(); await interception.fulfill({response: actual}); }
      catch (e) { if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e; }
      finally { finished.resolve(); }
    });
    try {
      await p.evaluate(() => history.forward()); await arrived.promise();
      const restored = await read(p, `${base(n)}/context`, () => p.evaluate(() => history.back())); const current = (await restored.json()).data;
      await expect(p.getByTestId('role-member')).toContainText(current.member.displayName); release.resolve(); await finished.promise();
      await expect(p.getByTestId('role-member')).not.toContainText(lateName); await expect(reason(p)).toHaveValue('');
    } finally { release.resolve(); await p.unroute(pattern); }
  });

  test('MR-B11 shared-session account switch drops late committed result and correlation', async ({page: p}, info) => {
    const n = index(info) + 11, secret = 'Synthetic old actor private draft'; await open(p, n); await fill(p, secret); await review(p, n);
    const arrived = barrier(), release = barrier(), finished = barrier(), pattern = `**${base(n)}`; let committed;
    await p.route(pattern, async interception => {
      try { const actual = await interception.fetch(); expect(actual.status()).toBe(201); committed = (await actual.json()).data; arrived.resolve(); await release.promise(); await interception.fulfill({response: actual}); }
      catch (e) { if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e; } finally { finished.resolve(); }
    });
    const sibling = await p.context().newPage();
    try {
      await ack(p).check(); await button(p, 'Lưu vai trò').click(); await arrived.promise();
      await sibling.goto('/workspace'); await button(sibling, 'Đăng xuất').click(); await expect(sibling).toHaveURL(/\/login$/); await login(sibling, 'admin');
      const fresh = response(p, '/api/v1/auth/session'); await p.bringToFront(); await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      expect((await (await fresh).json()).data.id).not.toBe(committed.actorId);
      await privateGone(p, [secret, committed.eventId]); release.resolve(); await finished.promise();
      await privateGone(p, [secret, committed.eventId]); await expect(unknown(p)).toHaveCount(0);
    } finally { release.resolve(); await p.unroute(pattern); await sibling.close(); }
  });

  test('MR-B12 late real history denial during committed POST retains only GET recovery', async ({page: p}, info) => {
    const n = index(info) + 12, secret = 'Synthetic late history private reason';
    await open(p, n); await fill(p, secret);
    const arrived = barrier(), releaseHistory = barrier(), committedSignal = barrier(), releasePost = barrier(), finished = barrier();
    const hp = `**${base(n)}/history?*`, pp = `**${base(n)}`; let committed;
    await p.route(hp, async interception => {
      arrived.resolve(); await releaseHistory.promise(); const actual = await interception.fetch(); expect(actual.status()).toBe(403); await interception.fulfill({response: actual});
    });
    await p.route(pp, async interception => {
      try { const actual = await interception.fetch(); expect(actual.status()).toBe(201); committed = (await actual.json()).data; committedSignal.resolve(); await releasePost.promise(); await interception.fulfill({response: actual}); }
      catch (e) { if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e; } finally { finished.resolve(); }
    });
    const observed = writes(p);
    try {
      await review(p, n); await arrived.promise(); await ack(p).check(); await button(p, 'Lưu vai trò').click(); await committedSignal.promise();
      restoreRole(actor(info), 'MEMBER'); const denied = response(p, `${base(n)}/history`); releaseHistory.resolve(); expect((await denied).status()).toBe(403);
      await privateGone(p, [secret]); await expect(unknown(p)).toBeVisible();
      restoreRole(actor(info), 'ADMIN');
      const requests = []; const record = r => { if (new URL(r.url()).pathname.startsWith(base(n))) requests.push([r.method(), new URL(r.url()).pathname]); }; p.on('request', record);
      try {
        const path = `${base(n)}/receipts/${committed.idempotencyKey}`;
        const recovered = await read(p, path, () => unknown(p).click()); expect((await recovered.json()).data).toEqual(committed);
        await expect(result(p)).toContainText(committed.eventId); expect(requests).toEqual([['GET', path]]);
        releasePost.resolve(); await finished.promise(); await expect(result(p)).toContainText(committed.eventId); expect(observed).toHaveLength(1);
      } finally { p.off('request', record); }
    } finally {
      try { restoreRole(actor(info), 'ADMIN'); }
      finally { releaseHistory.resolve(); releasePost.resolve(); await p.unroute(hp); await p.unroute(pp); }
    }
  });
});
