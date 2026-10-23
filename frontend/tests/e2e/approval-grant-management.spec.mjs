import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';

// Dedicated scripts/approval-grant-management-ci-fixture.sql only. No baseline writes.
const org = '60606060-6060-4060-8060-606060606060';
const foreign = '61616161-6161-4161-8161-616161616161';
const id = (prefix, n) => `${prefix}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const account = n => id('6080', n);
const team = n => id('6010', n);
const grant = n => id('6070', n);
const index = info => ({desktop: 100, tablet: 200, mobile: 300})[info.project.name];
const root = n => `/api/v1/organizations/${org}/members/${account(n)}/approval-grants`;
const route = n => `/workspace/${org}/members/${account(n)}/approval-grants`;
const issueRoute = n => `${route(n)}/new`;
const revokeRoute = (n, g) => `${route(n)}/${g}/revoke`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const teamButton = (p, name) => button(p, `Chọn team: ${name} · ${name.includes('Archived') ? 'ARCHIVED' : name.includes('Active') ? 'ACTIVE' : 'DRAFT'}`);
const reason = p => p.locator('textarea[aria-label="Lý do"], textarea[aria-label="Lý do thu hồi"]');
const risk = p => p.locator('select[aria-label="Mức rủi ro"]');
const duration = p => p.getByLabel('Thời hạn (giờ)', {exact: true});
const ack = p => p.getByRole('checkbox', {name: /^(Tôi xác nhận phạm vi và thời hạn trên\.|Tôi hiểu việc thu hồi là vĩnh viễn\.)$/});
const success = p => p.getByRole('heading', {name: 'Thao tác đã được ghi nhận', exact: true});
const unknown = p => button(p, 'Kiểm tra biên nhận');
const contextPath = (n, g = '') => g ? `${root(n)}/${g}/revocation-context` : `${root(n)}/management-context`;
const dialogAction = new WeakMap();
const requestIs = (r, path, method = 'GET') => new URL(r.url()).pathname === path && r.method() === method;
async function spa(p, target) {
  await p.evaluate(path => {
    history.pushState({}, '', path);
    window.dispatchEvent(new PopStateEvent('popstate', {state: {}}));
  }, target);
}
function responseFor(p, path, method = 'GET') {
  return p.waitForResponse(r => requestIs(r.request(), path, method));
}
async function read(p, path, action, method = 'GET') {
  const [r] = await Promise.all([responseFor(p, path, method), action()]);
  return r;
}
async function data(p, path) {
  const r = await p.request.get(path);
  expect(r.status(), path).toBe(200);
  return (await r.json()).data;
}
async function login(p, role) {
  expect(process.env.E2E_PASSWORD, 'Disposable fixture E2E_PASSWORD is required').toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(`grant-management-${role}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, n, g = '') {
  dialogAction.set(p, 'accept');
  let r;
  try { r = await read(p, contextPath(n, g), () => p.goto(g ? revokeRoute(n, g) : issueRoute(n))); }
  finally { dialogAction.set(p, 'dismiss'); }
  expect(r.status()).toBe(200);
  await expect(p.getByRole('heading', {name: g ? 'Thu hồi quyền duyệt' : 'Cấp quyền duyệt', exact: true})).toBeVisible();
  await expect(p.locator('section[aria-labelledby="grant-title"]')).toHaveAttribute('aria-busy', 'false');
  return (await r.json()).data;
}
async function fill(p, {hours = '1', level = 'LOW', text = 'Synthetic scoped grant review', name = 'Grant Management Draft Team'} = {}) {
  await teamButton(p, name).click();
  await risk(p).selectOption(level);
  await duration(p).fill(hours);
  await reason(p).fill(text);
}
async function review(p, n, g = '') {
  const r = await read(p, contextPath(n, g), () => button(p, g ? 'Xem lại thu hồi' : 'Xem lại quyền duyệt').click());
  expect(r.status()).toBe(200);
  await expect(ack(p)).toBeVisible();
  await expect(ack(p)).not.toBeChecked();
  return (await r.json()).data;
}
async function save(p, n, g = '', expected = 201) {
  await ack(p).check();
  const r = await read(p, root(n) + (g ? `/${g}/revoke` : ''), () => button(p, g ? 'Thu hồi grant này' : 'Cấp quyền duyệt').click(), 'POST');
  expect(r.status()).toBe(expected);
  const body = await r.json();
  await expect(success(p)).toBeVisible();
  return {result: body.data, request: r.request()};
}
// Independent API writes are real backend competitors/oracles, never success mocks.
async function post(p, path, body, key = randomUUID()) {
  const csrf = await data(p, '/api/v1/auth/csrf');
  return p.request.post(path, {data: body, headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': key}});
}
async function issue(p, n, {level = 'MEDIUM', teamId = team(2), text = 'Synthetic independent competing write', key = randomUUID()} = {}) {
  const c = await data(p, `${root(n)}/management-context?teamId=${teamId}&riskLevel=${level}`);
  expect(c.canIssue).toBe(true);
  const command = {expectedRevision: c.revision, teamId, actionType: 'PUBLISH_PREVIEW', riskLevel: level, durationHours: 1, reason: text, acknowledgement: true};
  const r = await post(p, root(n), command, key);
  expect(r.status()).toBe(201);
  return {result: (await r.json()).data, command, key};
}
async function history(p, n, g) {
  return data(p, `${root(n)}/${g}/management-history?page=0`);
}
async function overflow(p) {
  expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}
async function shot(p, info, state) {
  await overflow(p);
  const directory = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(directory, {recursive: true});
  await p.screenshot({path: join(directory, `approval-grant-management-${info.project.name}-${state}.png`), fullPage: true});
}
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
    cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8', timeout: 15000,
    stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}
function barrier() {
  let release;
  return {promise: new Promise(resolve => { release = resolve; }), resolve: () => release()};
}
function observeWrites(p) {
  const writes = [];
  p.on('request', r => { if (r.method() === 'POST' && new URL(r.url()).pathname.startsWith(`/api/v1/organizations/${org}/members/`)) writes.push(r); });
  return writes;
}
async function noPrivate(p, secrets) {
  await expect(success(p)).toHaveCount(0);
  for (const secret of secrets) await expect(p.locator('main')).not.toContainText(secret);
  await expect(reason(p)).toHaveCount(0);
}
async function independent(browser, p, role) {
  const context = await browser.newContext({baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8080', viewport: p.viewportSize()});
  const other = await context.newPage();
  await login(other, role);
  expect(await other.evaluate(() => innerWidth)).toBe(p.viewportSize().width);
  return {context, page: other};
}

// Every test is intentionally collected at all three existing viewport projects.
test.describe('real approval grant management', () => {
  test.describe.configure({timeout: 120000});
  test.beforeEach(async ({page: p}, info) => {
    // Dialog handler exists before login, Back, reload, or any guarded action.
    dialogAction.set(p, 'dismiss');
    p.on('dialog', async d => { await d[dialogAction.get(p) || 'dismiss'](); });
    await login(p, info.project.name);
  });

  test('AGM-B01 blank edit, scoped review, issue/readback/reload, revoke and history', async ({page: p}, info) => {
    const n = index(info) + 1;
    const writes = observeWrites(p);
    const initial = await open(p, n);
    expect(initial).toMatchObject({revision: 0, selection: null, canIssue: true});
    for (const field of [risk(p), duration(p), reason(p)]) await expect(field).toHaveValue('');
    await expect(ack(p)).toHaveCount(0);
    await expect(button(p, 'Xem lại quyền duyệt')).toBeDisabled();
    await shot(p, info, 'edit');
    await expect(teamButton(p, 'Grant Management Archived Team')).toBeDisabled();
    const search = p.getByLabel('Tìm team', {exact: true});
    await search.focus();
    await p.keyboard.press('Tab');
    await expect(button(p, 'Tìm kiếm team')).toBeFocused();
    const directoryPath = `/api/v1/organizations/${org}/teams/directory`;
    await search.fill('Grant Management Active');
    const searched = await read(p, directoryPath, () => button(p, 'Tìm kiếm team').click());
    expect((await searched.json()).data.items.map(t => t.id)).toEqual([team(2)]);
    await expect(teamButton(p, 'Grant Management Draft Team')).toHaveCount(0);
    await search.fill('');
    await read(p, directoryPath, () => button(p, 'Tìm kiếm team').click());
    await fill(p, {text: '  Synthetic issue reason\nScoped internal review.  '});
    const reviewed = await review(p, n);
    expect(reviewed.selection).toMatchObject({teamId: team(1), riskLevel: 'LOW', actionType: 'PUBLISH_PREVIEW', unexpiredCount: 0});
    expect(writes).toHaveLength(0);
    await shot(p, info, 'review');
    const issued = await save(p, n);
    const r = issued.result;
    expect(r).toMatchObject({operation: 'ISSUE', organizationId: org, accountId: account(n), revision: reviewed.revision + 1, revokedAt: null});
    expect(Object.keys(r).sort()).toEqual('operation organizationId accountId actorId actorName idempotencyKey grantId eventId revision recordedAt expiresAt revokedAt'.split(' ').sort());
    expect(r.idempotencyKey).toBe(issued.request.headers()['idempotency-key']);
    expect(Date.parse(r.expiresAt) - Date.parse(r.recordedAt)).toBe(3600000);
    expect(issued.request.postDataJSON()).toEqual({expectedRevision: reviewed.revision, teamId: team(1), actionType: 'PUBLISH_PREVIEW', riskLevel: 'LOW', durationHours: 1, reason: 'Synthetic issue reason\nScoped internal review.', acknowledgement: true});
    for (const value of [r.grantId, r.eventId, r.actorName, r.recordedAt, r.expiresAt]) await expect(p.getByTestId('grant-result')).toContainText(value);
    await shot(p, info, 'issued');
    const inventory = await data(p, `${root(n)}?page=0&status=ALL`);
    expect(inventory.items).toEqual([expect.objectContaining({id: r.grantId, expiresAt: r.expiresAt, revokedAt: null, status: 'UNEXPIRED'})]);
    await p.goto(route(n));
    await p.reload();
    await expect(p.getByTestId('approval-grant')).toContainText('LOW');
    const revoke = await open(p, n, r.grantId);
    expect(revoke).toMatchObject({revision: r.revision, remainingScopeGrants: 0, canRevoke: true});
    await reason(p).fill('Synthetic permanent revocation');
    await shot(p, info, 'revoke');
    await review(p, n, r.grantId);
    const revoked = (await save(p, n, r.grantId)).result;
    expect(revoked).toMatchObject({operation: 'REVOKE', grantId: r.grantId, revision: r.revision + 1, expiresAt: r.expiresAt});
    expect(revoked.revokedAt).toBe(revoked.recordedAt);
    await expect(p.getByTestId('grant-result')).toContainText(revoked.revokedAt);
    await shot(p, info, 'revoked');
    const h = await history(p, n, r.grantId);
    expect(h.total).toBe(2);
    expect(h.items.map(x => [x.id, x.operation, x.reason])).toEqual([
      [revoked.eventId, 'REVOKE', 'Synthetic permanent revocation'],
      [r.eventId, 'ISSUE', 'Synthetic issue reason\nScoped internal review.'],
    ]);
    await open(p, n, r.grantId);
    await expect(p.locator('main')).toContainText('Synthetic permanent revocation');
    await expect(p.locator('main')).toContainText('Synthetic issue reason');
    expect((await data(p, contextPath(n, r.grantId))).blockers).toEqual(['ALREADY_REVOKED']);
    expect(writes).toHaveLength(2);
  });

  test('AGM-B02 HIGH 168-hour bound, reason/code-point validation and edit acknowledgement reset', async ({page: p}, info) => {
    const n = index(info) + 3;
    const writes = observeWrites(p);
    await open(p, n);
    await fill(p, {hours: '168', level: 'HIGH'});
    for (const value of ['0', '169', '1.5']) {
      await duration(p).fill(value);
      await expect(button(p, 'Xem lại quyền duyệt')).toBeDisabled();
      await expect(ack(p)).toHaveCount(0);
    }
    await duration(p).fill('168');
    for (const value of [' ', 'Invalid\u007fcontrol', '😀'.repeat(1001)]) {
      await reason(p).fill(value);
      await expect(button(p, 'Xem lại quyền duyệt')).toBeDisabled();
      await expect(ack(p)).toHaveCount(0);
    }
    await reason(p).fill('\u00a0' + '😀'.repeat(998) + '\u00a0');
    await review(p, n);
    await ack(p).check();
    await button(p, 'Quay lại chỉnh sửa').click();
    await review(p, n);
    await expect(ack(p)).not.toBeChecked();
    expect(writes).toHaveLength(0);
    const saved = await save(p, n);
    expect(Date.parse(saved.result.expiresAt) - Date.parse(saved.result.recordedAt)).toBe(168 * 3600000);
    expect((await history(p, n, saved.result.grantId)).items[0].reason).toBe('\u00a0' + '😀'.repeat(998) + '\u00a0');
    await overflow(p);
  });

  test('AGM-B03 legacy duplicates warn, selected revoke preserves other row and unavailable provenance', async ({page: p}, info) => {
    const n = index(info) + 2;
    const first = grant(n + 10000), other = grant(n + 20000);
    const context = await open(p, n, first);
    expect(context.remainingScopeGrants).toBe(1);
    expect((await history(p, n, first)).items).toEqual([]);
    await expect(p.locator('main')).toContainText(/khác|còn lại/i);
    await expect(p.locator('main')).toContainText(/không có|không khả dụng|chưa có/i);
    await reason(p).fill('Synthetic revoke of only one legacy duplicate');
    await review(p, n, first);
    await save(p, n, first);
    const remainder = await data(p, contextPath(n, other));
    expect(remainder.grant.revokedAt).toBeNull();
    expect(remainder.canRevoke).toBe(true);
    expect(remainder.remainingScopeGrants).toBe(0);
    expect((await history(p, n, other)).items).toEqual([]);
    // Read-only fixed legacy targets do not consume shared native probe mutations.
    for (const [target, g, expected] of [[6, grant(6), {enabled: false, membershipPresent: true}], [7, grant(7), {membershipPresent: false}], [2, grant(3), {}]]) {
      const c = await open(p, target, g);
      expect(c.recipient).toMatchObject(expected);
      expect(c.canRevoke).toBe(true);
      if (g === grant(3)) expect(c.grant).toMatchObject({actionType: 'LEGACY_UNSUPPORTED', riskLevel: 'CRITICAL'});
    }
    await overflow(p);
  });

  test('AGM-B04 real revision conflict, duplicate block and scoped/self/disabled denials', async ({page: p, browser}, info) => {
    const n = index(info) + 4;
    const other = await independent(browser, p, 'admin');
    try {
      await open(p, n);
      await fill(p);
      const reviewed = await review(p, n);
      const competitor = await issue(other.page, n);
      expect(competitor.result.revision).toBe(reviewed.revision + 1);
      await ack(p).check();
      const rejected = await read(p, root(n), () => button(p, 'Cấp quyền duyệt').click(), 'POST');
      expect(rejected.status()).toBe(409);
      await expect(success(p)).toHaveCount(0);
      expect((await data(p, `${root(n)}?status=ALL&page=0`)).total).toBe(1);
      await open(p, n);
      await fill(p, {name: 'Grant Management Active Team', level: 'MEDIUM'});
      const blocked = await read(p, contextPath(n), () => button(p, 'Xem lại quyền duyệt').click());
      expect((await blocked.json()).data.blockers).toEqual(['DUPLICATE_SCOPE']);
      await expect(ack(p)).toHaveCount(0);
      await expect(p.locator('main')).toContainText('Đã có grant chưa hết hạn với đúng phạm vi này.');
      expect((await p.request.get(`${root(n)}/management-context?teamId=${team(99)}&riskLevel=LOW`)).status()).toBe(404);
      expect((await p.request.get(contextPath(99))).status()).toBe(404);
      expect((await p.request.get(contextPath(n, grant(1)))).status()).toBe(404);
      expect((await p.request.get(`/api/v1/organizations/${foreign}/members/${account(99)}/approval-grants/management-context`)).status()).toBe(403);
      for (const target of [({desktop: 3, tablet: 4, mobile: 5})[info.project.name], 6]) {
        const c = await open(p, target);
        expect(c.canIssue).toBe(false);
        expect(c.blockers).toContain(target === 6 ? 'RECIPIENT_DISABLED' : 'SELF_ISSUANCE');
      }
      await p.goto(issueRoute(99));
      await expect(p.getByRole('alert')).toBeVisible();
      await noPrivate(p, ['FOREIGN_GRANT_TEAM_PRIVATE']);
    } finally { await other.context.close(); }
  });

  test('AGM-B05 dirty Back cancellation, accepted route reuse and blank return never POST', async ({page: p}, info) => {
    const n = index(info) + 5;
    const writes = observeWrites(p);
    await p.goto(route(n));
    const initial = await read(p, contextPath(n), () => p.getByRole('link', {name: 'Cấp quyền duyệt', exact: true}).click());
    expect(initial.status()).toBe(200);
    await expect(p.getByRole('heading', {name: 'Cấp quyền duyệt', exact: true})).toBeVisible();
    await expect(p.locator('section[aria-labelledby="grant-title"]')).toHaveAttribute('aria-busy', 'false');
    await fill(p, {text: 'Synthetic dirty draft must be erased'});
    const canceled = p.waitForEvent('dialog');
    await p.evaluate(() => history.back());
    expect((await canceled).message()).toContain('chưa lưu');
    await expect(p).toHaveURL(new RegExp(`${issueRoute(n)}$`));
    await expect(reason(p)).toHaveValue('Synthetic dirty draft must be erased');
    // Native beforeunload exists before reloading and dismiss preserves the draft.
    const unload = p.waitForEvent('dialog');
    await p.evaluate(() => location.reload());
    expect((await unload).type()).toBe('beforeunload');
    await expect(reason(p)).toHaveValue('Synthetic dirty draft must be erased');
    dialogAction.set(p, 'accept');
    await p.getByRole('link', {name: 'Về thành viên & quyền', exact: true}).click();
    await expect(p).toHaveURL(new RegExp(`/workspace/${org}/members$`));
    await open(p, n + 10);
    await expect(reason(p)).toHaveValue('');
    await fill(p, {text: 'Synthetic route-reuse reason'});
    // Keep the actual panel node: both account routes must reuse this component.
    await p.locator('section[aria-labelledby="grant-title"]').evaluate(el => { el.dataset.agmReuse = 'B05'; });
    dialogAction.set(p, 'dismiss');
    const reuseCanceled = p.waitForEvent('dialog');
    await spa(p, issueRoute(n));
    expect((await reuseCanceled).message()).toContain('chưa lưu');
    await expect(p).toHaveURL(new RegExp(`${issueRoute(n + 10)}$`));
    await expect(reason(p)).toHaveValue('Synthetic route-reuse reason');
    await expect(p.getByTestId('grant-recipient')).toContainText(`Grant Management Target ${n + 10}`);
    await expect(p.locator('section[aria-labelledby="grant-title"]')).toHaveAttribute('data-agm-reuse', 'B05');
    expect(writes).toHaveLength(0);

    dialogAction.set(p, 'accept');
    const reuseAccepted = p.waitForEvent('dialog');
    const changed = await read(p, contextPath(n), () => spa(p, issueRoute(n)));
    expect((await reuseAccepted).message()).toContain('chưa lưu');
    expect(changed.status()).toBe(200);
    await expect(p).toHaveURL(new RegExp(`${issueRoute(n)}$`));
    await expect(p.getByTestId('grant-recipient')).toContainText(`Grant Management Target ${n}`);
    await expect(p.locator('section[aria-labelledby="grant-title"]')).toHaveAttribute('data-agm-reuse', 'B05');
    await expect(reason(p)).toHaveValue('');
    await expect(risk(p)).toHaveValue('');
    await expect(duration(p)).toHaveValue('');
    await expect(ack(p)).toHaveCount(0);
    await expect(p.locator('button[aria-pressed="true"]')).toHaveCount(0);
    await expect(p.locator('main')).not.toContainText('Synthetic route-reuse reason');
    await expect(p.locator('main')).not.toContainText(`Grant Management Target ${n + 10}`);
    dialogAction.set(p, 'dismiss');
    expect(writes).toHaveLength(0);
  });

  test('AGM-B06 real committed lost ISSUE reply retries identical key/body exactly once', async ({page: p}, info) => {
    const n = index(info) + 6;
    await open(p, n);
    await fill(p, {text: 'Synthetic committed issue with lost response'});
    await review(p, n);
    const captured = [];
    let persisted;
    await p.route(`**${root(n)}`, async interception => {
      if (interception.request().method() !== 'POST') return interception.continue();
      captured.push({body: interception.request().postData(), key: interception.request().headers()['idempotency-key']});
      const actual = await interception.fetch();
      if (captured.length === 1) {
        expect(actual.status()).toBe(201);
        persisted = (await actual.json()).data;
        return interception.abort('failed');
      }
      expect(actual.status()).toBe(200);
      await interception.fulfill({response: actual});
    });
    try {
      await ack(p).check();
      await button(p, 'Cấp quyền duyệt').click();
      await expect(unknown(p)).toBeVisible();
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeEnabled();
      await expect(success(p)).toHaveCount(0);
      await shot(p, info, 'unknown');
      const warning = p.waitForEvent('dialog');
      await p.getByRole('link', {name: 'Về thành viên & quyền', exact: true}).click();
      expect((await warning).message()).toContain('mất khóa');
      await expect(p).toHaveURL(new RegExp(`${issueRoute(n)}$`));
      const stored = await p.evaluate(() => [Object.entries(localStorage), Object.entries(sessionStorage)]);
      expect(JSON.stringify(stored)).not.toContain(captured[0].key);
      expect(JSON.stringify(stored)).not.toContain('Synthetic committed issue with lost response');
      expect((await data(p, `${root(n)}?status=ALL&page=0`)).items[0].id).toBe(persisted.grantId);
      await button(p, 'Gửi lại đúng yêu cầu này').click();
      await expect(success(p)).toBeVisible();
      expect(captured).toHaveLength(2);
      expect(captured[1]).toEqual(captured[0]);
      const h = await history(p, n, persisted.grantId);
      expect(h.total).toBe(1);
      expect(h.items[0].id).toBe(persisted.eventId);
      await expect(p.locator('main')).toContainText(persisted.eventId);
    } finally { await p.unroute(`**${root(n)}`); }
  });

  test('AGM-B07 committed malformed REVOKE reply plus null receipt/advanced state stays unknown', async ({page: p}, info) => {
    const n = index(info) + 7;
    const seeded = await issue(p, n);
    await open(p, n, seeded.result.grantId);
    await reason(p).fill('Synthetic committed revoke with malformed response');
    await review(p, n, seeded.result.grantId);
    let persisted;
    let captured;
    const endpoint = `${root(n)}/${seeded.result.grantId}/revoke`;
    await p.route(`**${endpoint}`, async interception => {
      captured = {body: interception.request().postData(), key: interception.request().headers()['idempotency-key']};
      const actual = await interception.fetch();
      expect(actual.status()).toBe(201);
      persisted = (await actual.json()).data;
      await interception.fulfill({response: actual, json: {data: {unexpected: true}}});
    });
    await ack(p).check();
    await button(p, 'Thu hồi grant này').click();
    await expect(unknown(p)).toBeVisible();
    await p.unroute(`**${endpoint}`);
    const advanced = await issue(p, n, {level: 'LOW'});
    expect(advanced.result.revision).toBe(persisted.revision + 1);
    const receiptPattern = `**${root(n)}/management-receipts/*?*`;
    await p.route(receiptPattern, async interception => {
      expect(new URL(interception.request().url()).pathname).toBe(`${root(n)}/management-receipts/${captured.key}`);
      expect(new URL(interception.request().url()).searchParams.get('operation')).toBe('REVOKE');
      const actual = await interception.fetch();
      expect(actual.status()).toBe(200);
      expect((await actual.json()).data.eventId).toBe(persisted.eventId);
      await interception.fulfill({response: actual, json: {data: null}});
    });
    try {
      await unknown(p).click();
      await expect(p.locator('main')).toContainText(/Kết quả vẫn chưa\s+xác định/);
      await expect(success(p)).toHaveCount(0);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeEnabled();
      // Focus may legitimately recover by GET while Playwright clicks the retry.
      // Keep receipts null until the identical real POST replay has completed.
      const retry = responseFor(p, endpoint, 'POST');
      await button(p, 'Gửi lại đúng yêu cầu này').click();
      const r = await retry;
      expect(r.status()).toBe(200);
      expect(r.request().postData()).toBe(captured.body);
      expect(r.request().headers()['idempotency-key']).toBe(captured.key);
      expect((await r.json()).data).toEqual(persisted);
      await expect(success(p)).toBeVisible();
      expect((await history(p, n, persisted.grantId)).total).toBe(2);
    } finally { await p.unroute(receiptPattern); }
  });

  test('AGM-B08 denied ambiguous replay erases private data; restored ADMIN recovers by GET only', async ({page: p}, info) => {
    const n = index(info) + 8;
    const actorId = account(({desktop: 3, tablet: 4, mobile: 5})[info.project.name]);
    const secret = 'Synthetic denied-replay private reason';
    const writes = observeWrites(p);
    await open(p, n);
    await fill(p, {text: secret});
    await review(p, n);
    let persisted;
    const pattern = `**${root(n)}`;
    await p.route(pattern, async interception => {
      if (interception.request().method() !== 'POST') return interception.continue();
      const actual = await interception.fetch();
      expect(actual.status()).toBe(201);
      persisted = (await actual.json()).data;
      await interception.abort('failed');
    });
    await ack(p).check();
    await button(p, 'Cấp quyền duyệt').click();
    await expect(unknown(p)).toBeVisible();
    await p.unroute(pattern);
    try {
      sql(`UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id='${actorId}';`);
      const denied = responseFor(p, root(n), 'POST');
      await button(p, 'Gửi lại đúng yêu cầu này').click();
      expect((await denied).status()).toBe(403);
      await noPrivate(p, [secret, `Grant Management Target ${n}`, 'Grant Management Draft Team']);
      await expect(p.locator('main')).toContainText(/chưa xác định/);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toHaveCount(0);
      await shot(p, info, 'denied');
    } finally {
      sql(`UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id='${actorId}';`);
    }
    const before = writes.length;
    await unknown(p).click();
    await expect(success(p)).toBeVisible();
    expect(writes.length).toBe(before);
    await expect(p.locator('main')).toContainText(persisted.eventId);
    expect((await history(p, n, persisted.grantId)).total).toBe(1);
  });

  test('AGM-B09 shared-session account switch suppresses a late real POST and drops correlation', async ({page: p}, info) => {
    const n = index(info) + 9;
    const secret = 'Synthetic old-account private reason';
    await open(p, n);
    await fill(p, {text: secret});
    await review(p, n);
    const arrived = barrier(), release = barrier(), finished = barrier();
    let persisted;
    const pattern = `**${root(n)}`;
    await p.route(pattern, async interception => {
      if (interception.request().method() !== 'POST') return interception.continue();
      try {
        const actual = await interception.fetch();
        expect(actual.status()).toBe(201);
        persisted = (await actual.json()).data;
        arrived.resolve();
        await release.promise;
        await interception.fulfill({response: actual});
      } catch (e) {
        if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e;
      } finally { finished.resolve(); }
    });
    const sibling = await p.context().newPage();
    sibling.on('dialog', async d => { await d.dismiss(); });
    try {
      await ack(p).check();
      await button(p, 'Cấp quyền duyệt').click();
      await arrived.promise;
      await sibling.goto('/workspace');
      await button(sibling, 'Đăng xuất').click();
      await expect(sibling).toHaveURL(/\/login$/);
      await login(sibling, 'admin');
      // Focus is an explicit identity-revalidation boundary after shared-session change.
      const revalidated = responseFor(p, '/api/v1/auth/session');
      await p.bringToFront();
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      const session = await revalidated;
      expect(session.status()).toBe(200);
      expect((await session.json()).data.id).not.toBe(persisted.actorId);
      await noPrivate(p, [secret, `Grant Management Target ${n}`, persisted.eventId]);
      release.resolve();
      await finished.promise;
      await noPrivate(p, [secret, `Grant Management Target ${n}`, persisted.eventId]);
      await expect(unknown(p)).toHaveCount(0);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toHaveCount(0);
    } finally {
      release.resolve();
      await p.unroute(pattern);
      await sibling.close();
    }
  });

  test('AGM-B10 strict private DTO rejection, real retry and late reused-route read after Back', async ({page: p}, info) => {
    const n = index(info) + 10;
    const pattern = `**${contextPath(n)}*`;
    for (const mode of ['scope', 'extra', 'revision', 'recipient', 'blockers']) {
      await p.route(pattern, async interception => {
        const actual = await interception.fetch();
        expect(actual.status()).toBe(200);
        const body = await actual.json();
        if (mode === 'scope') body.data.organizationId = foreign;
        if (mode === 'extra') body.data.recipient.email = 'SYNTHETIC_PRIVATE_EXTRA@example.test';
        if (mode === 'revision') body.data.revision = '0';
        if (mode === 'recipient') body.data.recipient.accountId = account(99);
        if (mode === 'blockers') body.data.blockers = ['DUPLICATE_SCOPE'];
        await interception.fulfill({response: actual, json: body});
      });
      try {
        await p.goto(issueRoute(n));
        await expect(p.getByRole('alert')).toBeVisible();
        await noPrivate(p, [`Grant Management Target ${n}`, 'SYNTHETIC_PRIVATE_EXTRA@example.test']);
      } finally { await p.unroute(pattern); }
    }
    await open(p, n);
    await expect(reason(p)).toHaveValue('');
    // Two new-grant routes share one Angular component. Delay the forward request,
    // then Back must install the earlier route's actual context, ignoring old data.
    await read(p, contextPath(n + 10), () => spa(p, issueRoute(n + 10)));
    await expect(p).toHaveURL(new RegExp(`${issueRoute(n + 10)}$`));
    await expect(reason(p)).toBeVisible();
    const back = await read(p, contextPath(n), () => p.evaluate(() => history.back()));
    expect(back.status()).toBe(200);
    await expect(p).toHaveURL(new RegExp(`${issueRoute(n)}$`));
    await expect(reason(p)).toBeVisible();
    await expect(p.locator('section[aria-labelledby="grant-title"]')).toHaveAttribute('aria-busy', 'false');
    let receivedLateResponse = false;
    const release = barrier(), finished = barrier();
    const latePattern = `**${contextPath(n + 10)}*`;
    await p.route(latePattern, async interception => {
      try {
        const actual = await interception.fetch();
        receivedLateResponse = true;
        await release.promise;
        await interception.fulfill({response: actual});
      } catch (e) {
        if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e;
      } finally { finished.resolve(); }
    });
    try {
      await p.evaluate(() => history.forward());
      await expect.poll(() => receivedLateResponse, {timeout: 15000, message: 'Forward route must reach the real delayed context response'}).toBe(true);
      const restored = await read(p, contextPath(n), () => p.evaluate(() => history.back()));
      expect(restored.status()).toBe(200);
      await expect(p).toHaveURL(new RegExp(`${issueRoute(n)}$`));
      await expect(p.locator('main')).toContainText(`Grant Management Target ${n}`);
      release.resolve();
      await finished.promise;
      await expect(p.locator('main')).not.toContainText(`Grant Management Target ${n + 10}`);
      await expect(reason(p)).toHaveValue('');
      await overflow(p);
    } finally { release.resolve(); await p.unroute(latePattern); }
  });
  test('AGM-B11 late real history denial preserves dispatched REVOKE receipt recovery', async ({page: p}, info) => {
    const n = index(info) + 11;
    const actorId = account(({desktop: 3, tablet: 4, mobile: 5})[info.project.name]);
    const secret = 'Synthetic late-history-denial private revoke reason';
    const seeded = await issue(p, n);
    const historyPath = `${root(n)}/${seeded.result.grantId}/management-history`;
    const initialHistory = responseFor(p, historyPath);
    await open(p, n, seeded.result.grantId);
    expect((await initialHistory).status()).toBe(200);
    await expect(p.getByTestId('grant-history-event')).toHaveCount(1);
    await reason(p).fill(secret);
    const historyArrived = barrier(), releaseHistory = barrier(), historyFinished = barrier();
    const postCommitted = barrier(), releasePost = barrier(), postFinished = barrier();
    const historyPattern = `**${historyPath}?*`;
    const endpoint = `${root(n)}/${seeded.result.grantId}/revoke`;
    const postPattern = `**${endpoint}`;
    const writes = observeWrites(p);
    let persisted;
    await p.route(historyPattern, async interception => {
      try {
        historyArrived.resolve();
        await releaseHistory.promise;
        const actual = await interception.fetch();
        expect(actual.status()).toBe(403);
        await interception.fulfill({response: actual});
      } finally { historyFinished.resolve(); }
    });
    await p.route(postPattern, async interception => {
      try {
        const actual = await interception.fetch();
        expect(actual.status()).toBe(201);
        persisted = (await actual.json()).data;
        postCommitted.resolve();
        await releasePost.promise;
        await interception.fulfill({response: actual});
      } catch (e) {
        if (!/closed|cancel|invalid interception|already handled/i.test(String(e))) throw e;
      } finally { postFinished.resolve(); }
    });
    try {
      await review(p, n, seeded.result.grantId);
      await historyArrived.promise;
      await ack(p).check();
      await button(p, 'Thu hồi grant này').click();
      await postCommitted.promise;
      expect(persisted).toMatchObject({operation: 'REVOKE', grantId: seeded.result.grantId, actorId});
      sql(`UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id='${actorId}';`);
      const denial = responseFor(p, historyPath);
      releaseHistory.resolve();
      expect((await denial).status()).toBe(403);
      await historyFinished.promise;
      await noPrivate(p, [secret, `Grant Management Target ${n}`, 'Grant Management Active Team']);
      await expect(unknown(p)).toBeVisible();
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toHaveCount(0);
      expect(writes).toHaveLength(1);

      sql(`UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id='${actorId}';`);
      const recoveryRequests = [];
      const recordRecovery = r => {
        if (new URL(r.url()).pathname.startsWith(root(n))) recoveryRequests.push(r);
      };
      p.on('request', recordRecovery);
      try {
        const receiptPath = `${root(n)}/management-receipts/${persisted.idempotencyKey}`;
        const recovered = await read(p, receiptPath, () => unknown(p).click());
        expect(recovered.status()).toBe(200);
        expect(new URL(recovered.url()).searchParams.get('operation')).toBe('REVOKE');
        expect((await recovered.json()).data).toEqual(persisted);
        await expect(success(p)).toBeVisible();
        await expect(p.getByTestId('grant-result')).toContainText(persisted.eventId);
        expect(recoveryRequests.map(r => [r.method(), new URL(r.url()).pathname])).toEqual([['GET', receiptPath]]);
        releasePost.resolve();
        await postFinished.promise;
        await expect(success(p)).toBeVisible();
        await expect(p.getByTestId('grant-result')).toContainText(persisted.eventId);
        await expect(unknown(p)).toHaveCount(0);
        expect(writes).toHaveLength(1);
      } finally { p.off('request', recordRecovery); }
    } finally {
      try {
        sql(`UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id='${actorId}';`);
      } finally {
        releaseHistory.resolve();
        releasePost.resolve();
        await p.unroute(historyPattern);
        await p.unroute(postPattern);
      }
    }
  });
});
