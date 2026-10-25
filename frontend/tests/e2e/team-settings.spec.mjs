import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

// Labels/hooks checked against the parallel FE HTML; no browser execution claimed.
const numbers = {desktop: 2, tablet: 3, mobile: 4};
const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const field = (p, name) => p.getByLabel(name, {exact: true});
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
function scope(info) {
  const n = numbers[info.project.name];
  if (!n)
    throw new Error(
      `Unsupported settings fixture project: ${info.project.name}`,
    );
  const org = uuid('50600000', n),
    team = uuid('50610000', n * 100 + 1);
  return {
    n,
    org,
    team,
    api: `/api/v1/organizations/${org}/teams/${team}/settings`,
    url: `/workspace/${org}/teams/${team}/settings`,
  };
}
async function settle(p, path, action, method = 'GET') {
  const response = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === method,
  );
  await action();
  return response;
}
async function login(p, info, kind = 'admin') {
  await p.goto('/login');
  await field(p, 'Email').fill(
    `settings-${info.project.name}-${kind}@example.test`,
  );
  await field(p, 'Mật khẩu').fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  const s = scope(info);
  const response = await settle(p, s.api, () => p.goto(s.url));
  expect(response.status()).toBe(kind === 'member' ? 403 : 200);
  if (kind !== 'member')
    await expect(p.getByTestId('team-settings-edit')).toBeVisible();
  return s;
}
async function shot(p, info, state) {
  await expect(p.locator('body')).not.toContainText('settings-native-');
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const directory = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(directory, {recursive: true});
  await p.screenshot({
    path: join(directory, `team-settings-${info.project.name}-${state}.png`),
    fullPage: true,
  });
}
async function tab(p, name) {
  await button(p, name).click();
}
async function cancelReload(p) {
  const cancelled = p.waitForEvent('dialog').then(async dialog => {
    expect(dialog.type()).toBe('beforeunload');
    await dialog.dismiss();
  });
  await p.evaluate(() => location.reload());
  await cancelled;
}
async function guardedBack(p, accept) {
  const decided = p.waitForEvent('dialog').then(async dialog => {
    expect(dialog.type()).toBe('confirm');
    expect(dialog.message()).toContain('chưa');
    if (accept) await dialog.accept();
    else await dialog.dismiss();
  });
  await p.evaluate(() => history.back());
  await decided;
}
async function review(p) {
  await button(p, 'Xem lại thay đổi').click();
  await expect(p.getByTestId('team-settings-review')).toBeVisible();
  const ack = field(p, 'Tôi đã xem lại thay đổi cấu hình Team.');
  await expect(ack).not.toBeChecked();
  await expect(button(p, 'Lưu thay đổi')).toBeDisabled();
  await ack.check();
}
async function edit(p, info) {
  await field(p, 'Team name').fill(`Settings saved ${info.project.name}`);
  await field(p, 'Purpose').fill('Real reviewed settings');
  await field(p, 'Description').fill('Exact description  ');
}

test.describe('team settings independent QC', () => {
  test('real info, membership, manager and saved limits review commits once and reloads', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const posts = [];
    p.on('request', r => {
      if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
        posts.push(r);
    });
    await shot(p, info, 'information');
    await edit(p, info);
    await shot(p, info, 'information-dirty');
    await tab(p, 'Thành viên & Manager');
    await shot(p, info, 'members');
    const removable = p
      .getByTestId('team-settings-member')
      .filter({hasText: 'Settings Agent 002'});
    await removable.getByRole('checkbox').uncheck();
    const protectedMember = p
      .getByTestId('team-settings-member')
      .filter({hasText: 'Settings Agent 003'});
    await expect(protectedMember.getByRole('checkbox')).toBeDisabled();
    const candidate = p
      .getByTestId('team-settings-candidate')
      .filter({hasText: 'Settings Agent 004'});
    await candidate.getByRole('checkbox').check();
    await field(p, 'Manager').selectOption({
      label: 'Settings Agent 004 · MANAGER · DRAFT',
    });
    await shot(p, info, 'manager-and-members-dirty');
    await tab(p, 'Giới hạn');
    await field(p, 'Concurrency').fill('8');
    await field(p, 'Monthly budget (USD)').fill('42.50');
    await expect(p.locator('main')).toContainText(
      'Các giới hạn này là cấu hình đã lưu. Số liệu thực thi hiện chưa có.',
    );
    await shot(p, info, 'saved-limits-dirty');
    await review(p);
    await expect(p.getByTestId('team-settings-review')).toContainText(
      'Settings Agent 002',
    );
    await expect(p.getByTestId('team-settings-review')).toContainText(
      'Settings Agent 004',
    );
    await shot(p, info, 'review-acknowledged');
    expect(posts).toHaveLength(0);
    const response = await settle(
      p,
      s.api,
      () => button(p, 'Lưu thay đổi').click(),
      'POST',
    );
    expect(response.status()).toBe(201);
    const receipt = (await response.json()).data;
    expect(receipt.after).toMatchObject({
      name: `Settings saved ${info.project.name}`,
      concurrency: 8,
      monthlyBudget: 42.5,
      managerAgentId: uuid('50620000', s.n * 1000 + 4),
    });
    expect(receipt.after.agentIds).toContain(uuid('50620000', s.n * 1000 + 1));
    expect(receipt.after.agentIds).not.toContain(
      uuid('50620000', s.n * 1000 + 2),
    );
    await expect(p.getByTestId('team-settings-result')).toBeVisible();
    await shot(p, info, 'historical-receipt');
    expect(posts).toHaveLength(1);
    const loaded = await settle(p, s.api, () => p.reload());
    expect((await loaded.json()).data.team).toEqual(receipt.after);
    await shot(p, info, 'persisted-reload');
  });

  for (const mode of ['lost', 'malformed']) {
    test(`real committed ${mode} reply: manual malformed receipt and identical explicit replay`, async ({
      page: p,
    }, info) => {
      const s = await login(p, info);
      await field(p, 'Purpose').fill(
        `Receipt recovery ${mode} ${info.project.name}`,
      );
      await review(p);
      const attempts = [];
      let original;
      let firstFaultDelivered;
      const firstFault = new Promise(resolve => {
        firstFaultDelivered = resolve;
      });
      await p.route(`**${s.api}`, async route => {
        if (route.request().method() !== 'POST') return route.continue();
        attempts.push({
          body: route.request().postData(),
          key: route.request().headers()['idempotency-key'],
        });
        const actual = await route.fetch(); // Commit first; never fabricate success.
        expect(actual.status()).toBe(attempts.length === 1 ? 201 : 200);
        const receipt = (await actual.json()).data;
        if (attempts.length > 1) {
          expect(receipt).toEqual(original);
          return route.fulfill({response: actual});
        }
        original = receipt;
        if (mode === 'lost') await route.abort('failed');
        else await route.fulfill({response: actual, body: '{malformed'});
        firstFaultDelivered();
      });
      await button(p, 'Lưu thay đổi').click();
      await firstFault;
      await expect(p.getByTestId('team-settings-unknown')).toBeVisible();
      await expect(button(p, 'Kiểm tra biên nhận')).toBeEnabled();
      await shot(p, info, `${mode}-unknown`);
      expect(attempts).toHaveLength(1);
      const pattern = `**${s.api}/receipts/*`;
      let malformedDelivered;
      const receiptFault = new Promise(resolve => {
        malformedDelivered = resolve;
      });
      await p.route(pattern, async route => {
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        expect((await actual.json()).data).toEqual(original);
        await route.fulfill({response: actual, body: '{malformed'});
        malformedDelivered();
      });
      await button(p, 'Kiểm tra biên nhận').click();
      await receiptFault;
      // UNKNOWN is already visible before this GET starts. Wait for the new
      // malformed reply to be handled before removing its route or replaying.
      await expect(
        p.getByRole('alert').filter({
          hasText: 'Không thể kiểm tra biên nhận. Kết quả vẫn chưa xác định.',
        }),
      ).toBeVisible();
      await expect(button(p, 'Kiểm tra biên nhận')).toBeEnabled();
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeEnabled();
      await expect(p.getByTestId('team-settings-unknown')).toBeVisible();
      expect(attempts).toHaveLength(1);
      await shot(p, info, `${mode}-manual-malformed-receipt`);
      await p.unroute(pattern);
      await button(p, 'Gửi lại đúng yêu cầu này').click();
      await expect(p.getByTestId('team-settings-result')).toBeVisible();
      expect(attempts).toHaveLength(2);
      expect(attempts[1]).toEqual(attempts[0]);
      await shot(p, info, `${mode}-recovered`);
    });
  }

  test('fresh preflight refuses stale source without POST; focus role loss clears private data', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let writes = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
        writes++;
    });
    await field(p, 'Purpose').fill('Pending private draft');
    await review(p);
    sql(`UPDATE teams SET updated_at=clock_timestamp() WHERE id='${s.team}';`);
    await button(p, 'Lưu thay đổi').click();
    await expect(
      p.getByRole('alert').filter({
        hasText: 'Cấu hình nguồn đã thay đổi. Bản nháp được giữ;',
      }),
    ).toBeVisible();
    await expect(p.getByTestId('team-settings-blockers')).toBeVisible();
    await shot(p, info, 'stale-source');
    expect(writes).toBe(0);
    const membership = `organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='settings-${info.project.name}-admin@example.test')`;
    try {
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE ${membership};`,
      );
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(p.getByTestId('team-settings-edit')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(
        'Pending private draft',
      );
      expect(writes).toBe(0);
      await shot(p, info, 'authority-denied');
    } finally {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
      );
    }
  });

  test('no-op, strict validation and dirty reload do not write', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let writes = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
        writes++;
    });
    await button(p, 'Xem lại thay đổi').click();
    await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
    const name = await field(p, 'Team name').inputValue();
    for (const value of ['', 'x'.repeat(256)]) {
      await field(p, 'Team name').fill(value);
      await button(p, 'Xem lại thay đổi').click();
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
    }
    await field(p, 'Team name').fill(name);
    await tab(p, 'Giới hạn');
    const validConcurrency = await field(p, 'Concurrency').inputValue();
    const validBudget = await field(p, 'Monthly budget (USD)').inputValue();
    for (const [label, value] of [
      ['Concurrency', '0'],
      ['Concurrency', '1001'],
      ['Concurrency', '1.5'],
      ['Monthly budget (USD)', '-1'],
      ['Monthly budget (USD)', '1.001'],
    ]) {
      await field(p, 'Concurrency').fill(validConcurrency);
      await field(p, 'Monthly budget (USD)').fill(validBudget);
      await field(p, label).fill(value);
      await button(p, 'Xem lại thay đổi').click();
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
    }
    p.once('dialog', d => d.accept());
    await settle(p, s.api, () => p.reload());
    await field(p, 'Purpose').fill('Dirty reload draft');
    await cancelReload(p);
    await expect(field(p, 'Purpose')).toHaveValue('Dirty reload draft');
    p.once('dialog', d => d.accept());
    await settle(p, s.api, () => p.reload());
    await expect(field(p, 'Purpose')).not.toHaveValue('Dirty reload draft');
    expect(writes).toBe(0);
  });

  test('ARCHIVED and ACTIVE restrictions and foreign identity privacy', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    for (const [suffix, status] of [
      [2, 'ACTIVE'],
      [3, 'ARCHIVED'],
    ]) {
      const team = uuid('50610000', s.n * 100 + suffix);
      const api = s.api.replace(s.team, team);
      const r = await settle(p, api, () => p.goto(s.url.replace(s.team, team)));
      const data = (await r.json()).data;
      expect(data.team.status).toBe(status);
      expect(data.canChangeMembership).toBe(false);
      if (status === 'ARCHIVED') {
        expect(data.canEdit).toBe(false);
        await expect(p.getByTestId('team-settings-blockers')).toBeVisible();
        await shot(p, info, 'archived-blocked');
      } else {
        await tab(p, 'Thành viên & Manager');
        await expect(field(p, 'Manager')).toBeDisabled();
      }
    }
    const foreignTeam = uuid('50610000', 501);
    const r = await settle(p, s.api.replace(s.team, foreignTeam), () =>
      p.goto(s.url.replace(s.team, foreignTeam)),
    );
    expect(r.status()).toBe(404);
    await expect(p.getByTestId('team-settings-member')).toHaveCount(0);
    await expect(p.getByTestId('team-settings-candidate')).toHaveCount(0);
    await expect(p.locator('main')).not.toContainText('Settings Agent');
  });

  test('late candidate reply cannot restore private data after role loss', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let release, observed;
    const gate = new Promise(resolve => {
      release = resolve;
    });
    const received = new Promise(resolve => {
      observed = resolve;
    });
    await p.route(`**${s.api}/candidates*`, async route => {
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      observed();
      await gate;
      await route.fulfill({response: actual});
    });
    const membership = `organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='settings-${info.project.name}-admin@example.test')`;
    try {
      await tab(p, 'Thành viên & Manager');
      await received;
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE ${membership};`,
      );
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(p.getByTestId('team-settings-edit')).toHaveCount(0);
      const delivered = p.waitForEvent('requestfinished', {
        predicate: request =>
          new URL(request.url()).pathname === `${s.api}/candidates`,
      });
      release();
      await delivered;
      await p.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      await expect(p.getByTestId('team-settings-candidate')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText('Settings Agent');
    } finally {
      release();
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
      );
    }
  });

  test('actual candidate pagination and literal search do not write', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let writes = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
        writes++;
    });
    const firstResponse = await settle(p, `${s.api}/candidates`, () =>
      tab(p, 'Thành viên & Manager'),
    );
    const first = (await firstResponse.json()).data;
    expect(first.items).toHaveLength(20);
    const nextResponse = await settle(p, `${s.api}/candidates`, () =>
      button(p, 'Tiếp').click(),
    );
    const next = (await nextResponse.json()).data;
    expect(next.page).toBe(1);
    expect(new Set([...first.items, ...next.items].map(x => x.id)).size).toBe(
      first.total,
    );
    await expect(button(p, 'Tiếp')).toBeDisabled();
    await field(p, 'Tìm Agent').fill('%_');
    const searched = await settle(p, `${s.api}/candidates`, () =>
      button(p, 'Tìm kiếm').click(),
    );
    expect((await searched.json()).data.total).toBe(0);
    await expect(p.getByTestId('team-settings-candidate')).toHaveCount(0);
    expect(writes).toBe(0);
  });

  test('Workspace Settings routerLink, dirty native Back/reload and reused identity guard', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const workspace = s.url.replace(/settings$/, 'workspace');
    await p.goto(workspace);
    const link = p.getByRole('link', {name: 'Settings', exact: true});
    await expect(link).toHaveAttribute('href', s.url);
    await settle(p, s.api, () => link.click());
    await field(p, 'Purpose').fill('Private guarded Back draft');
    await guardedBack(p, false);
    await expect(p).toHaveURL(new RegExp(`/teams/${s.team}/settings$`));
    await expect(field(p, 'Purpose')).toHaveValue('Private guarded Back draft');
    await cancelReload(p);
    await expect(field(p, 'Purpose')).toHaveValue('Private guarded Back draft');
    await guardedBack(p, true);
    await expect(p).toHaveURL(workspace);
    await settle(p, s.api, () => link.click());
    await field(p, 'Purpose').fill('Private reused identity draft');
    const otherTeam = uuid('50610000', s.n * 100 + 4);
    const otherUrl = s.url.replace(s.team, otherTeam);
    p.once('dialog', d => d.dismiss());
    await pushSpaHistory(p, otherUrl);
    await expect(p).toHaveURL(new RegExp(`/teams/${s.team}/settings$`));
    await expect(field(p, 'Purpose')).toHaveValue(
      'Private reused identity draft',
    );
    p.once('dialog', d => d.accept());
    await settle(p, s.api.replace(s.team, otherTeam), () =>
      pushSpaHistory(p, otherUrl),
    );
    await expect(p).toHaveURL(otherUrl);
    await expect(field(p, 'Purpose')).not.toHaveValue(
      'Private reused identity draft',
    );
  });

  test('shared-session account swap erases private state before a held committed reply', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const secret = `Private old actor draft ${info.project.name}`;
    await field(p, 'Purpose').fill(secret);
    await review(p);
    let release, arrived, finished;
    const gate = new Promise(resolve => {
      release = resolve;
    });
    const received = new Promise(resolve => {
      arrived = resolve;
    });
    const completed = new Promise(resolve => {
      finished = resolve;
    });
    let receipt,
      posts = 0;
    const pattern = `**${s.api}`;
    await p.route(pattern, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      posts++;
      try {
        const actual = await route.fetch();
        expect(actual.status()).toBe(201);
        receipt = (await actual.json()).data;
        arrived();
        await gate;
        await route.fulfill({response: actual});
      } catch (e) {
        if (
          !/closed|cancel|invalid interception|already handled/i.test(String(e))
        )
          throw e;
      } finally {
        finished();
      }
    });
    const sibling = await p.context().newPage();
    const privateGone = async () => {
      await expect(p.getByTestId('team-settings-edit')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-result')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(secret);
      await expect(p.locator('main')).not.toContainText(receipt.eventId);
      await expect(p.locator('input[name="acknowledgement"]')).toHaveCount(0);
    };
    try {
      await button(p, 'Lưu thay đổi').click();
      await received;
      const csrf = (
        await (await sibling.request.get('/api/v1/auth/csrf')).json()
      ).data;
      const logout = await sibling.request.post('/api/v1/auth/logout', {
        headers: {[csrf.headerName]: csrf.token},
      });
      expect([200, 204]).toContain(logout.status());
      await login(sibling, info, 'other');
      const session = await settle(p, '/api/v1/auth/session', () =>
        p.evaluate(() => window.dispatchEvent(new Event('focus'))),
      );
      expect(session.status()).toBe(200);
      expect((await session.json()).data.id).not.toBe(receipt.actorId);
      await privateGone();
      const delivered = p.waitForEvent('requestfinished', {
        predicate: request =>
          request.method() === 'POST' &&
          new URL(request.url()).pathname === s.api,
      });
      release();
      await completed;
      await delivered;
      await p.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      await privateGone();
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
      expect(posts).toBe(1);
    } finally {
      release();
      await p.unroute(pattern);
      await sibling.close();
    }
  });

  test('UNKNOWN role loss retains opaque GET-only recovery after ADMIN restoration', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const secret = `Opaque recovery private purpose ${info.project.name}`;
    await field(p, 'Purpose').fill(secret);
    await review(p);
    let receipt,
      key,
      posts = 0;
    let faultDelivered;
    const committedFault = new Promise(resolve => {
      faultDelivered = resolve;
    });
    await p.route(`**${s.api}`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      posts++;
      key = route.request().headers()['idempotency-key'];
      const actual = await route.fetch();
      expect(actual.status()).toBe(201);
      receipt = (await actual.json()).data;
      await route.abort('failed');
      faultDelivered();
    });
    const membership = `organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='settings-${info.project.name}-admin@example.test')`;
    try {
      await button(p, 'Lưu thay đổi').click();
      await committedFault;
      await expect(p.getByTestId('team-settings-unknown')).toBeVisible();
      await expect(button(p, 'Kiểm tra biên nhận')).toBeEnabled();
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE ${membership};`,
      );
      await settle(p, `/api/v1/auth/workspaces/${s.org}`, () =>
        p.evaluate(() => window.dispatchEvent(new Event('focus'))),
      );
      await expect(p.getByTestId('team-settings-edit')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(secret);
      await expect(p.locator('main')).not.toContainText(receipt.eventId);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
      );
      const recovered = await settle(p, `${s.api}/receipts/${key}`, () =>
        button(p, 'Kiểm tra biên nhận').click(),
      );
      expect(recovered.status()).toBe(200);
      expect((await recovered.json()).data).toEqual(receipt);
      await expect(p.getByTestId('team-settings-result')).toBeVisible();
      expect(posts).toBe(1);
    } finally {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
      );
    }
  });

  test('real read faults clear private DOM and acknowledgement; GET retry preserves authority', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let posts = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && new URL(r.url()).pathname === s.api) posts++;
    });
    for (const mode of ['503', 'malformed']) {
      await field(p, 'Purpose').fill(`Private read failure ${mode}`);
      await review(p);
      await button(p, 'Quay lại chỉnh sửa').click();
      await expect(p.locator('input[name="acknowledgement"]')).toHaveCount(0);
      const pattern = `**${s.api}`;
      await p.route(pattern, async route => {
        if (route.request().method() !== 'GET') return route.continue();
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        await route.fulfill(
          mode === '503'
            ? {response: actual, status: 503, json: {}}
            : {response: actual, body: '{malformed'},
        );
      });
      p.once('dialog', d => d.accept());
      await settle(p, s.api, () =>
        mode === '503' ? p.reload() : button(p, 'Tải lại').click(),
      );
      await expect(p.getByTestId('team-settings-edit')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-blockers')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(
        `Private read failure ${mode}`,
      );
      await expect(p.locator('main')).not.toContainText(
        'Chỉ Org Admin hiện tại được xem và sửa cài đặt.',
      );
      await p.unroute(pattern);
      const recovered = await settle(p, s.api, () =>
        button(p, 'Thử lại bằng GET').click(),
      );
      expect(recovered.status()).toBe(200);
      await expect(p.getByTestId('team-settings-edit')).toBeVisible();
      await button(p, 'Xem lại thay đổi').click();
      await expect(p.getByTestId('team-settings-review')).toHaveCount(0);
    }
    expect(posts).toBe(0);
  });

  test('accepted UNKNOWN route reuse loads the destination and ignores the old committed reply', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const secret = `Unknown old Team draft ${info.project.name}`;
    await field(p, 'Purpose').fill(secret);
    await review(p);
    let release, arrived, finished;
    const gate = new Promise(resolve => {
      release = resolve;
    });
    const received = new Promise(resolve => {
      arrived = resolve;
    });
    const completed = new Promise(resolve => {
      finished = resolve;
    });
    const pattern = `**${s.api}`;
    let receipt,
      posts = 0;
    p.on('request', request => {
      if (
        request.method() === 'POST' &&
        new URL(request.url()).pathname.endsWith('/settings')
      )
        posts++;
    });
    await p.route(pattern, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      try {
        const actual = await route.fetch();
        expect(actual.status()).toBe(201);
        receipt = (await actual.json()).data;
        arrived();
        await gate;
        await route.fulfill({response: actual});
      } finally {
        finished();
      }
    });
    try {
      await button(p, 'Lưu thay đổi').click();
      await received;
      await expect(p.getByTestId('team-settings-unknown')).toBeVisible();
      const otherTeam = uuid('50610000', s.n * 100 + 4);
      const otherUrl = s.url.replace(s.team, otherTeam);
      p.once('dialog', dialog => dialog.accept());
      const loaded = await settle(p, s.api.replace(s.team, otherTeam), () =>
        pushSpaHistory(p, otherUrl),
      );
      expect(loaded.status()).toBe(200);
      const destination = (await loaded.json()).data;
      await expect(p).toHaveURL(otherUrl);
      await expect(p.getByTestId('team-settings-edit')).toBeVisible();
      await expect(field(p, 'Team name')).toHaveValue(destination.team.name);
      await expect(field(p, 'Purpose')).toHaveValue(
        destination.team.purpose ?? '',
      );
      await expect(p.getByTestId('team-settings-unknown')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(secret);
      const delivered = p.waitForEvent('requestfinished', {
        predicate: request =>
          request.method() === 'POST' &&
          new URL(request.url()).pathname === s.api,
      });
      release();
      await completed;
      await delivered;
      await p.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      await expect(p.getByTestId('team-settings-edit')).toBeVisible();
      await expect(p.getByTestId('team-settings-result')).toHaveCount(0);
      await expect(p.getByTestId('team-settings-unknown')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(receipt.eventId);
      await expect(p.locator('main')).not.toContainText(secret);
      expect(posts).toBe(1);
      await shot(p, info, 'accepted-unknown-route');
    } finally {
      release();
      await p.unroute(pattern);
    }
  });
});
