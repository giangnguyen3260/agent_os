import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

// Dedicated CI fixtures; never reuse the baseline Dashboard organizations.
const org = '12121212-1212-4212-8212-121212121212';
const foreign = '13131313-1313-4313-8313-131313131313';
const team = n => `a1000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const manager = 'a2000000-0000-4000-8000-000000000001';
const url = `/workspace/${org}/teams`;
const api = `/api/v1/organizations/${org}/teams/directory`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const rows = p => p.getByTestId('directory-row');
const surface = p => p.getByTestId('team-directory');
const responseFor = (p, path = api) => p.waitForResponse(r =>
  new URL(r.url()).pathname === path && r.request().method() === 'GET');
async function settle(p, action) {
  const [response] = await Promise.all([responseFor(p), action()]);
  await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
  return response;
}
async function login(p, member = false) {
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(`team-directory-${member ? 'member' : 'admin'}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  await settle(p, () => p.goto(url));
  await expect(rows(p)).toHaveCount(20);
}
async function filter(p, query, status = '') {
  await p.getByLabel('Search teams', {exact: true}).fill(query);
  await p.getByLabel('Status', {exact: true}).selectOption(status);
  return settle(p, () => button(p, 'Apply filters').click());
}
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
    cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8',
    timeout: 15000, stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}

test.describe('real Team directory', () => {
  test('fixed pages, literal name/purpose filters, lifecycle and browser Back', async ({page: p}) => {
    await login(p);
    const firstPage = await rows(p).allTextContents();
    await expect(button(p, 'Previous')).toBeDisabled();
    const next = await settle(p, () => button(p, 'Next').click());
    expect((await next.json()).data).toMatchObject({page: 1, size: 20, total: 25});
    await expect(rows(p)).toHaveCount(5);
    await expect(p).toHaveURL(/page=1/);
    await expect(button(p, 'Next')).toBeDisabled();
    await settle(p, () => p.goBack());
    await expect(rows(p)).toHaveText(firstPage);
    for (const literal of ['%', '_', '\\', '100%_ready!']) {
      const response = await filter(p, literal);
      expect((await response.json()).data.total).toBe(1);
      await expect(rows(p)).toHaveCount(1);
      await expect(rows(p)).toContainText('Team 100%_ready!');
    }
    for (const [status, total] of [['DRAFT', 9], ['ACTIVE', 8], ['ARCHIVED', 8]]) {
      const filtered = await filter(p, '', status);
      const data = (await filtered.json()).data;
      expect(data.total).toBe(total);
      expect(data.items.every(item => item.status === status)).toBe(true);
      await expect(rows(p)).toHaveCount(total);
    }
    const response = await filter(p, 'Review products', 'DRAFT');
    expect((await response.json()).data.total).toBe(5);
    await expect(rows(p)).toHaveCount(5);
    await settle(p, () => p.reload());
    await expect(p.getByLabel('Search teams', {exact: true})).toHaveValue('Review products');
    await expect(p.getByLabel('Status', {exact: true})).toHaveValue('DRAFT');
    await filter(p, 'no-such-directory-team');
    await expect(rows(p)).toHaveCount(0);
    await expect(p.getByRole('heading', {name: 'Không tìm thấy kết quả', exact: true})).toBeVisible();
    await settle(p, () => button(p, 'Clear filters').click());
    await expect(rows(p)).toHaveCount(20);
  });

  test('real aggregates, null manager, navigation and filtered task drilldowns', async ({page: p}) => {
    await login(p);
    const response = await filter(p, '100%_ready!');
    const item = (await response.json()).data.items[0];
    expect(item).toMatchObject({id: team(25), organizationId: org, status: 'DRAFT',
      managerAgentId: manager, agentCount: 3, totalTasks: 6,
      runningTasks: 2, pendingTasks: 2, reviewTasks: 1});
    expect(item.managerName).toBe('Directory Agent 1');
    await expect(rows(p)).toContainText('3 agents');
    await expect(rows(p)).toContainText('6 tasks');
    await expect(rows(p)).toContainText(item.managerName);
    for (const [state, count] of [['RUNNING', 2], ['PENDING', 2], ['REVIEW', 1]]) {
      const link = rows(p).getByRole('link', {name: new RegExp(`^${state} ${count}$`, 'i')});
      const target = new URL(await link.getAttribute('href'), p.url());
      expect(target.pathname).toBe(`/workspace/${org}/dashboard`);
      expect(target.searchParams.get('teamId')).toBe(team(25));
      expect(target.searchParams.get('state')).toBe(state);
      const [tasks] = await Promise.all([
        responseFor(p, `/api/v1/organizations/${org}/dashboard/tasks`), link.click(),
      ]);
      expect(tasks.status()).toBe(200);
      const taskData = (await tasks.json()).data;
      expect(taskData.total).toBe(count);
      expect(taskData.items.every(task => task.teamId === team(25))).toBe(true);
      const numbers = {RUNNING: [1, 2], PENDING: [3, 4], REVIEW: [5]}[state];
      expect(taskData.items.map(task => task.title).sort()).toEqual(
        numbers.map(n => `Team directory task ${n}`));
      await expect(p.getByLabel('Team', {exact: true})).toHaveValue(team(25));
      await expect(p.getByLabel('Trạng thái', {exact: true})).toHaveValue(state);
      await expect(p.getByTestId('task-row')).toHaveCount(count);
      await settle(p, () => p.goto(url + '?query=100%25_ready!'));
      await expect(rows(p)).toHaveCount(1);
    }
    // Dashboard sorts by name, unlike the directory's newest-first ordering.
    const dashboardPath = `/api/v1/organizations/${org}/dashboard`;
    const dashboardResponse = await p.request.get(dashboardPath);
    expect(dashboardResponse.status()).toBe(200);
    const dashboard = (await dashboardResponse.json()).data;
    const outside = Array.from({length: 24}, (_, i) => i + 1).find(n =>
      !dashboard.teams.items.some(item => item.id === team(n)));
    expect(outside).toBeDefined();
    const outsideName = `Team directory ${String(outside).padStart(2, '0')}`;
    await filter(p, outsideName);
    const [detailResponse, outsideTasks] = await Promise.all([
      responseFor(p, `/api/v1/organizations/${org}/teams/${team(outside)}`),
      responseFor(p, `${dashboardPath}/tasks`),
      rows(p).getByRole('link', {name: 'Running 0', exact: true}).click(),
    ]);
    expect(detailResponse.status()).toBe(200);
    expect((await detailResponse.json()).data).toMatchObject({
      id: team(outside), organizationId: org, name: outsideName,
    });
    expect(new URL(outsideTasks.url()).searchParams.get('teamId')).toBe(team(outside));
    expect(new URL(outsideTasks.url()).searchParams.get('state')).toBe('RUNNING');
    expect((await outsideTasks.json()).data).toMatchObject({total: 0, items: []});
    const selectedTeam = p.getByLabel('Team', {exact: true});
    await expect(selectedTeam).toHaveValue(team(outside));
    await expect(selectedTeam.locator('option:checked')).toHaveText(outsideName);
    await expect(selectedTeam.locator(`option[value="${team(outside)}"]`)).toHaveCount(1);
    await expect(p.getByLabel('Trạng thái', {exact: true})).toHaveValue('RUNNING');
    await expect(p.getByTestId('task-row')).toHaveCount(0);
    await settle(p, () => p.goto(url));
    const empty = await filter(p, 'Team directory 01');
    expect((await empty.json()).data.items[0]).toMatchObject({id: team(1),
      managerAgentId: null, managerName: null, agentCount: 0, totalTasks: 0,
      runningTasks: 0, pendingTasks: 0, reviewTasks: 0});
    await expect(rows(p)).toHaveCount(1);
    await rows(p).getByRole('link', {name: 'View details', exact: true}).click();
    await expect(p).toHaveURL(new RegExp(`/teams/${team(1)}$`));
  });

  test('member reads without create; revoked membership clears current rows', async ({page: p}) => {
    await login(p, true);
    await expect(p.getByRole('link', {name: 'Create Team', exact: true})).toHaveCount(0);
    try {
      sql(`DELETE FROM organization_memberships WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='team-directory-member@example.test');`);
      const [response] = await Promise.all([
        responseFor(p, `/api/v1/auth/workspaces/${org}`),
        button(p, 'Refresh').click(),
      ]);
      await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
      expect(response.status()).toBe(404);
      await expect(rows(p)).toHaveCount(0);
      await expect(p.getByRole('alert')).toBeVisible();
      await expect(p.locator('main')).not.toContainText('Team directory 01');
      expect((await p.request.get(api)).status()).toBe(403);
    } finally {
      sql(`INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${org}','MEMBER' FROM human_accounts WHERE email='team-directory-member@example.test' ON CONFLICT DO NOTHING;`);
    }
  });

  test('503, null and wrong-scope payload clear loaded rows; retry recovers', async ({page: p}) => {
    await login(p);
    for (const mode of ['503', 'null', 'scope']) {
      // Recover between failures so every variant must clear actual loaded data.
      await p.route(`**${api}*`, async route => {
        if (mode === '503') return route.fulfill({status: 503, json: {}});
        if (mode === 'null') return route.fulfill({status: 200, json: null});
        const response = await route.fetch();
        const body = await response.json();
        body.data.items[0].organizationId = foreign;
        return route.fulfill({response, json: body});
      });
      const response = await settle(p, () => button(p, 'Refresh').click());
      expect(response.status()).toBe(mode === '503' ? 503 : 200);
      await expect(rows(p)).toHaveCount(0);
      await expect(p.getByRole('alert')).toBeVisible();
      await p.unroute(`**${api}*`);
      await settle(p, () => button(p, 'Thử lại').click());
      await expect(rows(p)).toHaveCount(20);
      await expect(p.getByRole('alert')).toHaveCount(0);
    }
    await p.route(`**${api}*`, async route => {
      const response = await route.fetch();
      const body = await response.json();
      body.data.items[0].purpose = null;
      await route.fulfill({response, json: body});
    });
    await settle(p, () => button(p, 'Refresh').click());
    await expect(rows(p)).toHaveCount(20);
    await expect(rows(p).first()).toContainText('No purpose set');
    await expect(p.getByRole('alert')).toHaveCount(0);
    await p.unroute(`**${api}*`);
  });

  test('forbidden workspace and invalid URL filters never expose team rows', async ({page: p}) => {
    await login(p);
    expect((await p.request.get(`/api/v1/organizations/${foreign}/teams/directory`)).status()).toBe(403);
    for (const invalid of ['status=READY', 'page=-1', 'page=oops']) {
      await p.goto(`${url}?${invalid}`);
      await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
      await expect(p.getByRole('alert')).toContainText('Invalid directory filters');
      await expect(rows(p)).toHaveCount(0);
      await settle(p, () => button(p, 'Clear filters').click());
      await expect(rows(p)).toHaveCount(20);
    }
    await p.goto(`/workspace/${foreign}/teams`);
    await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
    await expect(p.locator('body')).not.toContainText('Team directory 01');
    await expect(p.locator('body')).not.toContainText('Foreign Private Team');
  });

  test('admin create, compact keyboard menu, readable contrast and no overflow', async ({page: p}, info) => {
    await login(p);
    if (info.project.name !== 'desktop') {
      const menu = button(p, 'Menu');
      await menu.click();
      await expect(p.getByRole('dialog', {name: 'Workspace navigation'})).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(p.getByRole('dialog', {name: 'Workspace navigation'})).not.toBeVisible();
      await expect(menu).toBeFocused();
    }
    expect(await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const create = p.getByRole('link', {name: 'Create Team', exact: true});
    await expect(create).toBeVisible();
    const contrast = await create.evaluate(element => {
      const rgb = color => color.match(/[\d.]+/g).slice(0, 3).map(Number);
      const luminance = channels => channels.map(x => x / 255).map(x =>
        x <= 0.04045 ? x / 12.92 : ((x + 0.055) / 1.055) ** 2.4)
        .reduce((sum, x, i) => sum + x * [0.2126, 0.7152, 0.0722][i], 0);
      const style = getComputedStyle(element);
      let ancestor = element;
      while (getComputedStyle(ancestor).backgroundColor === 'rgba(0, 0, 0, 0)' && ancestor.parentElement) ancestor = ancestor.parentElement;
      const a = luminance(rgb(style.color));
      const b = luminance(rgb(getComputedStyle(ancestor).backgroundColor));
      return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    });
    expect(contrast).toBeGreaterThanOrEqual(4.5);
    await create.click();
    await expect(p).toHaveURL(new RegExp(`/workspace/${org}/teams/new$`));
    await expect(p.getByRole('heading', {name: 'Create Team / Basics', exact: true})).toBeVisible();
  });
});
