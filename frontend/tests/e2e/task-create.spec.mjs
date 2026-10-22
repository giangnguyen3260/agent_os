import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const org = '22222222-2323-4232-8232-222222222222';
const foreign = '23232323-2323-4323-8323-232323232323';
const id = (prefix, n) =>
  `${prefix}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const team = id('2210', 1);
const surface = info =>
  ({desktop: 10, tablet: 11, mobile: 12})[info.project.name];
const mission = info => id('2230', surface(info));
const base = m => `/workspace/${org}/teams/${team}/missions/${m}`;
const api = m =>
  `/api/v1/organizations/${org}/teams/${team}/missions/${m}/tasks`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const field = (p, name) =>
  p.getByLabel(name === 'Description' ? 'Description / input' : name, {
    exact: true,
  });
const create = p =>
  p.getByRole('button', {name: /^(?:Create task|Retry creation)$/});
const uuid =
  /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
async function login(p, member = false) {
  expect(process.env.E2E_PASSWORD, 'fixture password required').toBeTruthy();
  await p.goto('/login');
  await field(p, 'Email').fill(
    `task-create-${member ? 'member' : 'admin'}@example.test`,
  );
  await field(p, 'Mật khẩu').fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function context(p, m) {
  const response = await p.request.get(`${api(m)}/creation-context`);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
async function headers(p, key = randomUUID()) {
  const response = await p.request.get('/api/v1/auth/csrf');
  expect(response.status()).toBe(200);
  const csrf = (await response.json()).data;
  return {[csrf.headerName]: csrf.token, 'Idempotency-Key': key};
}
const payload = (version, overrides = {}) => ({
  title: `Task QC ${randomUUID()}`,
  description: 'Persisted task description',
  acceptanceCriteria: 'The saved configuration is readable',
  priority: 'NORMAL',
  ownerAgentId: null,
  dependencyIds: [],
  expectedIntakeVersion: version,
  ...overrides,
});
async function post(p, m, body, key = randomUUID(), path = api(m)) {
  return p.request.post(path, {data: body, headers: await headers(p, key)});
}
async function read(p, m, taskId) {
  const response = await p.request.get(`${api(m)}/${taskId}`);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}
async function open(p, info) {
  await login(p);
  await p.goto(`${base(mission(info))}/tasks/new`);
  await expect(field(p, 'Title')).toBeVisible();
}
async function fill(p, title) {
  await field(p, 'Title').fill(title);
  await field(p, 'Description').fill('Persisted task description');
  await field(p, 'Acceptance criteria').fill(
    'The saved configuration is readable',
  );
}
async function review(p) {
  await expect(create(p)).toBeVisible();
}
async function detail(p, title) {
  await expect(
    p.getByRole('heading', {name: title, exact: true}),
  ).toBeVisible();
  await expect(p.getByTestId('task-detail')).toHaveAttribute(
    'aria-busy',
    'false',
  );
}
async function candidateAction(
  p,
  m,
  owners,
  page,
  query,
  action,
  status = 200,
) {
  const endpoint = `${api(m)}/eligible-${owners ? 'owners' : 'dependencies'}`;
  const [response] = await Promise.all([
    p.waitForResponse(r => {
      const url = new URL(r.url());
      return (
        r.request().method() === 'GET' &&
        url.pathname === endpoint &&
        url.searchParams.get('page') === String(page) &&
        url.searchParams.get('query') === query
      );
    }),
    action(),
  ]);
  expect(response.status()).toBe(status);
}
async function noOverflow(p) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
}

test('TC01 required/maxlength validation, Cancel and keyboard focus never POST', async ({
  page: p,
}, info) => {
  await open(p, info);
  const writes = [];
  p.on('request', r => {
    if (
      r.method() === 'POST' &&
      new URL(r.url()).pathname === api(mission(info))
    )
      writes.push(r);
  });
  await field(p, 'Title').fill('   ');
  await create(p).click();
  await expect(field(p, 'Title')).toBeVisible();
  for (const [name, max] of [
    ['Title', 255],
    ['Description', 8000],
    ['Acceptance criteria', 4000],
  ]) {
    await expect(field(p, name)).toHaveAttribute('maxlength', String(max));
    await field(p, name).fill('x'.repeat(max + 1));
    expect((await field(p, name).inputValue()).length).toBeLessThanOrEqual(max);
  }
  await fill(p, `Cancel ${info.project.name}`);
  await review(p);
  await button(p, 'Cancel').focus();
  await expect(button(p, 'Cancel')).toBeFocused();
  p.once('dialog', d => d.dismiss());
  await p.keyboard.press('Enter');
  await expect(create(p)).toBeVisible();
  expect(
    await p.evaluate(() => {
      const event = new Event('beforeunload', {cancelable: true});
      window.dispatchEvent(event);
      return event.defaultPrevented;
    }),
  ).toBe(true);
  await noOverflow(p);
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    await expect(
      p.getByRole('dialog', {name: 'Workspace navigation', exact: true}),
    ).toBeVisible();
    await p.keyboard.press('Escape');
    await expect(button(p, 'Menu')).toBeFocused();
  }
  p.once('dialog', d => d.accept());
  await button(p, 'Cancel').click();
  await expect(p).not.toHaveURL(/\/tasks\/new$/);
  expect(writes).toHaveLength(0);
});

test('TC02 native creation persists detail and appears in Mission Control and Team Intake', async ({
  page: p,
}, info) => {
  await open(p, info);
  const m = mission(info),
    title = `Saved ${info.project.name} ${randomUUID()}`;
  await fill(p, `  ${title}  `);
  await field(p, 'Priority').selectOption('HIGH');
  await candidateAction(p, m, true, 0, '', () =>
    button(p, 'Select owner').click(),
  );
  await p
    .getByRole('dialog', {name: 'Select owner', exact: true})
    .getByRole('button', {name: /Task Owner 01 · DRAFT/})
    .click();
  await expect(button(p, 'Select owner')).toBeFocused();
  await candidateAction(p, m, false, 0, '', () =>
    button(p, 'Select dependencies').click(),
  );
  const chooser = p.getByRole('dialog', {
    name: 'Select dependencies',
    exact: true,
  });
  await chooser.getByRole('button', {name: /Dependency 01 · DRAFT/}).click();
  await chooser.getByRole('button', {name: 'Done', exact: true}).click();
  await expect(button(p, 'Select dependencies')).toBeFocused();
  await review(p);
  const pending = p.waitForResponse(
    r =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === api(m),
  );
  await create(p).click();
  const response = await pending;
  expect(response.status()).toBe(201);
  const saved = (await response.json()).data;
  expect(saved.task.id).toMatch(uuid);
  expect(saved.task).toMatchObject({
    organizationId: org,
    teamId: team,
    missionId: m,
    title,
    status: 'DRAFT',
    description: 'Persisted task description',
    acceptanceCriteria: 'The saved configuration is readable',
    priority: 'HIGH',
    ownerAgentId: id('2220', 1),
    ownerName: 'Task Owner 01',
    dependencyIds: [id('2240', surface(info) * 100 + 1)],
  });
  await expect(p).toHaveURL(`${base(m)}/tasks/${saved.task.id}`);
  await detail(p, title);
  expect((await read(p, m, saved.task.id)).task).toEqual(saved.task);
  await p.reload();
  await detail(p, title);
  await noOverflow(p);
  await p.getByRole('link', {name: 'Back to mission', exact: true}).click();
  await expect(p.getByRole('link', {name: title, exact: true})).toBeVisible();
  const intakePath = `/api/v1/organizations/${org}/teams/${team}/workspace/tasks`;
  const intakeResponse = page =>
    p.waitForResponse(r => {
      const url = new URL(r.url());
      return (
        r.request().method() === 'GET' &&
        url.pathname === intakePath &&
        url.searchParams.get('lane') === 'INTAKE' &&
        url.searchParams.get('page') === String(page)
      );
    });
  const intake = p.getByTestId('workspace-tasks');
  const [initialIntake] = await Promise.all([
    intakeResponse(0),
    p.goto(
      `/workspace/${org}/teams/${team}/workspace?tab=overview&lane=INTAKE`,
    ),
  ]);
  expect(initialIntake.status()).toBe(200);
  await expect(p.getByTestId('team-workspace')).toHaveAttribute(
    'aria-busy',
    'false',
  );
  await expect(intake).toBeVisible();
  // Inspect each fully loaded page before requesting the next one.
  for (
    let page = 1;
    page <= 10 &&
    !(await intake.getByTestId('task-row').filter({hasText: title}).count());
    page++
  ) {
    await expect(button(p, 'Next')).toBeEnabled();
    const [response] = await Promise.all([
      intakeResponse(page),
      button(p, 'Next').click(),
    ]);
    expect(response.status()).toBe(200);
    await expect(p).toHaveURL(
      url =>
        url.pathname === `/workspace/${org}/teams/${team}/workspace` &&
        url.searchParams.get('tab') === 'overview' &&
        url.searchParams.get('lane') === 'INTAKE' &&
        url.searchParams.get('page') === String(page),
    );
    await expect(p.getByTestId('team-workspace')).toHaveAttribute(
      'aria-busy',
      'false',
    );
    await expect(intake).toBeVisible();
  }
  await expect(
    p.getByTestId('task-row').filter({hasText: title}),
  ).toContainText('DRAFT');
});

test('TC03 lost response after real commit retries identical body/key and original identity', async ({
  page: p,
}, info) => {
  await open(p, info);
  const m = mission(info),
    title = `Ambiguous ${info.project.name} ${randomUUID()}`;
  await fill(p, title);
  await review(p);
  const requests = [];
  let taskId;
  await p.route(`**${api(m)}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    requests.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postData(),
    });
    const response = await route.fetch({maxRetries: 0});
    const data = (await response.json()).data;
    if (requests.length === 1) {
      expect(response.status()).toBe(201);
      taskId = data.task.id;
      if (info.project.name === 'desktop') return route.abort('failed');
      if (info.project.name === 'tablet')
        return route.fulfill({
          status: 503,
          json: {error: {code: 'UNAVAILABLE'}},
        });
      return route.fulfill({status: 201, json: {data: {}}});
    }
    expect(response.status()).toBe(200);
    expect(data.task.id).toBe(taskId);
    return route.fulfill({response});
  });
  await create(p).click();
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(field(p, 'Title')).toBeDisabled();
  await expect(p.getByRole('heading', {name: title, exact: true})).toHaveCount(
    0,
  );
  await create(p).click();
  await detail(p, title);
  expect(requests).toHaveLength(2);
  expect(requests[0].key).toMatch(uuid);
  expect(requests[1]).toEqual(requests[0]);
  expect((await read(p, m, taskId)).task.title).toBe(title);
});

test('TC04 native candidates are stable 20+5, searchable, eligible and scoped', async ({
  page: p,
}, info) => {
  await login(p);
  const m = mission(info);
  for (const [endpoint, prefix] of [
    ['eligible-owners', '2220'],
    ['eligible-dependencies', '2240'],
  ]) {
    const get = async query => {
      const r = await p.request.get(`${api(m)}/${endpoint}?${query}`);
      expect(r.status()).toBe(200);
      return (await r.json()).data;
    };
    const filter = endpoint === 'eligible-dependencies' ? 'Dependency' : '';
    const first = await get(`page=0&query=${filter}`),
      last = await get(`page=1&query=${filter}`);
    expect(first).toMatchObject({page: 0, size: 20, total: 25});
    expect(last).toMatchObject({page: 1, size: 20, total: 25});
    expect(first.items).toHaveLength(20);
    expect(last.items).toHaveLength(5);
    expect([...first.items, ...last.items].map(x => x.id)).toEqual(
      Array.from({length: 25}, (_, n) =>
        id(prefix, n + 1 + (prefix === '2240' ? surface(info) * 100 : 0)),
      ),
    );
    const term =
      endpoint === 'eligible-owners' ? 'task owner 01' : 'dependency 01';
    expect(
      (await get(`page=0&query=${encodeURIComponent(term)}`)).items.map(
        x => x.id,
      ),
    ).toEqual([id(prefix, 1 + (prefix === '2240' ? surface(info) * 100 : 0))]);
    for (const term of ['%', '_', '\\'])
      expect(
        (await get(`page=0&query=${encodeURIComponent(term)}`)).total,
      ).toBe(0);
  }
  await p.goto(`${base(m)}/tasks/new`);
  await expect(field(p, 'Title')).toBeVisible();
  await candidateAction(p, m, true, 0, '', () =>
    button(p, 'Select owner').click(),
  );
  const owners = p.getByRole('dialog', {name: 'Select owner', exact: true});
  await candidateAction(p, m, true, 1, '', () =>
    owners.getByRole('button', {name: 'Next', exact: true}).click(),
  );
  await expect(
    owners.getByRole('button', {name: /Task Owner 25/}),
  ).toBeVisible();
  await owners.getByLabel('Search owners', {exact: true}).fill('Task Owner 01');
  await candidateAction(p, m, true, 0, 'Task Owner 01', () =>
    owners.getByRole('button', {name: 'Search', exact: true}).click(),
  );
  await expect(
    owners.getByRole('button', {name: /Task Owner 01/}),
  ).toBeVisible();
  await expect(owners.getByRole('button', {name: /Task Owner 25/})).toHaveCount(
    0,
  );
  await p.keyboard.press('Escape');
  await expect(button(p, 'Select owner')).toBeFocused();
  await candidateAction(p, m, false, 0, '', () =>
    button(p, 'Select dependencies').click(),
  );
  const dependencies = p.getByRole('dialog', {
    name: 'Select dependencies',
    exact: true,
  });
  await dependencies
    .getByLabel('Search dependencies', {exact: true})
    .fill('Dependency');
  await candidateAction(p, m, false, 0, 'Dependency', () =>
    dependencies.getByRole('button', {name: 'Search', exact: true}).click(),
  );
  for (let n = 1; n <= 20; n++)
    await dependencies
      .getByRole('button', {
        name: new RegExp(`Dependency ${String(n).padStart(2, '0')} · DRAFT`),
      })
      .click();
  await candidateAction(p, m, false, 1, 'Dependency', () =>
    dependencies.getByRole('button', {name: 'Next', exact: true}).click(),
  );
  await expect(
    dependencies.getByRole('button', {name: /Dependency 21 · DRAFT/}),
  ).toBeDisabled();
  await p.keyboard.press('Escape');
  await expect(button(p, 'Select dependencies')).toBeFocused();
  p.once('dialog', d => d.accept());
  await button(p, 'Cancel').click();
});

test('TC05 native validation, setup conflicts, CSRF and changed-key-body fail without writes', async ({
  page: p,
}, info) => {
  await login(p);
  const m = mission(info),
    before = await context(p, m),
    version = before.intakeVersion;
  for (const invalid of [
    {title: ' '},
    {title: 'x'.repeat(256)},
    {description: ' '},
    {description: 'x'.repeat(8001)},
    {acceptanceCriteria: ' '},
    {acceptanceCriteria: 'x'.repeat(4001)},
    {priority: 'INVALID'},
    {dependencyIds: Array.from({length: 21}, (_, n) => id('2240', n + 1))},
    {expectedIntakeVersion: -1},
    {expectedIntakeVersion: '0'},
    {expectedIntakeVersion: 0.5},
    {unexpected: true},
  ]) {
    expect(
      (await post(p, m, payload(version, invalid))).status(),
      JSON.stringify(invalid).slice(0, 90),
    ).toBe(400);
  }
  for (const invalid of [
    {ownerAgentId: id('2220', 26)},
    {ownerAgentId: id('2220', 27)},
    {ownerAgentId: id('2320', 1)},
    {dependencyIds: [id('2240', 1)]},
    {dependencyIds: [id('2340', 1)]},
  ]) {
    const r = await post(p, m, payload(version, invalid));
    expect(r.status()).toBe(409);
    expect(JSON.stringify(await r.json())).toContain('TASK_SETUP_CONFLICT');
  }
  expect(
    (
      await p.request.post(api(m), {
        data: payload(version),
        headers: {'Idempotency-Key': randomUUID()},
      })
    ).status(),
  ).toBe(403);
  expect((await context(p, m)).intakeVersion).toBe(version);
  const key = randomUUID(),
    body = payload(version);
  const first = await post(p, m, body, key);
  expect(first.status()).toBe(201);
  const saved = (await first.json()).data;
  expect((await post(p, m, body, key)).status()).toBe(200);
  const changed = await post(
    p,
    m,
    {...body, title: `${body.title} changed`},
    key,
  );
  expect(changed.status()).toBe(409);
  expect(JSON.stringify(await changed.json())).toContain(
    'IDEMPOTENCY_CONFLICT',
  );
  expect((await context(p, m)).intakeVersion).toBe(version + 1);
  expect((await read(p, m, saved.task.id)).task.title).toBe(body.title);
});

test('TC06 native scope/member denial clears private form while saved detail remains readable', async ({
  page: p,
}, info) => {
  await login(p);
  const m = mission(info),
    body = payload((await context(p, m)).intakeVersion);
  const created = await post(p, m, body);
  expect(created.status()).toBe(201);
  const saved = (await created.json()).data;
  for (const [path, status] of [
    [api(m).replace(team, id('2210', 3)), 404],
    [api(m).replace(m, id('2230', 4)), 404],
    [
      api(m)
        .replace(org, foreign)
        .replace(team, id('2310', 1))
        .replace(m, id('2330', 1)),
      403,
    ],
  ]) {
    expect((await p.request.get(`${path}/creation-context`)).status()).toBe(
      status,
    );
    expect((await post(p, m, body, randomUUID(), path)).status()).toBe(status);
  }
  await login(p, true);
  expect((await read(p, m, saved.task.id)).task.title).toBe(body.title);
  expect((await post(p, m, body)).status()).toBe(403);
  for (const endpoint of [
    'creation-context',
    'eligible-owners',
    'eligible-dependencies',
  ])
    expect((await p.request.get(`${api(m)}/${endpoint}`)).status()).toBe(403);
  await p.goto(`${base(m)}/tasks/new`);
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(field(p, 'Title')).toHaveCount(0);
  await expect(create(p)).toHaveCount(0);
  await p.goto(`${base(m)}/workspace`);
  await expect(button(p, 'Add Task')).toBeDisabled();
});

test('TC07 paused/terminal metadata and native stale version reject then fresh context succeeds', async ({
  page: p,
}, info) => {
  await login(p);
  for (const n of [2, 3]) {
    const m = id('2230', n),
      ctx = await context(p, m);
    expect(ctx.canCreate).toBe(false);
    if (n === 2) expect(ctx.intakePaused).toBe(true);
    const r = await post(p, m, payload(ctx.intakeVersion));
    expect(r.status()).toBe(409);
    await p.goto(`${base(m)}/tasks/new`);
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(create(p)).toBeDisabled();
  }
  const m = mission(info),
    old = await context(p, m);
  await p.goto(`${base(m)}/tasks/new`);
  await expect(field(p, 'Title')).toBeVisible();
  expect((await post(p, m, payload(old.intakeVersion))).status()).toBe(201);
  const rejectedTitle = `Stale ${randomUUID()}`;
  const stale = await post(
    p,
    m,
    payload(old.intakeVersion, {title: rejectedTitle}),
  );
  expect(stale.status()).toBe(409);
  expect(JSON.stringify(await stale.json())).toContain('TASK_INTAKE_CONFLICT');
  const fresh = await context(p, m);
  expect(fresh.intakeVersion).toBe(old.intakeVersion + 1);
  await fill(p, rejectedTitle);
  const rejected = p.waitForResponse(
    r =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === api(m),
  );
  await create(p).click();
  const conflict = await rejected;
  expect(conflict.status()).toBe(409);
  const oldKey = conflict.request().headers()['idempotency-key'];
  await expect(field(p, 'Title')).toBeEnabled();
  await expect(field(p, 'Title')).toHaveValue(rejectedTitle);
  const accepted = p.waitForResponse(
    r =>
      r.request().method() === 'POST' && new URL(r.url()).pathname === api(m),
  );
  await create(p).click();
  const retry = await accepted;
  expect(retry.status()).toBe(201);
  expect(retry.request().headers()['idempotency-key']).not.toBe(oldKey);
  expect(retry.request().postDataJSON().expectedIntakeVersion).toBe(
    fresh.intakeVersion,
  );
  await detail(p, rejectedTitle);
});

test('TC08 context failure shows no fabricated form and native reload recovers', async ({
  page: p,
}, info) => {
  await login(p);
  const m = mission(info),
    pattern = `**${api(m)}/creation-context`;
  for (const mode of ['unavailable', 'malformed']) {
    await p.route(pattern, route =>
      mode === 'unavailable'
        ? route.fulfill({status: 503, json: {}})
        : route.fulfill({status: 200, json: {data: {canCreate: true}}}),
    );
    await p.goto(`${base(m)}/tasks/new`);
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(field(p, 'Title')).toHaveCount(0);
    await expect(create(p)).toHaveCount(0);
    await expect(p.getByTestId('task-detail')).toHaveCount(0);
    await p.unroute(pattern);
    await p.reload();
    await expect(field(p, 'Title')).toBeVisible();
    await noOverflow(p);
  }
  const candidatesPattern = `**${api(m)}/eligible-owners?*`;
  await p.route(candidatesPattern, route =>
    route.fulfill({status: 503, json: {}}),
  );
  await candidateAction(
    p,
    m,
    true,
    0,
    '',
    () => button(p, 'Select owner').click(),
    503,
  );
  const picker = p.getByRole('dialog', {name: 'Select owner', exact: true});
  await expect(picker.getByRole('alert')).toBeVisible();
  await expect(picker.getByRole('button', {name: /Task Owner/})).toHaveCount(0);
  await p.unroute(candidatesPattern);
  await candidateAction(p, m, true, 0, '', () =>
    picker.getByRole('button', {name: 'Try again', exact: true}).click(),
  );
  await expect(
    picker.getByRole('button', {name: /Task Owner 01/}),
  ).toBeVisible();
  await p.keyboard.press('Escape');
  await expect(button(p, 'Select owner')).toBeFocused();
});
