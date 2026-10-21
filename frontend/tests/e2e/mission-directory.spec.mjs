import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

// Dedicated CI fixture only. Success always comes from the backend.
// Selector contract is recorded in docs/qc/MISSION_DIRECTORY_TEST_CASES.md;
// reconcile against the delivered FE before claiming browser acceptance.
const org = '18181818-1818-4818-8818-181818181818';
const foreign = '19191919-1919-4919-8919-191919191919';
const url = `/workspace/${org}/missions`;
const api = `/api/v1/organizations/${org}/missions/directory`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const rows = p => p.getByTestId('mission-row');
const surface = p => p.getByTestId('mission-directory');
const responseFor = (p, path = api) =>
  p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === 'GET',
  );
async function settle(p, action) {
  const [response] = await Promise.all([responseFor(p), action()]);
  expect(response.status()).toBe(200);
  await expect(surface(p)).toHaveAttribute('aria-busy', 'false');
  return (await response.json()).data;
}
async function login(p, member = false) {
  expect(
    process.env.E2E_PASSWORD,
    'CI bootstrap password required',
  ).toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`mission-directory-${member ? 'member' : 'admin'}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  return settle(p, () => p.goto(url));
}
async function filter(p, query, status = '') {
  await p.getByLabel('Search missions', {exact: true}).fill(query);
  await p.getByLabel('Status', {exact: true}).selectOption(status);
  return settle(p, () => button(p, 'Apply filters').click());
}
async function all(p) {
  const items = [];
  for (let page = 0; ; page++) {
    const response = await p.request.get(`${api}?page=${page}`);
    expect(response.status()).toBe(200);
    const data = (await response.json()).data;
    expect(data).toMatchObject({page, size: 20});
    items.push(...data.items);
    if (items.length >= data.total) break;
    expect(data.items).toHaveLength(20);
    expect(page).toBeLessThan(100);
  }
  return items;
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

test('MD01 fixed pages, stable ordering, history and out-of-range recovery', async ({
  page: p,
}) => {
  const first = await login(p);
  expect(first).toMatchObject({total: 27, page: 0, size: 20, team: null});
  await expect(rows(p)).toHaveCount(20);
  await expect(button(p, 'Previous')).toBeDisabled();
  const next = await settle(p, () => button(p, 'Next').click());
  expect(next).toMatchObject({page: 1, total: 27, size: 20});
  expect(next.items).toHaveLength(7);
  await expect(rows(p)).toHaveCount(7);
  await expect(button(p, 'Next')).toBeDisabled();
  expect(next.items.every(x => !first.items.some(y => y.id === x.id))).toBe(
    true,
  );
  await expect(p).toHaveURL(/page=1/);
  await settle(p, () => p.reload());
  const back = await settle(p, () => p.goBack());
  expect(back.items).toEqual(first.items);
  const items = await all(p);
  expect(new Set(items.map(x => x.id)).size).toBe(first.total);
  expect(items.map(x => x.id)).toEqual(
    [...items]
      .sort(
        (a, b) =>
          Date.parse(b.createdAt) - Date.parse(a.createdAt) ||
          a.id.localeCompare(b.id),
      )
      .map(x => x.id),
  );
  const empty = await settle(p, () => p.goto(`${url}?page=100000`));
  expect(empty.items).toEqual([]);
  await expect(rows(p)).toHaveCount(0);
  await settle(p, () => button(p, 'Clear filters').click());
  await expect(rows(p)).toHaveCount(20);
});

test('MD02 literal/status filters, URL persistence and invalid scope denial', async ({
  page: p,
}) => {
  await login(p);
  const items = await all(p);
  for (const query of ['%', '_', '!']) {
    const expected = items.filter(x => x.title.includes(query));
    expect(expected.length, 'literal fixture required').toBeGreaterThan(0);
    const data = await filter(p, query);
    expect(data.total).toBe(expected.length);
    expect(data.items.every(x => x.title.includes(query))).toBe(true);
  }
  for (const status of [
    'CREATED',
    'PLANNING',
    'READY',
    'RUNNING',
    'WAITING_APPROVAL',
    'REVIEW',
    'COMPLETED',
    'FAILED',
    'CANCELLED',
  ]) {
    const data = await filter(p, '', status);
    expect(data.total).toBe(items.filter(x => x.status === status).length);
    expect(data.total, `fixture status ${status}`).toBeGreaterThan(0);
    expect(data.items.every(x => x.status === status)).toBe(true);
  }
  expect((await filter(p, '\\')).total).toBe(0);
  await settle(p, () => button(p, 'Clear filters').click());
  await settle(p, () => button(p, 'Next').click());
  const target = items.find(x => x.title.includes('%'));
  const data = await filter(
    p,
    `  ${target.title.toUpperCase()}  `,
    target.status,
  );
  expect(data.page).toBe(0);
  expect(data.items.map(x => x.id)).toEqual([target.id]);
  expect(new URL(p.url()).searchParams.get('status')).toBe(target.status);
  await settle(p, () => p.reload());
  await expect(p.getByLabel('Status', {exact: true})).toHaveValue(
    target.status,
  );
  const empty = await filter(p, 'no-such-mission-directory-result');
  expect(empty.total).toBe(0);
  await expect(rows(p)).toHaveCount(0);
  await settle(p, () => button(p, 'Clear filters').click());
  expect(new URL(p.url()).search).toBe('');
  await invalidScopes(p);
});

test('MD03 recorded counts, legacy null team and scoped read links', async ({
  page: p,
}) => {
  await login(p);
  const items = await all(p);
  const legacy = items.find(x => x.teamId === null);
  const recorded = items.find(
    x => x.id === '18300000-0000-4000-8000-000000000025',
  );
  expect(legacy, 'legacy fixture required').toBeTruthy();
  expect(recorded, 'mixed recorded-task fixture required').toBeTruthy();
  expect(recorded).toMatchObject({
    totalTasks: 7,
    completedTasks: 2,
    pendingTasks: 2,
    managerName: 'Directory Manager',
  });
  expect(legacy).toMatchObject({
    totalTasks: 0,
    completedTasks: 0,
    pendingTasks: 0,
    managerName: null,
    teamName: null,
  });
  const allowed = [
    'id',
    'organizationId',
    'title',
    'status',
    'priority',
    'teamId',
    'teamName',
    'managerAgentId',
    'managerName',
    'totalTasks',
    'completedTasks',
    'pendingTasks',
    'createdAt',
  ].sort();
  for (const item of items) {
    expect(item.organizationId).toBe(org);
    expect(Object.keys(item).sort()).toEqual(allowed);
  }
  expect(JSON.stringify(items)).not.toMatch(
    /FOREIGN_PRIVATE|PRIVATE_DIRECTORY_GOAL|Private resolver|Private task reason/,
  );
  await filter(p, legacy.title);
  const legacyRow = rows(p).filter({hasText: legacy.title});
  await expect(legacyRow).toContainText('Team not assigned');
  await expect(legacyRow).toContainText('No recorded tasks');
  await expect(
    legacyRow.getByRole('link', {name: 'View details', exact: true}),
  ).toHaveCount(0);
  await filter(p, recorded.title);
  const row = rows(p).filter({hasText: recorded.title});
  await expect(row).toContainText(
    new RegExp(`${recorded.completedTasks}\\s*/\\s*${recorded.totalTasks}`),
  );
  await expect(row).toContainText(
    new RegExp(`${recorded.pendingTasks}\\s+pending`, 'i'),
  );
  const link = row.getByRole('link', {name: 'View details', exact: true});
  const path = `/workspace/${org}/teams/${recorded.teamId}/missions/${recorded.id}`;
  await expect(link).toHaveAttribute('href', path);
  await link.click();
  await expect(p).toHaveURL(new RegExp(`${path}$`));
  await expect(p.locator('main')).toContainText(recorded.title);
  await p.getByRole('link', {name: 'Browse missions', exact: true}).click();
  await expect(surface(p)).toBeVisible();
});

test('MD04 Team chooser, create permissions, compact navigation and no writes', async ({
  page: p,
}, info) => {
  await login(p);
  await createAndCompactNavigation(p, info);
  const chooserApi = `/api/v1/organizations/${org}/teams/directory`;
  const opener = button(p, 'Select Team');
  const [response] = await Promise.all([
    responseFor(p, chooserApi),
    opener.click(),
  ]);
  expect(response.status()).toBe(200);
  const firstTeams = (await response.json()).data;
  expect(firstTeams).toMatchObject({total: 25, page: 0, size: 20});
  expect(firstTeams.items).toHaveLength(20);
  const dialog = p.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await p.keyboard.press('Escape');
  await expect(dialog).not.toBeVisible();
  await expect(opener).toBeFocused();
  await Promise.all([responseFor(p, chooserApi), opener.click()]);
  const [next] = await Promise.all([
    responseFor(p, chooserApi),
    dialog.getByRole('button', {name: 'Next', exact: true}).click(),
  ]);
  expect(next.status()).toBe(200);
  const secondTeams = (await next.json()).data;
  expect(secondTeams).toMatchObject({total: 25, page: 1});
  expect(secondTeams.items).toHaveLength(5);
  expect(
    new Set([...firstTeams.items, ...secondTeams.items].map(x => x.id)).size,
  ).toBe(25);
  const team = secondTeams.items.find(x => x.status !== 'ARCHIVED');
  expect(team, 'nonarchived second-page Team fixture required').toBeTruthy();
  await dialog.getByRole('button', {name: team.name, exact: true}).click();
  const data = await settle(p, () => button(p, 'Apply filters').click());
  expect(data.team).toMatchObject({id: team.id, name: team.name});
  expect(data.items.every(x => x.teamId === team.id)).toBe(true);
  expect(new URL(p.url()).searchParams.get('teamId')).toBe(team.id);
  await settle(p, () => p.reload());
  await expect(surface(p)).toContainText(team.name);
  await p.getByRole('link', {name: 'Create Mission', exact: true}).click();
  await expect(p).toHaveURL(new RegExp(`/teams/${team.id}/missions/new$`));
});

test('MD05 member reads; real membership revocation clears private rows', async ({
  page: p,
}) => {
  await login(p, true);
  await expect(p.getByRole('link', {name: /create mission/i})).toHaveCount(0);
  await expect(p.getByRole('button', {name: /create mission/i})).toHaveCount(0);
  const membership = `organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='mission-directory-member@example.test')`;
  try {
    sql(`DELETE FROM organization_memberships WHERE ${membership};`);
    await button(p, 'Refresh').click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
    expect((await p.request.get(api)).status()).toBe(403);
  } finally {
    sql(
      `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${org}','MEMBER' FROM human_accounts WHERE email='mission-directory-member@example.test' ON CONFLICT DO NOTHING;`,
    );
  }
});

test('MD06 failed and malformed refresh discard rows; real retry recovers', async ({
  page: p,
}) => {
  await login(p);
  for (const mode of ['503', 'null', 'scope', 'malformed']) {
    await p.route(`**${api}*`, async route => {
      if (mode === '503') return route.fulfill({status: 503, json: {}});
      if (mode === 'null') return route.fulfill({status: 200, json: null});
      const response = await route.fetch();
      const body = await response.json();
      if (mode === 'scope') body.data.items[0].organizationId = foreign;
      else body.data.items[0].totalTasks = -1;
      return route.fulfill({response, json: body});
    });
    await button(p, 'Refresh').click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
    await p.unroute(`**${api}*`);
    await settle(p, () => button(p, 'Try again').click());
    await expect(rows(p)).toHaveCount(20);
  }
});

async function invalidScopes(p) {
  for (const query of [
    'page=-1',
    'page=100001',
    'page=oops',
    'status=PAUSED',
    'teamId=bad',
    `query=${'x'.repeat(201)}`,
  ]) {
    expect((await p.request.get(`${api}?${query}`)).status(), query).toBe(400);
    await p.goto(`${url}?${query}`);
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
  }
  const missing = '18100000-0000-4000-8000-999999999999';
  for (const teamId of [missing, '19100000-0000-4000-8000-000000000001']) {
    expect((await p.request.get(`${api}?teamId=${teamId}`)).status()).toBe(404);
    await p.goto(`${url}?teamId=${teamId}`);
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
  }
  expect((await p.request.get(api.replace(org, foreign))).status()).toBe(403);
  await p.goto(url.replace(org, foreign));
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(rows(p)).toHaveCount(0);
}

async function createAndCompactNavigation(p, info) {
  const writes = [];
  p.on('request', r => {
    if (
      new URL(r.url()).pathname.startsWith(`/api/v1/organizations/${org}/`) &&
      !['GET', 'HEAD', 'OPTIONS'].includes(r.method())
    )
      writes.push(r.url());
  });
  const chooserApi = `/api/v1/organizations/${org}/teams/directory`;
  const chooserResponse = query =>
    p.waitForResponse(response => {
      const target = new URL(response.url());
      return (
        response.request().method() === 'GET' &&
        target.pathname === chooserApi &&
        target.searchParams.get('query') === query &&
        target.searchParams.get('page') === '0'
      );
    });
  const [initial] = await Promise.all([
    chooserResponse(''),
    button(p, 'Create Mission').click(),
  ]);
  expect(initial.status()).toBe(200);
  const dialog = p.getByRole('dialog');
  await expect(dialog).toBeVisible();
  await dialog
    .getByLabel('Search teams', {exact: true})
    .fill('Directory Archived');
  const [searched] = await Promise.all([
    chooserResponse('Directory Archived'),
    dialog.getByLabel('Search teams', {exact: true}).press('Enter'),
  ]);
  expect(searched.status()).toBe(200);
  expect((await searched.json()).data.items.map(x => x.name)).toEqual([
    'Directory Archived',
  ]);
  await expect(
    dialog.getByRole('button', {name: 'Directory Archived', exact: true}),
  ).toBeDisabled();
  await dialog.getByRole('button', {name: 'Cancel', exact: true}).click();
  await expect(dialog).not.toBeVisible();
  await expect(button(p, 'Create Mission')).toBeFocused();
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    const navigation = p.getByRole('dialog', {
      name: 'Workspace navigation',
      exact: true,
    });
    await expect(
      navigation.getByRole('link', {name: 'Missions', exact: true}),
    ).toHaveAttribute('href', url);
    await p.keyboard.press('Escape');
    await expect(button(p, 'Menu')).toBeFocused();
  }
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  expect(writes).toEqual([]);
}
