import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';

// Dedicated disposable member-access fixture; no baseline organization is mutated.
const org = '50505050-5050-4050-8050-505050505050';
const foreign = '51515151-5151-4151-8151-515151515151';
const account = n => `50800000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const url = `/workspace/${org}/members`;
const api = `/api/v1/organizations/${org}/members`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const members = p => p.getByTestId('member-card');
const grants = p => p.getByTestId('approval-grant');
const panel = p => p.locator('section[aria-labelledby="access-title"]');
const responseFor = (p, path) => p.waitForResponse(r =>
  new URL(r.url()).pathname === path && r.request().method() === 'GET');
async function read(p, action, path = api) {
  const [response] = await Promise.all([responseFor(p, path), action()]);
  await expect(panel(p)).toHaveAttribute('aria-busy', 'false');
  return response;
}
async function data(p, path) {
  const response = await p.request.get(path);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
async function login(p, role = 'admin') {
  expect(process.env.E2E_PASSWORD).toBeTruthy();
  // Install before any action that could trigger a native dialog.
  p.on('dialog', async dialog => { await dialog.dismiss(); });
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(`member-access-${role}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p) {
  const response = await read(p, () => p.goto(url));
  expect(response.status()).toBe(200);
  const result = (await response.json()).data;
  await expect(members(p)).toHaveCount(result.items.length);
  return result;
}
async function filter(p, query, role = '') {
  await p.getByLabel('Tìm thành viên', {exact: true}).fill(query);
  await p.getByLabel('Vai trò tổ chức', {exact: true}).selectOption(role);
  const response = await read(p, () => button(p, 'Áp dụng bộ lọc').click());
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
async function privateClear(p, secrets) {
  await expect(members(p)).toHaveCount(0);
  await expect(grants(p)).toHaveCount(0);
  await expect(p.getByTestId('grant-member')).toHaveCount(0);
  for (const secret of secrets) await expect(p.locator('main')).not.toContainText(secret);
}
async function shot(p, info, state) {
  const directory = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(directory, {recursive: true});
  await p.screenshot({path: join(directory,
    `member-access-${info.project.name}-${state}.png`), fullPage: true});
}
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
    cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8',
    timeout: 15000, stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}

// Intentionally no project gating: every case runs in all three existing projects.
test.describe('real member access inventory', () => {
  test.describe.configure({timeout: 90000});
  const writes = new WeakMap();
  test.beforeEach(async ({page}) => {
    const observed = [];
    writes.set(page, observed);
    page.on('request', request => {
      if (new URL(request.url()).pathname.startsWith('/api/v1/organizations/') &&
        !['GET', 'HEAD', 'OPTIONS'].includes(request.method())) observed.push(request.method() + ' ' + request.url());
    });
  });
  test.afterEach(async ({page}) => { expect(writes.get(page)).toEqual([]); });
  test('admin search, literal percent, roles, stable pages, reload and Back', async ({page: p}, info) => {
    await login(p);
    const first = await open(p);
    expect(first.total).toBe(26);
    expect(first.items).toHaveLength(20);
    await expect(button(p, 'Trang trước')).toBeDisabled();
    await shot(p, info, 'members');
    const second = await read(p, () => button(p, 'Trang sau').click());
    const next = (await second.json()).data;
    expect(next.page).toBe(1);
    expect(next.items).toHaveLength(6);
    expect(next.items.every(row => !first.items.some(old => old.accountId === row.accountId))).toBe(true);
    await expect(p).toHaveURL(/page=1/);
    await read(p, () => p.reload());
    await expect(members(p)).toHaveCount(next.items.length);
    await read(p, () => p.goBack());
    await expect(members(p).locator('h2')).toHaveText(first.items.map(row =>
      row.displayName + (row.isSelf ? ' · Bạn' : '')));
    for (const role of ['ADMIN', 'MEMBER']) {
      const result = await filter(p, '', role);
      expect(result.total).toBeGreaterThan(0);
      expect(result.items.every(row => row.role === role)).toBe(true);
      await expect(members(p)).toHaveCount(result.items.length);
      await read(p, () => p.reload());
      await expect(p.getByLabel('Vai trò tổ chức', {exact: true})).toHaveValue(role);
    }
    const literal = await filter(p, '%');
    expect(literal.total).toBe(1);
    expect(literal.items[0].displayName + literal.items[0].email).toContain('%');
    await expect(members(p)).toHaveCount(1);
    const email = literal.items[0].email;
    const byEmail = await filter(p, email.toUpperCase());
    expect(byEmail.items.map(row => row.email)).toEqual([email]);
    await read(p, () => p.reload());
    await expect(p.getByLabel('Tìm thành viên', {exact: true})).toHaveValue(email.toUpperCase());
    await filter(p, 'no-such-member-access-result');
    await expect(members(p)).toHaveCount(0);
    await expect(p.getByText('Không có thành viên phù hợp với bộ lọc.', {exact: true})).toBeVisible();
    await shot(p, info, 'empty');
    await read(p, () => button(p, 'Xóa bộ lọc').click());
    await expect(members(p)).toHaveCount(20);
    expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const search = p.getByLabel('Tìm thành viên', {exact: true});
    await search.focus();
    await p.keyboard.press('Tab');
    await expect(p.getByLabel('Vai trò tổ chức', {exact: true})).toBeFocused();
  });

  test('disabled target lifecycle counts and real grant status/filter/reload', async ({page: p}, info) => {
    await login(p);
    await open(p);
    const disabled = (await filter(p, 'member-access-disabled@example.test')).items[0];
    expect(disabled).toMatchObject({accountId: account(3), enabled: false,
      grantCounts: {unexpired: 1, expired: 0, revoked: 0}});
    await expect(members(p)).toContainText('Đã vô hiệu hóa');
    const disabledPath = `${api}/${disabled.accountId}/approval-grants`;
    const disabledResponse = await read(p, () => members(p).getByRole('link', {name: 'Xem quyền duyệt Approval', exact: true}).click(), disabledPath);
    expect((await disabledResponse.json()).data.total).toBe(1);
    await expect(p.getByTestId('grant-member')).toContainText('Đã vô hiệu hóa');
    await read(p, () => p.getByRole('link', {name: 'Về thành viên & quyền', exact: true}).click());
    const result = await filter(p, '%');
    const target = result.items[0];
    expect(target).toMatchObject({accountId: account(2), enabled: true,
      grantCounts: {unexpired: 24, expired: 1, revoked: 2}});
    await expect(members(p)).toContainText(`${target.grantCounts.unexpired} chưa hết hạn`);
    await expect(members(p)).toContainText(`${target.grantCounts.expired} đã hết hạn`);
    await expect(members(p)).toContainText(`${target.grantCounts.revoked} đã thu hồi`);
    const path = `${api}/${target.accountId}/approval-grants`;
    const response = await read(p, () => members(p).getByRole('link', {name: 'Xem quyền duyệt Approval', exact: true}).click(), path);
    const all = (await response.json()).data;
    expect(all.total).toBe(Object.values(target.grantCounts).reduce((a, b) => a + b, 0));
    await expect(p.getByTestId('grant-member')).toContainText(target.email);
    await shot(p, info, 'grants');
    for (const [status, key, label] of [
      ['UNEXPIRED', 'unexpired', 'Chưa hết hạn'],
      ['EXPIRED', 'expired', 'Đã hết hạn'],
      ['REVOKED', 'revoked', 'Đã thu hồi'],
    ]) {
      await p.getByLabel('Trạng thái grant', {exact: true}).selectOption(status);
      const filtered = await read(p, () => button(p, 'Áp dụng bộ lọc').click(), path);
      const value = (await filtered.json()).data;
      expect(value.total).toBe(target.grantCounts[key]);
      expect(value.items.every(grant => grant.status === status)).toBe(true);
      await expect(grants(p)).toHaveCount(value.items.length);
      for (let i = 0; i < value.items.length; i++) {
        const grant = value.items[i];
        await expect(grants(p).nth(i)).toContainText(grant.teamName);
        await expect(grants(p).nth(i)).toContainText(grant.actionType);
        await expect(grants(p).nth(i)).toContainText(grant.riskLevel);
        await expect(grants(p).nth(i)).toContainText(label);
        expect(grant.organizationId).toBe(org);
        expect(grant.accountId).toBe(target.accountId);
        expect(grant.revokedAt !== null ? 'REVOKED' :
          Date.parse(grant.expiresAt) <= Date.parse(value.generatedAt) ? 'EXPIRED' : 'UNEXPIRED').toBe(status);
      }
      await read(p, () => p.reload(), path);
      await expect(p.getByLabel('Trạng thái grant', {exact: true})).toHaveValue(status);
      if (value.total > 20) {
        const pageTwo = await read(p, () => button(p, 'Trang sau').click(), path);
        const second = (await pageTwo.json()).data;
        expect(second.page).toBe(1);
        expect(second.items.every(row => !value.items.some(old => old.id === row.id))).toBe(true);
        await read(p, () => p.goBack(), path);
      }
    }
    await read(p, () => p.getByRole('link', {name: 'Về thành viên & quyền', exact: true}).click());
    const zero = (await data(p, api + '?query=&role=MEMBER&page=0')).items.find(row =>
      Object.values(row.grantCounts).every(count => count === 0));
    expect(zero).toBeDefined();
    await read(p, () => p.goto(`${url}/${zero.accountId}/approval-grants`), `${api}/${zero.accountId}/approval-grants`);
    await expect(grants(p)).toHaveCount(0);
    await expect(p.getByText('Chưa có grant phù hợp với bộ lọc.', {exact: true})).toBeVisible();
    expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });

  test('independent MEMBER and foreign organization deny private inventory', async ({page: p, browser}, info) => {
    await login(p);
    const loaded = await open(p);
    const viewport = p.viewportSize();
    const context = await browser.newContext({baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8080', viewport});
    try {
      const other = await context.newPage();
      await login(other, 'member');
      expect(await other.evaluate(() => innerWidth)).toBe(viewport.width);
      expect((await other.request.get(api)).status()).toBe(403);
      expect((await other.request.get(`${api}/${loaded.items[0].accountId}/approval-grants`)).status()).toBe(403);
      await other.goto(url);
      await expect(other.getByRole('alert')).toBeVisible();
      await privateClear(other, loaded.items.map(row => row.email));
      await shot(other, info, 'denied');
      await expect(members(p)).toHaveCount(20);
    } finally { await context.close(); }
    const foreignMemberPath = `${api}/${account(99)}/approval-grants`;
    expect((await p.request.get(foreignMemberPath)).status()).toBe(404);
    await read(p, () => p.goto(`${url}/${account(99)}/approval-grants`), foreignMemberPath);
    await expect(p.getByRole('alert')).toBeVisible();
    await privateClear(p, [...loaded.items.map(row => row.email), 'FOREIGN_MEMBER_PRIVATE', 'FOREIGN_TEAM_PRIVATE']);
    expect((await p.request.get(`/api/v1/organizations/${foreign}/members`)).status()).toBe(403);
    await p.goto(`/workspace/${foreign}/members`);
    await expect(p.getByRole('alert')).toBeVisible();
    await privateClear(p, loaded.items.map(row => row.email));
  });

  test('malformed and failed GETs clear members and grant metadata; retry uses backend', async ({page: p}) => {
    await login(p);
    await open(p);
    for (const path of [api, `${api}/${account(2)}/approval-grants`]) {
      if (path !== api) await read(p, () => p.goto(`${url}/${account(2)}/approval-grants`), path);
      for (const mode of ['503', 'null', 'scope', 'duplicate', 'self', ...(path === api ? [] : ['lifecycle', 'target'])]) {
        const current = await data(p, path);
        const secrets = path === api ? current.items.map(row => row.email) : [current.member.email, ...current.items.map(row => row.teamName)];
        const pattern = `**${path}?*`;
        await p.route(pattern, async route => {
          if (mode === '503') return route.fulfill({status: 503, json: {}});
          if (mode === 'null') return route.fulfill({status: 200, json: null});
          const response = await route.fetch();
          const body = await response.json();
          if (mode === 'scope') body.data.organizationId = foreign;
          if (mode === 'duplicate') {
            if (body.data.items.length > 1) body.data.items[1] = body.data.items[0];
            else body.data.total += 1;
          }
          if (mode === 'lifecycle') body.data.items[0].status = 'EXPIRED';
          if (mode === 'target') body.data.items[0].accountId = account(99);
          if (mode === 'self') {
            const member = path === api ? body.data.items[0] : body.data.member;
            member.isSelf = !member.isSelf;
          }
          return route.fulfill({response, json: body});
        });
        try {
          await read(p, () => button(p, 'Làm mới').click(), path);
          await expect(p.getByRole('alert')).toBeVisible();
          await privateClear(p, secrets);
        } finally { await p.unroute(pattern); }
        const retry = await read(p, () => button(p, 'Thử lại').click(), path);
        expect(retry.status()).toBe(200);
        await expect(p.getByRole('alert')).toHaveCount(0);
        if (path === api) await expect(members(p)).toHaveCount(current.items.length);
        else await expect(p.getByTestId('grant-member')).toContainText(current.member.email);
      }
    }
  });

  test('freshly revoked admin role clears private inventory and denies both GETs', async ({page: p}) => {
    await login(p);
    const loaded = await open(p);
    const email = 'member-access-admin@example.test';
    try {
      sql(`UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${email}');`);
      await button(p, 'Làm mới').click();
      await expect(p.getByRole('alert')).toBeVisible();
      await privateClear(p, loaded.items.map(row => row.email));
      expect((await p.request.get(api)).status()).toBe(403);
      expect((await p.request.get(`${api}/${loaded.items[0].accountId}/approval-grants`)).status()).toBe(403);
    } finally {
      sql(`UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${email}');`);
    }
    await open(p);
  });

  test('route transition clears loaded data and suppresses a late real GET', async ({page: p}) => {
    await login(p);
    const loaded = await open(p);
    let release;
    let seen;
    const held = new Promise(resolve => { release = resolve; });
    const arrived = new Promise(resolve => { seen = resolve; });
    let finished;
    const delivered = new Promise(resolve => { finished = resolve; });
    const pattern = `**${api}?*`;
    await p.route(pattern, async route => {
      try {
        const response = await route.fetch();
        seen();
        await held;
        await route.fulfill({response});
      } catch (error) {
        // Browser cancellation is expected after the route/context epoch changes.
        if (!/closed|cancel|invalid interception|already handled/i.test(String(error))) throw error;
      } finally { finished(); }
    });
    try {
      await button(p, 'Làm mới').click();
      await arrived;
      await privateClear(p, loaded.items.map(row => row.email));
      // RouterLink performs an SPA transition while the previous GET is held.
      await p.getByRole('link', {name: 'Về workspace', exact: true}).click();
      await expect(p).toHaveURL(new RegExp(`/workspace/${org}$`));
      release();
      await delivered;
      await expect(panel(p)).toHaveCount(0);
      await expect(members(p)).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(loaded.items[0].email);
    } finally { release(); await p.unroute(pattern); }
    await open(p);
  });

  test('real shared-session logout and account change clear loaded private data', async ({page: p}) => {
    await login(p);
    const loaded = await open(p);
    const sibling = await p.context().newPage();
    try {
      // A second tab uses the production logout UI and the same browser session.
      await sibling.goto('/workspace');
      await button(sibling, 'Đăng xuất').click();
      await expect(sibling).toHaveURL(/\/login$/);
      await button(p, 'Làm mới').click();
      await expect(p.getByRole('alert')).toBeVisible();
      await privateClear(p, loaded.items.map(row => row.email));
      expect((await p.request.get(api)).status()).toBe(401);
      await login(sibling, 'member');
      await p.reload();
      await expect(p.getByRole('alert')).toBeVisible();
      await privateClear(p, loaded.items.map(row => row.email));
      expect((await p.request.get(api)).status()).toBe(403);
    } finally { await sibling.close(); }
  });

  test('reused grant route ignores an older status response after browser Back', async ({page: p}) => {
    await login(p);
    const path = `${api}/${account(2)}/approval-grants`;
    await read(p, () => p.goto(`${url}/${account(2)}/approval-grants?status=UNEXPIRED`), path);
    await expect(grants(p)).toHaveCount(20);
    let release;
    let seen;
    let finished;
    const held = new Promise(resolve => { release = resolve; });
    const arrived = new Promise(resolve => { seen = resolve; });
    const delivered = new Promise(resolve => { finished = resolve; });
    const pattern = `**${path}?*`;
    await p.route(pattern, async route => {
      if (new URL(route.request().url()).searchParams.get('status') !== 'EXPIRED') return route.continue();
      try {
        const response = await route.fetch();
        seen();
        await held;
        await route.fulfill({response});
      } catch (error) {
        if (!/closed|cancel|invalid interception|already handled/i.test(String(error))) throw error;
      } finally { finished(); }
    });
    try {
      await p.getByLabel('Trạng thái grant', {exact: true}).selectOption('EXPIRED');
      await button(p, 'Áp dụng bộ lọc').click();
      await arrived;
      await privateClear(p, ['member-access-member@example.test', 'Member Access Archived Team']);
      const back = await read(p, () => p.goBack(), path);
      expect((await back.json()).data.total).toBe(24);
      await expect(grants(p)).toHaveCount(20);
      release();
      await delivered;
      await expect(p.getByLabel('Trạng thái grant', {exact: true})).toHaveValue('UNEXPIRED');
      await expect(grants(p)).toHaveCount(20);
      await expect(grants(p).first()).toContainText('Chưa hết hạn');
      await expect(p.getByRole('alert')).toHaveCount(0);
    } finally { release(); await p.unroute(pattern); }
  });

});
