import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';

const numbers = {desktop: 2, tablet: 3, mobile: 4};
const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const hook = (p, name) => p.getByTestId(`org-map-${name}`);
function scope(info) {
  const n = numbers[info.project.name];
  if (!n) throw new Error(`Unknown map fixture project ${info.project.name}`);
  const org = uuid('50700000', n);
  return {
    org,
    team: uuid('50710000', n * 100 + 1),
    api: `/api/v1/organizations/${org}/organization-map`,
    url: `/workspace/${org}/organization-map`,
    email: `map-${info.project.name}-member@example.test`,
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
async function signIn(p, email) {
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(email);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function stable(p) {
  await expect(p.locator('main')).toHaveAttribute('aria-busy', 'false');
  await expect(hook(p, 'overview')).toBeVisible();
  await expect(hook(p, 'inspector')).toBeVisible();
  await expect(hook(p, 'overview').getByRole('status')).toContainText('Teams');
  await expect(hook(p, 'inspector').getByRole('status')).toContainText(
    'Agents',
  );
}
async function login(p, info) {
  const s = scope(info);
  await signIn(p, s.email);
  await p.goto(s.url);
  await stable(p);
  return s;
}
async function change(p, action, path) {
  const waiting = p.waitForResponse(
    r => r.request().method() === 'GET' && new URL(r.url()).pathname === path,
  );
  await action();
  const response = await waiting;
  expect(response.status()).toBe(200);
  await stable(p);
  return (await response.json()).data;
}
async function select(p, s) {
  return change(p, () => button(p, 'Map Team 001').click(), `${s.api}/agents`);
}
async function privateGone(p) {
  for (const name of ['overview', 'inspector', 'team', 'agent', 'unassigned'])
    await expect(hook(p, name)).toHaveCount(0);
  await expect(p.locator('body')).not.toContainText('Map Agent');
  await expect(p.locator('body')).not.toContainText('Map Team');
}
async function shot(p, info, state, denied = false) {
  if (denied) {
    await expect(p.locator('main')).toHaveAttribute('aria-busy', 'false');
    await privateGone(p);
  } else await stable(p);
  await p.evaluate(() => document.fonts.ready);
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  await p.screenshot({
    path: join(dir, `organization-map-${info.project.name}-${state}.png`),
    fullPage: true,
  });
}
function keys(value, names) {
  expect(Object.keys(value).sort()).toEqual(names.split(',').sort());
}
function teamShape(t) {
  keys(
    t,
    'id,name,purpose,status,managerAgentId,managerName,managerUnavailable,agentCount,intake',
  );
  expect(t.agentCount).toBeGreaterThanOrEqual(0);
}

test.describe('organization map independent QC', () => {
  test('MEMBER persisted counts, Team selection, Agent pages, redaction, cycles, Unassigned and links', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const overview = (await (await p.request.get(s.api)).json()).data;
    keys(
      overview,
      'organizationId,name,teamTotal,agentTotal,unassignedTotal,teams',
    );
    keys(overview.teams, 'items,page,size,total');
    expect(overview).toMatchObject({
      organizationId: s.org,
      teamTotal: 25,
      agentTotal: 29,
      unassignedTotal: 3,
    });
    overview.teams.items.forEach(teamShape);
    await expect(hook(p, 'overview')).toContainText(
      '25 Teams · 29 Agents · 3 Unassigned',
    );
    await expect(
      p.getByRole('link', {name: 'Add Agent', exact: true}),
    ).toHaveCount(0);
    await shot(p, info, 'overview');
    const agents = await select(p, s);
    keys(agents, 'organizationId,teamId,team,items,page,size,total');
    teamShape(agents.team);
    expect(agents).toMatchObject({
      organizationId: s.org,
      teamId: s.team,
      total: 25,
      size: 20,
      page: 0,
    });
    for (const a of agents.items)
      keys(
        a,
        'id,name,role,level,status,reportsToAgentId,reportsToName,reportingUnavailable',
      );
    expect(agents.items[1].reportsToName).toBe('Map Agent 001');
    for (const index of [2, 3, 4])
      expect(agents.items[index]).toMatchObject({
        reportsToAgentId: null,
        reportsToName: null,
        reportingUnavailable: true,
      });
    expect(agents.items[5].reportsToName).toBe('Map Agent 007');
    expect(agents.items[6].reportsToName).toBe('Map Agent 006');
    expect(JSON.stringify(agents)).not.toContain('000000005001');
    await expect(hook(p, 'agent')).toHaveCount(20);
    for (const name of ['003', '004', '005'])
      await expect(
        hook(p, 'agent').filter({hasText: `Map Agent ${name}`}),
      ).toContainText('Báo cáo cho: Không có thông tin');
    await expect(
      hook(p, 'agent').filter({hasText: 'Map Agent 006'}).first(),
    ).toContainText('Báo cáo cho: Map Agent 007');
    await shot(p, info, 'selected');
    const next = await change(
      p,
      () => button(p, 'Agent tiếp').click(),
      `${s.api}/agents`,
    );
    expect(next).toMatchObject({page: 1, total: 25});
    await expect(hook(p, 'agent')).toHaveCount(5);
    await expect(hook(p, 'inspector').getByRole('status')).toHaveText(
      'Trang 2 · 25 Agents',
    );
    await expect(button(p, 'Agent tiếp')).toBeDisabled();
    await shot(p, info, 'memberspage2');
    const unassigned = await change(
      p,
      () => button(p, 'Unassigned').click(),
      `${s.api}/agents`,
    );
    expect(unassigned).toMatchObject({
      teamId: null,
      team: null,
      total: 3,
      page: 0,
    });
    await expect(hook(p, 'agent')).toHaveCount(3);
    await shot(p, info, 'unassigned');
    await select(p, s);
    await expect(
      p.getByRole('link', {name: 'Map Agent 001', exact: true}),
    ).toHaveAttribute(
      'href',
      `/workspace/${s.org}/agents/${agents.items[0].id}`,
    );
    await p.getByRole('link', {name: 'Open team', exact: true}).click();
    await expect(p).toHaveURL(
      `${s.url.replace('/organization-map', '')}/teams/${s.team}/workspace`,
    );
  });

  test('Team next page, lifecycle filtering, literal search and Agent search', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const next = await change(p, () => button(p, 'Team tiếp').click(), s.api);
    expect(next.teams).toMatchObject({page: 1, total: 25});
    await expect(hook(p, 'team')).toHaveCount(5);
    await expect(button(p, 'Team tiếp')).toBeDisabled();
    await p.getByLabel('Trạng thái Team', {exact: true}).selectOption('ACTIVE');
    const filtered = await change(
      p,
      () => button(p, 'Lọc Team').click(),
      s.api,
    );
    expect(filtered.teams).toMatchObject({page: 0, total: 1});
    await expect(hook(p, 'team')).toContainText('Map Team 002');
    await p.getByLabel('Trạng thái Team', {exact: true}).selectOption('');
    for (const literal of ['%', '_', "' OR 1=1 --"]) {
      await p.getByLabel('Tìm Team', {exact: true}).fill(literal);
      const data = await change(p, () => button(p, 'Lọc Team').click(), s.api);
      expect(data.teams.total).toBe(0);
      expect(data.teamTotal).toBe(25);
      await expect(hook(p, 'team')).toHaveCount(0);
    }
    await shot(p, info, 'filteredempty');
    await change(p, () => button(p, 'Xóa bộ lọc').click(), s.api);
    await select(p, s);
    await p.getByLabel('Tìm Agent', {exact: true}).fill('Map Agent 025');
    const found = await change(
      p,
      () => button(p, 'Lọc Agent').click(),
      `${s.api}/agents`,
    );
    expect(found.total).toBe(1);
    await expect(hook(p, 'agent')).toHaveCount(1);
    await expect(hook(p, 'agent')).toContainText('Map Agent 025');
  });

  test('empty organization uses persisted zero totals and empty inspector', async ({
    page: p,
  }, info) => {
    await signIn(p, 'map-empty-member@example.test');
    await p.goto(`/workspace/${uuid('50700000', 6)}/organization-map`);
    await stable(p);
    await expect(hook(p, 'overview')).toContainText(
      '0 Teams · 0 Agents · 0 Unassigned',
    );
    await expect(hook(p, 'team')).toHaveCount(0);
    await expect(hook(p, 'agent')).toHaveCount(0);
    await expect(button(p, 'Team tiếp')).toBeDisabled();
    await expect(button(p, 'Agent tiếp')).toBeDisabled();
    await shot(p, info, 'emptyorg');
  });

  test('foreign Team is scoped404 and foreign organization is denied403', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const foreignTeam = uuid('50710000', 501);
    expect(
      (await p.request.get(`${s.api}/agents?teamId=${foreignTeam}`)).status(),
    ).toBe(404);
    expect(
      (
        await p.request.get(
          `/api/v1/organizations/${uuid('50700000', 5)}/organization-map`,
        )
      ).status(),
    ).toBe(403);
    await p.goto(`${s.url}?teamId=${foreignTeam}`);
    await expect(hook(p, 'denied')).toBeVisible();
    await privateGone(p);
  });

  test('actual GET fault503 and malformed DTO erase DOM and manual GET retry recovers', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    for (const fault of ['503', 'malformed']) {
      await select(p, s);
      let intercepted = 0;
      const handler = async route => {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        intercepted++;
        if (fault === '503') await route.fulfill({response, status: 503});
        else {
          const body = await response.json();
          body.data.organizationId = uuid('50700000', 5);
          await route.fulfill({response, json: body});
        }
      };
      await p.route(`${s.api}?*`, handler);
      await button(p, 'Refresh').click();
      await expect(hook(p, 'error')).toBeVisible();
      expect(intercepted).toBeGreaterThan(0);
      await privateGone(p);
      if (fault === '503') await shot(p, info, 'error', true);
      await p.unroute(`${s.api}?*`, handler);
      await change(p, () => button(p, 'Thử lại').click(), s.api);
      await expect(hook(p, 'agent')).toHaveCount(20);
    }
  });

  test('focus observes membership deletion and unsupported role; finally restores MEMBER', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    const predicate = `account_id=(SELECT id FROM human_accounts WHERE email='${s.email}') AND organization_id='${s.org}'`;
    try {
      sql(`DELETE FROM organization_memberships WHERE ${predicate};`);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
      await privateGone(p);
      expect((await p.request.get(s.api)).status()).toBe(403);
      await shot(p, info, 'denied', true);
      sql(
        `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${s.org}','MEMBER' FROM human_accounts WHERE email='${s.email}';`,
      );
      await button(p, 'Refresh').click();
      await stable(p);
      sql(
        `UPDATE organization_memberships SET role='UNKNOWN' WHERE ${predicate};`,
      );
      expect((await p.request.get(s.api)).status()).toBe(403);
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
      await privateGone(p);
    } finally {
      sql(
        `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${s.org}','MEMBER' FROM human_accounts WHERE email='${s.email}' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='MEMBER';`,
      );
    }
  });

  test('held actual GET success cannot repaint after revoke and focus', async ({
    page: p,
  }, info) => {
    const s = await login(p, info);
    let release;
    const gate = new Promise(resolve => {
      release = resolve;
    });
    let fetched;
    const ready = new Promise(resolve => {
      fetched = resolve;
    });
    let finished;
    const done = new Promise(resolve => {
      finished = resolve;
    });
    const handler = async route => {
      try {
        const response = await route.fetch();
        expect(response.status()).toBe(200);
        fetched();
        await gate;
        await route.fulfill({response});
      } catch (error) {
        // Canceled requests may become unfulfillable after the component fences them.
        if (
          !/closed|canceled|cancelled|Invalid InterceptionId/i.test(
            String(error),
          )
        )
          throw error;
      } finally {
        finished();
      }
    };
    await p.route(`${s.api}?*`, handler);
    try {
      await button(p, 'Refresh').click();
      await ready;
      sql(
        `DELETE FROM organization_memberships WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${s.email}');`,
      );
      await p.evaluate(() => window.dispatchEvent(new Event('focus')));
      await expect(hook(p, 'denied')).toBeVisible();
      release();
      await done;
      await p.unroute(`${s.api}?*`, handler);
      await p.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(resolve)),
          ),
      );
      await privateGone(p);
      await expect(hook(p, 'denied')).toBeVisible();
    } finally {
      release();
      await p.unroute(`${s.api}?*`, handler);
      sql(
        `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${s.org}','MEMBER' FROM human_accounts WHERE email='${s.email}' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='MEMBER';`,
      );
    }
  });

  test('shared session actor change clears old map on focus', async ({
    page: p,
    context,
  }, info) => {
    const s = await login(p, info);
    await select(p, s);
    const other = await context.newPage();
    await signIn(other, `map-${info.project.name}-other@example.test`);
    await p.evaluate(() => window.dispatchEvent(new Event('focus')));
    await expect(hook(p, 'denied')).toBeVisible();
    await privateGone(p);
    await other.close();
  });
});
