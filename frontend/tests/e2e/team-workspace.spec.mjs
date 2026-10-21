import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

// Dedicated fixtures owned by the main worker; no baseline directory IDs.
const org = '14141414-1414-4414-8414-141414141414';
const foreign = '15151515-1515-4515-8515-151515151515';
const id = (prefix, n) =>
  `${prefix}000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const team = id('e1', 1);
const emptyTeam = id('e1', 2);
const foreignTeam = id('f1', 1);
const url = `/workspace/${org}/teams/${team}/workspace`;
const api = `/api/v1/organizations/${org}/teams/${team}/workspace`;
const directory = `/api/v1/organizations/${org}/agents/directory`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const surface = p => p.getByTestId('team-workspace');
const tasks = p => p.getByTestId('workspace-tasks').getByTestId('task-row');
const agents = p => p.getByTestId('workspace-agents').getByTestId('agent-row');
const events = p =>
  p.getByTestId('workspace-activity').getByTestId('activity-row');
// Metric aria-labels are distinct from the exact tab names.
const tab = (p, name) => surface(p).getByRole('button', {name, exact: true});
const responseFor = (p, path) =>
  p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === 'GET',
  );
async function settle(p, path, action) {
  const [response] = await Promise.all([responseFor(p, path), action()]);
  await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
  return response;
}
async function login(p, member = false) {
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`workspace-team-${member ? 'member' : 'admin'}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  return settle(p, api, () => p.goto(url));
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
      'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
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
async function assertNoOverflow(p) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
}

test.describe('real Team workspace', () => {
  test('real board counts, all eight lanes and pending reasons with fixed pages', async ({
    page: p,
  }) => {
    const response = await login(p);
    const data = (await response.json()).data;
    expect(data.team).toMatchObject({
      id: team,
      organizationId: org,
      name: 'Workspace Delivery',
    });
    expect(data.summary).toEqual({
      agentCount: 24,
      totalTasks: 31,
      runningTasks: 1,
      pendingTasks: 24,
      reviewTasks: 1,
    });
    expect(data.lanes).toHaveLength(8);
    expect(data.lanes.map(lane => lane.lane)).toEqual([
      'INTAKE',
      'READY',
      'RUNNING',
      'REVIEW',
      'PENDING',
      'DONE',
      'FAILED',
      'CANCELLED',
    ]);
    for (const lane of data.lanes) {
      expect(lane.total).toBe(lane.lane === 'PENDING' ? 24 : 1);
      expect(lane.items).toHaveLength(lane.lane === 'PENDING' ? 5 : 1);
      const panel = p.getByTestId(`lane-${lane.lane}`);
      await expect(panel).toBeVisible();
      for (const item of lane.items)
        await expect(panel).toContainText(item.title);
    }
    await expect(
      p.getByRole('heading', {name: 'Workspace Delivery', exact: true}),
    ).toBeVisible();
    for (const [name, lane] of [
      ['Running tasks', 'RUNNING'],
      ['Review tasks', 'REVIEW'],
    ]) {
      const response = await settle(p, `${api}/tasks`, () =>
        button(p, name).click(),
      );
      expect(new URL(response.url()).searchParams.get('lane')).toBe(lane);
      expect((await response.json()).data.total).toBe(1);
      await expect(tasks(p)).toHaveCount(1);
    }
    const pending = await settle(p, `${api}/tasks`, () =>
      tab(p, 'Pending').click(),
    );
    expect((await pending.json()).data).toMatchObject({
      page: 0,
      size: 20,
      total: 24,
    });
    await expect(tasks(p)).toHaveCount(20);
    await expect(button(p, 'Previous')).toBeDisabled();
    const first = await tasks(p).allTextContents();
    expect(first.join(' ')).toMatch(/Await (user input|dependency) \d+/);
    expect(first.join(' ')).toContain('Workspace operator');
    const pendingData = (await pending.json()).data;
    expect(pendingData.items.map(item => item.id)).toEqual(
      Array.from({length: 20}, (_, i) => id('e4', 31 - i)),
    );
    for (const item of pendingData.items) {
      const n = Number(item.id.slice(-12));
      expect(item.pendingReason).toBe(
        `Await ${n % 2 === 0 ? 'user input' : 'dependency'} ${n}`,
      );
      expect(item.waitingSince).toBeTruthy();
      expect(item.resolver).toBe('Workspace operator');
    }
    const next = await settle(p, `${api}/tasks`, () =>
      button(p, 'Next').click(),
    );
    const last = (await next.json()).data;
    expect(last).toMatchObject({page: 1, size: 20, total: 24});
    await expect(tasks(p)).toHaveCount(4);
    await expect(button(p, 'Next')).toBeDisabled();
    expect(last.items.every(item => item.teamId === team)).toBe(true);
    await settle(p, `${api}/tasks`, () => button(p, 'Previous').click());
    await expect(tasks(p)).toHaveText(first);
    await assertNoOverflow(p);
  });

  test('assigned agents use scoped directory paging and real navigation', async ({
    page: p,
  }) => {
    await login(p);
    const response = await settle(p, directory, () => tab(p, 'Agents').click());
    expect(new URL(response.url()).searchParams.get('teamId')).toBe(team);
    const first = (await response.json()).data;
    expect(first).toMatchObject({page: 0, size: 20, total: 24});
    expect(
      first.items.every(
        item => item.organizationId === org && item.teamId === team,
      ),
    ).toBe(true);
    await expect(agents(p)).toHaveCount(20);
    const next = await settle(p, directory, () => button(p, 'Next').click());
    expect((await next.json()).data).toMatchObject({
      page: 1,
      size: 20,
      total: 24,
    });
    expect(new URL(next.url()).searchParams.get('teamId')).toBe(team);
    await expect(agents(p)).toHaveCount(4);
    await expect(button(p, 'Next')).toBeDisabled();
    const agentLink = agents(p).first().getByRole('link');
    const target = new URL(await agentLink.getAttribute('href'), p.url());
    expect(target.pathname).toBe(`/workspace/${org}/agents/${id('e2', 4)}`);
    await agentLink.click();
    await expect(p).toHaveURL(target.href);
  });

  test('real activity has stable paging and safe metadata without foreign references', async ({
    page: p,
  }) => {
    await login(p);
    const first = await settle(p, `${api}/activity`, () =>
      tab(p, 'Activity').click(),
    );
    const a = (await first.json()).data;
    expect(a).toMatchObject({page: 0, size: 20, total: 25});
    await expect(events(p)).toHaveCount(20);
    const next = await settle(p, `${api}/activity`, () =>
      button(p, 'Next').click(),
    );
    const b = (await next.json()).data;
    expect(b).toMatchObject({page: 1, size: 20, total: 25});
    await expect(events(p)).toHaveCount(5);
    await expect(button(p, 'Next')).toBeDisabled();
    const items = [...a.items, ...b.items];
    expect(new Set(items.map(item => item.id)).size).toBe(25);
    expect(items.map(item => item.id)).toEqual(
      Array.from({length: 25}, (_, i) => id('e5', 25 - i)),
    );
    expect(items.map(item => item.id).sort()).toEqual(
      Array.from({length: 25}, (_, i) => id('e5', i + 1)).sort(),
    );
    for (const item of items) {
      expect(item.eventType).toBe('WORKSPACE_TASK_EVENT');
      expect(item.actorType).toBe('HUMAN');
      expect(Object.keys(item).sort()).toEqual([
        'actorType',
        'createdAt',
        'eventType',
        'id',
      ]);
      expect(
        Object.keys(item).some(key =>
          /payload|actorId|secret|token|credential/i.test(key),
        ),
      ).toBe(false);
    }
    expect(JSON.stringify(items)).not.toMatch(
      /f[1345]000000-0000|secret|sentinel|mixed.foreign/i,
    );
    await expect(p.locator('main')).not.toContainText('payload');
    await assertNoOverflow(p);
  });

  test('member access, current role refresh, revoked membership and Add Agent action', async ({
    page: p,
  }) => {
    await login(p, true);
    await expect(
      p.getByRole('link', {name: 'Add Agent', exact: true}),
    ).toHaveCount(0);
    const membership = `organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='workspace-team-member@example.test')`;
    try {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
      );
      await settle(p, api, () => button(p, 'Refresh').click());
      const add = p.getByRole('link', {name: 'Add Agent', exact: true});
      await expect(add).toBeVisible();
      const target = new URL(await add.getAttribute('href'), p.url());
      expect(target.pathname).toBe(`/workspace/${org}/agents/new`);
      expect(target.searchParams.get('teamId')).toBe(team);
      await add.click();
      await expect(p).toHaveURL(target.href);
      await settle(p, api, () => p.goto(url));
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE ${membership};`,
      );
      await settle(p, api, () => button(p, 'Refresh').click());
      await expect(add).toHaveCount(0);
      await settle(p, directory, () => tab(p, 'Agents').click());
      sql(`DELETE FROM organization_memberships WHERE ${membership};`);
      await settle(p, `/api/v1/auth/workspaces/${org}`, () =>
        button(p, 'Refresh').click(),
      );
      await expect(agents(p)).toHaveCount(0);
      await expect(tasks(p)).toHaveCount(0);
      await expect(p.getByRole('alert')).toBeVisible();
      await expect(p.locator('main')).not.toContainText('Workspace Delivery');
      expect((await p.request.get(api)).status()).toBe(403);
    } finally {
      sql(
        `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${org}','MEMBER' FROM human_accounts WHERE email='workspace-team-member@example.test' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='MEMBER';`,
      );
    }
  });

  test('503, null and wrong scope clear old tab state; response-synchronized retry recovers', async ({
    page: p,
  }) => {
    await login(p);
    for (const [name, path, rows] of [
      [
        'Overview',
        api,
        q => q.getByTestId('workspace-board').getByTestId('lane-PENDING'),
      ],
      ['Pending', `${api}/tasks`, tasks],
      ['Agents', directory, agents],
      ['Activity', `${api}/activity`, events],
    ]) {
      if (name !== 'Overview')
        await settle(p, path, () => tab(p, name).click());
      const loadedCount = name === 'Overview' ? 1 : 20;
      for (const mode of ['503', 'null', 'scope']) {
        await expect(rows(p)).toHaveCount(loadedCount);
        const pattern = `**${path}*`;
        await p.route(pattern, async route => {
          if (mode === '503') return route.fulfill({status: 503, json: {}});
          if (mode === 'null') return route.fulfill({status: 200, json: null});
          const response = await route.fetch();
          const body = await response.json();
          if (name === 'Overview') {
            body.data.team.organizationId = foreign;
            body.data.team.id = foreignTeam;
          } else {
            body.data.items[0].organizationId = foreign;
            body.data.items[0].teamId = foreignTeam;
          }
          return route.fulfill({response, json: body});
        });
        await settle(p, path, () => button(p, 'Refresh').click());
        await expect(rows(p)).toHaveCount(0);
        await expect(p.getByRole('alert')).toBeVisible();
        await p.unroute(pattern);
        await settle(p, path, () => button(p, 'Try again').click());
        await expect(rows(p)).toHaveCount(loadedCount);
        await expect(p.getByRole('alert')).toHaveCount(0);
      }
    }
  });

  test('URL Back, empty/foreign/invalid scope, keyboard and zero workspace writes', async ({
    page: p,
  }, info) => {
    await login(p);
    const writes = [];
    p.on('request', request => {
      if (
        new URL(request.url()).pathname.startsWith('/api/v1/organizations/') &&
        !['GET', 'HEAD', 'OPTIONS'].includes(request.method())
      )
        writes.push(`${request.method()} ${request.url()}`);
    });
    await tab(p, 'Pending').focus();
    await settle(p, `${api}/tasks`, () => p.keyboard.press('Enter'));
    await settle(p, `${api}/tasks`, () => button(p, 'Next').click());
    await expect(tasks(p)).toHaveCount(4);
    await settle(p, `${api}/tasks`, () => p.goBack());
    await expect(tasks(p)).toHaveCount(20);
    await settle(p, `${api}/tasks`, () => p.reload());
    await expect(tasks(p)).toHaveCount(20);
    if (info.project.name !== 'desktop') {
      const menu = button(p, 'Menu');
      await menu.click();
      await expect(
        p.getByRole('dialog', {name: 'Workspace navigation'}),
      ).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(menu).toBeFocused();
    }
    await assertNoOverflow(p);
    const emptyApi = api.replace(team, emptyTeam);
    const empty = await settle(p, emptyApi, () =>
      p.goto(url.replace(team, emptyTeam)),
    );
    expect(
      (await empty.json()).data.lanes.every(
        lane => lane.total === 0 && lane.items.length === 0,
      ),
    ).toBe(true);
    await expect(tasks(p)).toHaveCount(0);
    for (const invalid of [
      'tab=unknown',
      'tab=pending&page=-1',
      'tab=pending&page=oops',
      'lane=INVALID',
    ]) {
      await p.goto(`${url}?${invalid}`);
      await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
      await expect(p.getByRole('alert')).toBeVisible();
      await expect(tasks(p)).toHaveCount(0);
    }
    expect((await p.request.get(api.replace(team, foreignTeam))).status()).toBe(
      404,
    );
    expect(
      (
        await p.request.get(
          api.replace(org, foreign).replace(team, foreignTeam),
        )
      ).status(),
    ).toBe(403);
    for (const denied of [
      url.replace(team, foreignTeam),
      url.replace(org, foreign).replace(team, foreignTeam),
      url.replace(team, 'not-a-uuid'),
    ]) {
      await p.goto(denied);
      await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
      await expect(p.getByRole('alert')).toBeVisible();
      await expect(tasks(p)).toHaveCount(0);
      await expect(agents(p)).toHaveCount(0);
      await expect(events(p)).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText('Workspace Delivery');
    }
    expect(writes).toEqual([]);
  });
});
