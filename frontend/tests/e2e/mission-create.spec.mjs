import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';

const org = '16161616-1616-4616-8616-161616161616';
const foreign = '17171717-1717-4717-8717-171717171717';
const teamId = n => `16100000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const managerId = n => `16200000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const team = teamId(1);
const api = `/api/v1/organizations/${org}/teams/${team}/missions`;
const url = `/workspace/${org}/teams/${team}/missions/new`;
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const field = (p, name) =>
  p.getByLabel(
    {
      Manager: 'Manager (optional)',
      Priority: 'Priority *',
      Deadline: 'Deadline (optional)',
      'Budget limit (USD)': 'Budget limit (USD, optional)',
      Context: 'Context (optional)',
    }[name] || name,
    {exact: true},
  );
const create = p =>
  p.getByRole('button', {name: /^(?:Retry )?Create mission$/});
const saved = p =>
  p.locator('main').filter({
    has: p.getByRole('heading', {name: 'Mission details', exact: true}),
  });
const payload = (overrides = {}) => ({
  title: `Mission QC ${randomUUID()}`,
  goal: 'Review a real persisted mission',
  requiredOutput: 'A concrete QC report',
  managerAgentId: null,
  priority: 'NORMAL',
  deadline: null,
  budgetLimit: null,
  approvalMode: 'HUMAN_REQUIRED',
  context: null,
  ...overrides,
});
async function login(p, email) {
  await p.goto('/login');
  await field(p, 'Email').fill(email);
  await field(p, 'Mật khẩu').fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
const account = info => `mission-${info.project.name}@example.test`;
async function wizard(p, info) {
  await login(p, account(info));
  await p.goto(url);
  await expect(field(p, 'Title')).toBeVisible();
  await expect(field(p, 'Team')).toHaveValue('Mission Delivery');
  await expect(field(p, 'Team')).toHaveAttribute('readonly', '');
}
async function goal(p, title) {
  await field(p, 'Title').fill(title);
  await field(p, 'Goal').fill('Review a real persisted mission');
  await field(p, 'Required output').fill('A concrete QC report');
  await button(p, 'Continue').click();
  await expect(field(p, 'Priority')).toBeVisible();
}
async function review(p) {
  await button(p, 'Continue').click();
  await expect(create(p)).toBeVisible();
}
async function headers(p, key = randomUUID()) {
  const response = await p.request.get('/api/v1/auth/csrf');
  expect(response.status()).toBe(200);
  const csrf = (await response.json()).data;
  return {[csrf.headerName]: csrf.token, 'Idempotency-Key': key};
}
async function post(p, data, path = api, key = randomUUID()) {
  return p.request.post(path, {data, headers: await headers(p, key)});
}
async function read(p, id) {
  const response = await p.request.get(`${api}/${id}`);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
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
async function noOverflow(p) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
}

test('validation, keyboard Back and dismissed/accepted Cancel never write early', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  const writes = [];
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === api)
      writes.push(r);
  });
  await field(p, 'Title').fill('   ');
  await button(p, 'Continue').click();
  await expect(field(p, 'Title')).toBeVisible();
  await expect(create(p)).toHaveCount(0);
  const title = `Cancel ${info.project.name}`;
  await goal(p, title);
  await expect(field(p, 'Priority')).toHaveValue('NORMAL');
  await field(p, 'Budget limit (USD)').fill('1000000.01');
  await button(p, 'Continue').click();
  await expect(field(p, 'Budget limit (USD)')).toBeVisible();
  await field(p, 'Budget limit (USD)').fill('12.34');
  await field(p, 'Context').fill('  Keep exact\ncontext  ');
  await button(p, 'Back').focus();
  await p.keyboard.press('Enter');
  await expect(field(p, 'Title')).toHaveValue(title);
  await button(p, 'Continue').click();
  await expect(field(p, 'Budget limit (USD)')).toHaveValue('12.34');
  await expect(field(p, 'Context')).toHaveValue('  Keep exact\ncontext  ');
  await review(p);
  await noOverflow(p);
  if (info.project.name !== 'desktop') {
    const menu = button(p, 'Menu');
    await menu.click();
    await expect(
      p.getByRole('dialog', {name: 'Workspace navigation', exact: true}),
    ).toBeVisible();
    await p.keyboard.press('Escape');
    await expect(menu).toBeFocused();
  }
  p.once('dialog', d => d.dismiss());
  await button(p, 'Cancel').click();
  await expect(create(p)).toBeVisible();
  p.once('dialog', d => d.accept());
  await button(p, 'Cancel').click();
  await expect(p).not.toHaveURL(/\/missions\/new$/);
  expect(writes).toHaveLength(0);
});

test('create persists reviewed configuration and reloads at each width', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  const title = `Delivery ${info.project.name} ${randomUUID()}`;
  await goal(p, `  ${title}  `);
  await expect(
    field(p, 'Manager').getByRole('option', {
      name: /Mission Manager 02.*DRAFT.*configuration only/,
    }),
  ).toHaveCount(1);
  await button(p, 'Next managers').click();
  await expect(
    field(p, 'Manager').getByRole('option', {name: /Mission Manager 23/}),
  ).toHaveCount(1);
  await button(p, 'Previous managers').click();
  await field(p, 'Manager').selectOption(
    managerId(info.project.name === 'tablet' ? 2 : 1),
  );
  await field(p, 'Priority').selectOption('HIGH');
  await field(p, 'Deadline').fill('2028-02-29');
  await field(p, 'Budget limit (USD)').fill('123.40');
  const context = '  Exact context\nwith trailing spaces  ';
  await field(p, 'Context').fill(context);
  await review(p);
  await expect(p.locator('main')).toContainText(title);
  const pending = p.waitForResponse(
    r => r.request().method() === 'POST' && new URL(r.url()).pathname === api,
  );
  await create(p).click();
  const response = await pending;
  expect(response.status()).toBe(201);
  const data = (await response.json()).data;
  expect(data.id).toMatch(uuid);
  expect(data).toMatchObject({
    organizationId: org,
    teamId: team,
    teamName: 'Mission Delivery',
    title,
    goal: 'Review a real persisted mission',
    requiredOutput: 'A concrete QC report',
    managerAgentId: managerId(info.project.name === 'tablet' ? 2 : 1),
    status: 'CREATED',
    priority: 'HIGH',
    deadline: '2028-02-29',
    budgetLimit: 123.4,
    currency: 'USD',
    approvalMode: 'HUMAN_REQUIRED',
    context,
  });
  await expect(p).toHaveURL(new RegExp(`/teams/${team}/missions/${data.id}$`));
  await expect(saved(p)).toContainText(title);
  expect(await read(p, data.id)).toEqual(data);
  await p.reload();
  await expect(saved(p)).toContainText(title);
  await expect(saved(p)).toContainText(/plan not generated/i);
  await expect(
    p.getByRole('button', {name: /generate plan|execute/i}),
  ).toHaveCount(0);
  await noOverflow(p);
});

test('ambiguous POST freezes body/key; retry replays one real persisted mission', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  await goal(p, `Ambiguous ${info.project.name} ${randomUUID()}`);
  await review(p);
  const requests = [];
  let id;
  await p.route(`**${api}`, async route => {
    const request = route.request();
    if (request.method() !== 'POST') return route.continue();
    requests.push({
      key: request.headers()['idempotency-key'],
      body: request.postData(),
    });
    const response = await route.fetch({maxRetries: 0});
    const data = (await response.json()).data;
    if (requests.length === 1) {
      expect(response.status()).toBe(201);
      id = data.id;
      if (info.project.name === 'desktop') return route.abort('failed');
      if (info.project.name === 'tablet')
        return route.fulfill({
          status: 503,
          json: {error: {code: 'UNAVAILABLE'}},
        });
      return route.fulfill({status: 201, json: {data: {}}});
    }
    expect(response.status()).toBe(200);
    expect(data.id).toBe(id);
    return route.fulfill({response});
  });
  await create(p).click();
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(button(p, 'Back')).toBeDisabled();
  await create(p).click();
  await expect(saved(p)).toBeVisible();
  expect(requests).toHaveLength(2);
  expect(requests[0].key).toMatch(uuid);
  expect(requests[1]).toEqual(requests[0]);
  expect((await read(p, id)).status).toBe('CREATED');
  expect(
    sql(
      `SELECT count(*) FROM missions WHERE organization_id='${org}' AND title='${JSON.parse(requests[0].body).title}';`,
    ),
  ).toBe('1');
});

test('confirmed creation followed by failed detail retries GET only', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  await goal(p, `Read retry ${info.project.name} ${randomUUID()}`);
  await review(p);
  let writes = 0,
    failed = false;
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === api) writes++;
  });
  await p.route(`**${api}/*`, async route => {
    if (
      !failed &&
      route.request().method() === 'GET' &&
      uuid.test(new URL(route.request().url()).pathname.split('/').at(-1))
    ) {
      failed = true;
      return route.fulfill({status: 503, json: {error: {code: 'UNAVAILABLE'}}});
    }
    return route.continue();
  });
  await create(p).click();
  await expect(p.getByRole('alert')).toBeVisible();
  await button(p, 'Reload current data').click();
  await expect(saved(p)).toBeVisible();
  expect(failed).toBe(true);
  expect(writes).toBe(1);
});

test('real manager eligibility, stable paging and literal search are team scoped', async ({
  page: p,
}, info) => {
  await login(p, account(info));
  const get = async query => {
    const response = await p.request.get(`${api}/eligible-managers?${query}`);
    expect(response.status()).toBe(200);
    return (await response.json()).data;
  };
  const first = await get('page=0&query=');
  const last = await get('page=1&query=');
  expect(first).toMatchObject({page: 0, size: 20, total: 23});
  expect(last).toMatchObject({page: 1, size: 20, total: 23});
  expect(first.items).toHaveLength(20);
  expect(last.items).toHaveLength(3);
  const items = [...first.items, ...last.items];
  expect(items.map(x => x.id)).toEqual(
    Array.from({length: 23}, (_, n) => managerId(n + 1)),
  );
  expect(
    items.every(
      x => x.level === 'MANAGER' && ['ACTIVE', 'DRAFT'].includes(x.status),
    ),
  ).toBe(true);
  expect((await get('page=0&query=manager%2001')).items.map(x => x.id)).toEqual(
    [managerId(1)],
  );
  for (const query of ['%25', '_', '%5C'])
    expect((await get(`page=0&query=${query}`)).total).toBe(0);
});

test('optional manager, canonical replay, changed-body conflict and parallel replay', async ({
  page: p,
}, info) => {
  await login(p, account(info));
  const data = payload({
    title: `  Canonical ${randomUUID()}  `,
    context: '',
    budgetLimit: 0,
  });
  const key = randomUUID();
  const first = await post(p, data, api, key);
  expect(first.status()).toBe(201);
  const original = (await first.json()).data;
  expect(original).toMatchObject({
    title: data.title.trim(),
    managerAgentId: null,
    context: '',
    budgetLimit: 0,
    status: 'CREATED',
  });
  const normalized = {...data, title: data.title.trim(), context: null};
  const replay = await post(p, normalized, api, key);
  expect(replay.status()).toBe(200);
  expect((await replay.json()).data).toEqual(original);
  const conflict = await post(
    p,
    {...normalized, goal: 'Different goal'},
    api,
    key,
  );
  expect(conflict.status()).toBe(409);
  expect(JSON.stringify(await conflict.json())).toContain(
    'IDEMPOTENCY_CONFLICT',
  );
  const nextKey = randomUUID(),
    next = payload();
  const auth = await headers(p, nextKey);
  const responses = await Promise.all(
    [1, 2].map(() => p.request.post(api, {headers: auth, data: next})),
  );
  expect(responses.map(r => r.status()).sort()).toEqual([200, 201]);
  const bodies = await Promise.all(responses.map(r => r.json()));
  expect(bodies[0].data).toEqual(bodies[1].data);
  expect(await read(p, original.id)).toEqual(original);
});

test('invalid inputs, setup conflicts, tenant denial, CSRF and legacy bypass fail closed', async ({
  page: p,
}, info) => {
  await login(p, account(info));
  for (const invalid of [
    {title: ' '},
    {title: 'x'.repeat(256)},
    {goal: ' '},
    {goal: 'x'.repeat(4001)},
    {requiredOutput: ''},
    {requiredOutput: 'x'.repeat(4001)},
    {context: 'x'.repeat(8001)},
    {budgetLimit: -0.01},
    {budgetLimit: 1000000.01},
    {budgetLimit: 1.001},
    {budgetLimit: '1e2'},
    {deadline: '2026-02-30'},
    {deadline: '2026-1-01'},
    {priority: 'INVALID'},
    {approvalMode: 'AUTO'},
  ])
    expect(
      (await post(p, payload(invalid))).status(),
      JSON.stringify(invalid).slice(0, 100),
    ).toBe(400);
  for (const n of [24, 25, 26]) {
    const response = await post(p, payload({managerAgentId: managerId(n)}));
    expect(response.status()).toBe(409);
    expect(JSON.stringify(await response.json())).toContain(
      'MISSION_SETUP_CONFLICT',
    );
  }
  expect(
    (await post(p, payload(), api.replace(team, teamId(2)))).status(),
  ).toBe(409);
  expect(
    (
      await post(
        p,
        payload(),
        api.replace(team, '17100000-0000-4000-8000-000000000001'),
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await post(
        p,
        payload(),
        api
          .replace(org, foreign)
          .replace(team, '17100000-0000-4000-8000-000000000001'),
      )
    ).status(),
  ).toBe(403);
  expect(
    (
      await p.request.post(api, {
        data: payload(),
        headers: {'Idempotency-Key': randomUUID()},
      })
    ).status(),
  ).toBe(403);
  expect(
    (
      await post(
        p,
        {...payload(), teamId: team},
        `/api/v1/organizations/${org}/missions`,
      )
    ).status(),
  ).toBe(410);
});

test('member may read saved detail but cannot create or enumerate managers; wrong team is hidden', async ({
  page: p,
}, info) => {
  await login(p, account(info));
  const response = await post(p, payload());
  expect(response.status()).toBe(201);
  const data = (await response.json()).data;
  expect(
    (
      await p.request.get(`${api.replace(team, teamId(3))}/${data.id}`)
    ).status(),
  ).toBe(404);
  await login(p, 'mission-member@example.test');
  expect(await read(p, data.id)).toEqual(data);
  await p.goto(url.replace('/new', `/${data.id}`));
  await expect(saved(p)).toContainText(data.title);
  expect((await post(p, payload())).status()).toBe(403);
  expect((await p.request.get(`${api}/eligible-managers`)).status()).toBe(403);
  await p.goto(url);
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(create(p)).toHaveCount(0);
  await expect(field(p, 'Goal')).toHaveCount(0);
});

test('fresh role revocation denies original-key replay and clears private wizard state', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  await goal(p, `Private ${info.project.name} ${randomUUID()}`);
  await review(p);
  const data = payload(),
    key = randomUUID();
  expect((await post(p, data, api, key)).status()).toBe(201);
  const membership = `organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${account(info)}')`;
  try {
    sql(
      `UPDATE organization_memberships SET role='MEMBER' WHERE ${membership};`,
    );
    expect((await post(p, data, api, key)).status()).toBe(403);
    await create(p).click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(create(p)).toHaveCount(0);
    await expect(p.locator('form')).toHaveCount(0);
  } finally {
    sql(
      `UPDATE organization_memberships SET role='ADMIN' WHERE ${membership};`,
    );
  }
});

test('setup rejection unlocks configuration and next submission uses a new key', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  await goal(p, `Setup recovery ${info.project.name} ${randomUUID()}`);
  await review(p);
  const keys = [];
  await p.route(`**${api}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']);
    if (keys.length === 1)
      return route.fulfill({
        status: 409,
        json: {error: {code: 'MISSION_SETUP_CONFLICT'}},
      });
    return route.continue();
  });
  await create(p).click();
  await expect(field(p, 'Priority')).toBeEnabled();
  await field(p, 'Priority').selectOption('URGENT');
  await review(p);
  await create(p).click();
  await expect(saved(p)).toContainText('URGENT');
  expect(keys).toHaveLength(2);
  expect(keys[1]).not.toBe(keys[0]);
});

test('idempotency conflict keeps original review locked and disables resubmission', async ({
  page: p,
}, info) => {
  await wizard(p, info);
  await goal(p, `Key conflict ${info.project.name} ${randomUUID()}`);
  await review(p);
  let writes = 0;
  await p.route(`**${api}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    writes++;
    return route.fulfill({
      status: 409,
      json: {error: {code: 'IDEMPOTENCY_CONFLICT'}},
    });
  });
  await create(p).click();
  await expect(p.getByRole('alert')).toContainText(/conflict/i);
  await expect(button(p, 'Back')).toBeDisabled();
  await expect(create(p)).toBeDisabled();
  expect(writes).toBe(1);
});
