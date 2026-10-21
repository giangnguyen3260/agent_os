import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

// Dedicated disposable fixture; coordinator owns SQL/probe/workflow.
const org = '20202020-2020-4020-8020-202020202020';
const foreign = '21212121-2121-4121-8121-212121212121';
const id = (prefix, n) =>
  `${prefix}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const team = id('2010', 1),
  mission = id('2030', 1),
  empty = id('2030', 2);
const api = `/api/v1/organizations/${org}/teams/${team}/missions/${mission}/workspace`;
const url = `/workspace/${org}/teams/${team}/missions/${mission}/workspace`;
const statuses = [
  'DRAFT',
  'READY',
  'RUNNING',
  'WAITING',
  'BLOCKED',
  'IN_REVIEW',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
];
const button = (p, name) => p.getByRole('button', {name, exact: true});
const surface = p => p.getByTestId('mission-workspace');
const tasks = p => p.getByTestId('workspace-tasks').getByTestId('task-row');
const events = p =>
  p.getByTestId('workspace-activity').getByTestId('activity-row');
async function settle(p, path, action) {
  const [response] = await Promise.all([
    p.waitForResponse(
      r => new URL(r.url()).pathname === path && r.request().method() === 'GET',
    ),
    action(),
  ]);
  await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
  return response;
}
async function login(p, member = false) {
  expect(process.env.E2E_PASSWORD, 'bootstrap password required').toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`mission-workspace-${member ? 'member' : 'admin'}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  const response = await settle(p, api, () => p.goto(url));
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
async function get(p, path) {
  const response = await p.request.get(path);
  expect(response.status(), path).toBe(200);
  return (await response.json()).data;
}
async function collect(p, path, query = '') {
  const items = [];
  for (let page = 0; page < 100; page++) {
    const data = await get(p, `${path}?page=${page}${query}`);
    expect(data).toMatchObject({page, size: 20});
    expect(data.items.length).toBe(
      Math.min(20, Math.max(0, data.total - page * 20)),
    );
    items.push(...data.items);
    if (items.length >= data.total) {
      expect(new Set(items.map(x => x.id)).size).toBe(data.total);
      return items;
    }
  }
  throw new Error('Fixture exceeded bounded pagination');
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
async function noRows(p) {
  // Await this load's completion before checking an alert or cleared rows.
  await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
  await expect(tasks(p)).toHaveCount(0);
  await expect(events(p)).toHaveCount(0);
  await expect(p.getByRole('alert')).toBeVisible();
}

test('MW01 real counts, nine statuses, pending details and filter page reset', async ({
  page: p,
}) => {
  const root = await login(p);
  expect(root.mission).toMatchObject({
    id: mission,
    organizationId: org,
    teamId: team,
  });
  await expect(
    p.getByRole('heading', {name: 'Mission Control', exact: true}),
  ).toBeVisible();
  await expect(surface(p)).toContainText(root.mission.title);
  const all = await collect(p, `${api}/tasks`);
  const count = (...s) => all.filter(x => s.includes(x.status)).length;
  expect(root.summary).toEqual({
    totalTasks: all.length,
    completedTasks: count('COMPLETED'),
    runningTasks: count('RUNNING'),
    pendingTasks: count('WAITING', 'BLOCKED'),
    reviewTasks: count('IN_REVIEW'),
  });
  expect(root.summary).toEqual({
    totalTasks: 31,
    completedTasks: 1,
    runningTasks: 1,
    pendingTasks: 24,
    reviewTasks: 1,
  });
  expect(root.mission).toMatchObject({
    title: 'Mission Control Review',
    status: 'RUNNING',
    managerName: 'Control Manager',
  });
  expect(all.map(x => x.id)).toEqual(
    Array.from({length: 31}, (_, i) => id('2040', 31 - i)),
  );
  await expect(tasks(p)).toHaveCount(20);
  for (const item of all) {
    expect(item).toMatchObject({
      organizationId: org,
      teamId: team,
      missionId: mission,
    });
    expect(statuses).toContain(item.status);
    if (['WAITING', 'BLOCKED'].includes(item.status)) {
      expect(item.pendingReason).toEqual(expect.any(String));
      expect(item.pendingReason).toBe(
        `Await evidence ${Number(item.id.slice(-12))}`,
      );
      expect(item.resolver).toBe('Control operator');
      expect(Number.isFinite(Date.parse(item.waitingSince))).toBe(true);
      expect(item.resolver).toEqual(expect.any(String));
    } else
      for (const field of ['pendingReason', 'waitingSince', 'resolver'])
        expect(item[field]).toBeNull();
    for (const field of ['owner', 'run', 'spec', 'privateMissionContext'])
      expect(item).not.toHaveProperty(field);
  }
  const pageTwo = await settle(p, `${api}/tasks`, () =>
    button(p, 'Next').click(),
  );
  expect((await pageTwo.json()).data.items).toHaveLength(11);
  await expect(tasks(p)).toHaveCount(11);
  for (const status of statuses) {
    expect(count(status), `fixture must exercise ${status}`).toBeGreaterThan(0);
    await p.getByLabel('Task status', {exact: true}).selectOption(status);
    const response = await settle(p, `${api}/tasks`, () =>
      button(p, 'Apply filters').click(),
    );
    const data = (await response.json()).data;
    expect(data).toMatchObject({page: 0, total: count(status)});
    expect(data.items.every(x => x.status === status)).toBe(true);
    await expect(tasks(p)).toHaveCount(data.items.length);
    for (const item of data.items)
      await expect(tasks(p).filter({hasText: item.title})).toBeVisible();
  }
});

test('MW02 pending paging, metric navigation, reload and Back preserve URL', async ({
  page: p,
}) => {
  const root = await login(p);
  const response = await settle(p, `${api}/tasks`, () =>
    button(p, 'Pending').click(),
  );
  const first = (await response.json()).data;
  expect(first).toMatchObject({
    page: 0,
    size: 20,
    total: root.summary.pendingTasks,
  });
  expect(
    first.items.every(x => ['WAITING', 'BLOCKED'].includes(x.status)),
  ).toBe(true);
  await expect(tasks(p)).toHaveCount(20);
  await expect(button(p, 'Previous')).toBeDisabled();
  const next = await settle(p, `${api}/tasks`, () => button(p, 'Next').click());
  const second = (await next.json()).data;
  expect(second.page).toBe(1);
  expect(second.items).toHaveLength(4);
  await expect(button(p, 'Next')).toBeDisabled();
  expect(second.items.every(x => !first.items.some(y => y.id === x.id))).toBe(
    true,
  );
  await expect(tasks(p)).toHaveCount(second.items.length);
  await expect(p).toHaveURL(/page=1/);
  await settle(p, `${api}/tasks`, () => p.reload());
  await expect(tasks(p)).toHaveCount(second.items.length);
  const back = await settle(p, `${api}/tasks`, () => p.goBack());
  expect((await back.json()).data.items).toEqual(first.items);
  await settle(p, `${api}/tasks`, () => button(p, 'Running tasks').click());
  await expect(tasks(p)).toHaveCount(root.summary.runningTasks);
  await settle(p, `${api}/tasks`, () => button(p, 'Pending tasks').click());
  await expect(tasks(p)).toHaveCount(20);
});

test('MW03 safe mixed-reference activity, exact event keys and stable pages', async ({
  page: p,
}) => {
  await login(p);
  const response = await settle(p, `${api}/activity`, () =>
    button(p, 'Activity').click(),
  );
  const first = (await response.json()).data;
  expect(first.total).toBe(25);
  await expect(events(p)).toHaveCount(20);
  const next = await settle(p, `${api}/activity`, () =>
    button(p, 'Next').click(),
  );
  const second = (await next.json()).data;
  expect(second.page).toBe(1);
  await expect(events(p)).toHaveCount(second.items.length);
  const all = await collect(p, `${api}/activity`);
  expect(second.items).toHaveLength(5);
  await expect(button(p, 'Next')).toBeDisabled();
  expect(all.map(x => x.id)).toEqual(
    Array.from({length: 25}, (_, i) => id('2050', 25 - i)),
  );
  expect(all.some(x => x.id.startsWith('2060'))).toBe(false);
  for (const item of all) {
    expect(Object.keys(item).sort()).toEqual([
      'actorType',
      'createdAt',
      'eventType',
      'id',
    ]);
    expect(Number.isFinite(Date.parse(item.createdAt))).toBe(true);
    expect(item.actorType).toEqual(expect.any(String));
    expect(item.eventType).toBe('CONTROL_TASK_EVENT');
    expect(item.actorType).toBe('HUMAN');
  }
  expect(all.map(x => x.id)).toEqual(
    [...all]
      .sort(
        (a, b) =>
          Date.parse(b.createdAt) - Date.parse(a.createdAt) ||
          a.id.localeCompare(b.id),
      )
      .map(x => x.id),
  );
  expect((await collect(p, `${api}/activity`)).map(x => x.id)).toEqual(
    all.map(x => x.id),
  );
  const back = await settle(p, `${api}/activity`, () =>
    button(p, 'Previous').click(),
  );
  expect((await back.json()).data.items).toEqual(first.items);
});

test('MW04 empty mission, invalid parameters and every API scope boundary', async ({
  page: p,
}) => {
  const root = await login(p);
  const emptyApi = api.replace(mission, empty);
  const response = await settle(p, emptyApi, () =>
    p.goto(url.replace(mission, empty)),
  );
  const emptyData = (await response.json()).data;
  expect(emptyData.mission).toMatchObject({
    id: empty,
    status: 'CREATED',
    managerAgentId: null,
    managerName: null,
  });
  expect(emptyData.summary).toEqual({
    totalTasks: 0,
    completedTasks: 0,
    runningTasks: 0,
    pendingTasks: 0,
    reviewTasks: 0,
  });
  await expect(tasks(p)).toHaveCount(0);
  expect((await get(p, `${emptyApi}/tasks`)).items).toEqual([]);
  expect((await get(p, `${emptyApi}/activity`)).items).toEqual([]);
  for (const suffix of ['', '/tasks', '/activity']) {
    for (const [path, status] of [
      [api.replace(org, foreign), 403],
      [api.replace(team, id('2010', 999)), 404],
      [api.replace(team, id('2110', 1)), 404],
      [api.replace(mission, id('2030', 999)), 404],
      [api.replace(mission, id('2030', 4)), 404],
      [api.replace(team, id('2010', 2)), 404],
      [api.replace(mission, id('2130', 1)), 404],
    ])
      expect((await p.request.get(path + suffix)).status(), path + suffix).toBe(
        status,
      );
  }
  for (const path of [
    '/tasks?page=-1',
    '/tasks?page=100001',
    '/tasks?page=oops',
    '/tasks?status=INVALID',
    '/activity?page=-1',
    '/activity?page=100001',
  ])
    expect((await p.request.get(api + path)).status(), path).toBe(400);
  for (const query of [
    'tab=unknown',
    'tab=pending&status=RUNNING',
    'tab=tasks&status=INVALID',
    'tab=activity&page=-1',
  ]) {
    await p.goto(`${url}?${query}`);
    await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
    await noRows(p);
  }
  await p.goto(url.replace(team, id('2010', 999)));
  await noRows(p);
  await expect(surface(p)).not.toContainText(root.mission.title);
});

test('MW05 failure-only intercepts clear rows and metadata; real GET retry', async ({
  page: p,
}) => {
  const root = await login(p);
  for (const mode of ['503', 'null', 'scope', 'counts']) {
    const pattern = `**${api}`;
    await p.route(pattern, async route => {
      if (mode === '503') return route.fulfill({status: 503, json: {}});
      if (mode === 'null') return route.fulfill({status: 200, json: null});
      const response = await route.fetch();
      const body = await response.json();
      if (mode === 'scope') body.data.mission.organizationId = foreign;
      else body.data.summary.totalTasks = -1;
      return route.fulfill({response, json: body});
    });
    try {
      await settle(p, api, () => button(p, 'Refresh').click());
      await noRows(p);
      await expect(surface(p)).not.toContainText(root.mission.title);
    } finally {
      await p.unroute(pattern);
    }
    await settle(p, `${api}/tasks`, () => button(p, 'Try again').click());
    await expect(tasks(p)).toHaveCount(20);
  }
  for (const [tab, path, rows] of [
    ['Tasks', `${api}/tasks`, tasks],
    ['Pending', `${api}/tasks`, tasks],
    ['Activity', `${api}/activity`, events],
  ]) {
    await settle(p, path, () => p.goto(`${url}?tab=${tab.toLowerCase()}`));
    for (const mode of ['503', 'null', 'malformed']) {
      await expect(rows(p)).not.toHaveCount(0);
      const pattern = `**${path}*`;
      await p.route(pattern, async route => {
        if (mode === '503') return route.fulfill({status: 503, json: {}});
        if (mode === 'null') return route.fulfill({status: 200, json: null});
        const response = await route.fetch();
        const body = await response.json();
        if (tab === 'Activity') body.data.items[0].payload = 'MUST_REJECT';
        else body.data.items[0].organizationId = foreign;
        return route.fulfill({response, json: body});
      });
      try {
        await settle(p, path, () => button(p, 'Refresh').click());
        await noRows(p);
        await expect(surface(p)).not.toContainText(root.mission.title);
      } finally {
        await p.unroute(pattern);
      }
      await settle(p, path, () => button(p, 'Try again').click());
      await expect(rows(p)).not.toHaveCount(0);
      await expect(p.getByRole('alert')).toHaveCount(0);
    }
  }
});

test('MW06 member revocation, disabled mutations, native compact navigation and no writes', async ({
  page: p,
}, info) => {
  const root = await login(p, true);
  const writes = [];
  p.on('request', r => {
    if (
      new URL(r.url()).pathname.startsWith('/api/v1/organizations/') &&
      !['GET', 'HEAD', 'OPTIONS'].includes(r.method())
    )
      writes.push(`${r.method()} ${r.url()}`);
  });
  for (const name of [
    'Add Task',
    'Pause mission',
    'Cancel mission',
    'Plan',
    'Outputs',
  ])
    await expect(button(p, name)).toBeDisabled();
  await expect(surface(p)).toContainText(/unavailable/i);
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    const dialog = p.getByRole('dialog', {
      name: 'Workspace navigation',
      exact: true,
    });
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByRole('link', {name: 'Missions', exact: true}),
    ).toHaveAttribute('href', `/workspace/${org}/missions`);
    await p.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(button(p, 'Menu')).toBeFocused();
  }
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  for (const [name, href] of [
    ['Team Workspace', `/workspace/${org}/teams/${team}/workspace`],
    ['Mission details', url.replace(/\/workspace$/, '')],
  ])
    await expect(p.getByRole('link', {name, exact: true})).toHaveAttribute(
      'href',
      href,
    );
  const membership = `organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='mission-workspace-member@example.test')`;
  try {
    sql(`DELETE FROM organization_memberships WHERE ${membership};`);
    await settle(p, `/api/v1/auth/workspaces/${org}`, () =>
      button(p, 'Refresh').click(),
    );
    await noRows(p);
    await expect(surface(p)).not.toContainText(root.mission.title);
    for (const suffix of ['', '/tasks', '/activity'])
      expect((await p.request.get(api + suffix)).status()).toBe(403);
  } finally {
    sql(
      `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${org}','MEMBER' FROM human_accounts WHERE email='mission-workspace-member@example.test' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='MEMBER';`,
    );
  }
  expect(writes).toEqual([]);
});
