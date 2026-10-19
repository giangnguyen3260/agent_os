import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {randomUUID} from 'node:crypto';
const org = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const api = `/api/v1/organizations/${org}/teams`;
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
async function login(page, email = 'team-admin@example.test') {
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page
    .getByLabel('Mật khẩu', {exact: true})
    .fill(process.env.E2E_PASSWORD);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
}
async function wizard(page) {
  await login(page);
  await page.goto(`/workspace/${org}/teams/new`);
  await expect(
    page.getByRole('heading', {name: 'Create Team / Basics', exact: true}),
  ).toBeVisible();
}
async function basics(page, name) {
  await page.getByLabel('Team name', {exact: true}).fill(name);
  await page
    .getByLabel('Purpose', {exact: true})
    .fill('Deliver tested product features');
  await page
    .getByLabel('Description', {exact: true})
    .fill('Persist reviewed configuration');
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await expect(
    page.getByRole('heading', {name: 'Create Team / Setup', exact: true}),
  ).toBeVisible();
}
async function limits(page) {
  await page.getByLabel('Concurrency', {exact: true}).fill('4');
  await page.getByLabel('Monthly budget (USD)', {exact: true}).fill('100.50');
}
async function review(page) {
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await expect(
    page.getByRole('heading', {name: 'Create Team / Review', exact: true}),
  ).toBeVisible();
}
const create = page =>
  page.getByRole('button', {name: 'Create draft', exact: true});
const success = page =>
  page.getByRole('heading', {name: 'Team draft created', exact: true});

test('validation, Back and Cancel do not create early', async ({
  page,
}, info) => {
  await wizard(page);
  let writes = 0;
  page.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === api) writes++;
  });
  await expect(page.getByLabel('Team name', {exact: true})).toHaveValue('');
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await expect(page.getByLabel('Team name', {exact: true})).toBeFocused();
  await expect(page.getByLabel('Team name', {exact: true})).toHaveAttribute(
    'aria-invalid',
    'true',
  );
  await basics(page, `Cancel ${info.project.name}`);
  await expect(page.getByLabel('Concurrency', {exact: true})).toHaveValue('');
  await expect(
    page.getByLabel('Monthly budget (USD)', {exact: true}),
  ).toHaveValue('');
  await limits(page);
  await page.getByRole('button', {name: 'Back', exact: true}).click();
  await expect(page.getByLabel('Team name', {exact: true})).toHaveValue(
    `Cancel ${info.project.name}`,
  );
  await page.getByRole('button', {name: 'Continue', exact: true}).click();
  await expect(page.getByLabel('Concurrency', {exact: true})).toHaveValue('4');
  await review(page);
  page.once('dialog', d => d.accept());
  await page.getByRole('button', {name: 'Cancel', exact: true}).click();
  await expect(page).toHaveURL(new RegExp(`/workspace/${org}/dashboard$`));
  expect(writes).toBe(0);
});
test('creation persists Draft configuration and scoped agent assignments', async ({
  page,
}, info) => {
  await wizard(page);
  const surface = info.project.name;
  const n = {desktop: 1, tablet: 2, mobile: 3}[surface];
  const manager = `c2000000-0000-4000-8000-00000000000${n}`,
    worker = `c3000000-0000-4000-8000-00000000000${n}`;
  const name = `Delivery ${surface}`;
  await basics(page, name);
  const items = (
    await (await page.request.get(`${api}/eligible-agents`)).json()
  ).data.items;
  expect(items.some(x => x.id === 'c4444444-4444-4444-8444-444444444444')).toBe(
    false,
  );
  expect(items.some(x => x.id === 'f5555555-5555-4555-8555-555555555555')).toBe(
    false,
  );
  await page.getByLabel('Tìm agent', {exact: true}).fill(surface);
  await page.getByLabel('Manager', {exact: true}).selectOption(manager);
  await expect(page.getByLabel('Manager', {exact: true})).toHaveValue(manager);
  await page
    .getByRole('checkbox', {name: new RegExp(`QC ${surface} worker`)})
    .check();
  await limits(page);
  await review(page);
  await create(page).click();
  await expect(success(page)).toBeVisible();
  const id = new URL(page.url()).pathname.split('/').at(-1);
  expect(id).toMatch(/^[0-9a-f-]{36}$/);
  const data = (await (await page.request.get(`${api}/${id}`)).json()).data;
  expect(data).toMatchObject({
    name,
    purpose: 'Deliver tested product features',
    description: 'Persist reviewed configuration',
    status: 'DRAFT',
    managerAgentId: manager,
    agentIds: [worker],
    intake: 'MANUAL',
    concurrency: 4,
    monthlyBudget: 100.5,
    currency: 'USD',
  });
  expect(
    sql(
      `SELECT count(*) FROM agents WHERE team_id='${id}' AND id IN ('${manager}','${worker}');`,
    ),
  ).toBe('2');
  await page.reload();
  await expect(success(page)).toBeVisible();
  await expect(page.getByTestId('saved-team')).toContainText(name);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});
test('ambiguous result retries immutable key without duplicate team', async ({
  page,
}, info) => {
  await wizard(page);
  await basics(page, `Retry ${info.project.name}`);
  await limits(page);
  await review(page);
  const keys = [];
  let id;
  await page.route(`**${api}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']);
    const response = await route.fetch();
    const body = await response.json();
    if (keys.length === 1) {
      expect(response.status()).toBe(201);
      id = body.data.id;
      if (info.project.name === 'desktop') await route.abort('failed');
      else if (info.project.name === 'tablet')
        await route.fulfill({
          status: 503,
          json: {error: {code: 'UNAVAILABLE'}},
        });
      else await route.fulfill({status: 201, json: {data: {}}});
    } else {
      expect(response.status()).toBe(200);
      expect(body.data.id).toBe(id);
      await route.fulfill({response});
    }
  });
  await create(page).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Back', exact: true}),
  ).toBeDisabled();
  await create(page).click();
  await expect(success(page)).toBeVisible();
  expect(keys).toHaveLength(2);
  expect(keys[0]).toBeTruthy();
  expect(new Set(keys).size).toBe(1);
  expect(sql(`SELECT count(*) FROM teams WHERE id='${id}';`)).toBe('1');
});
test('member writes and missing CSRF are denied', async ({page}) => {
  await login(page, 'team-member@example.test');
  await page.goto(`/workspace/${org}/teams/new`);
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(create(page)).toHaveCount(0);
  const csrf = (await (await page.request.get('/api/v1/auth/csrf')).json())
    .data;
  const data = {
    name: 'Denied',
    purpose: 'No write',
    agentIds: [],
    intake: 'MANUAL',
    concurrency: 1,
    monthlyBudget: 0,
  };
  expect(
    (
      await page.request.post(api, {
        data,
        headers: {
          [csrf.headerName]: csrf.token,
          'Idempotency-Key': randomUUID(),
        },
      })
    ).status(),
  ).toBe(403);
  await login(page);
  expect(
    (
      await page.request.post(api, {
        data,
        headers: {'Idempotency-Key': randomUUID()},
      })
    ).status(),
  ).toBe(403);
});
test('revoked admin cannot save and private fields are cleared', async ({
  page,
}, info) => {
  await wizard(page);
  await basics(page, `Revoked ${info.project.name}`);
  await limits(page);
  await review(page);
  try {
    sql(
      `UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='team-admin@example.test');`,
    );
    await create(page).click();
    await expect(create(page)).toHaveCount(0);
    await expect(page.getByTestId('team-step')).toHaveCount(0);
  } finally {
    sql(
      `UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='team-admin@example.test');`,
    );
  }
});
test('failed detail GET after confirmed creation retries only the read', async ({
  page,
}, info) => {
  await wizard(page);
  await basics(page, `Read retry ${info.project.name}`);
  await limits(page);
  await review(page);
  let writes = 0,
    failed = false;
  page.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === api) writes++;
  });
  await page.route(`**${api}/*`, async route => {
    if (
      route.request().method() === 'GET' &&
      /[0-9a-f-]{36}$/.test(new URL(route.request().url()).pathname) &&
      !failed
    ) {
      failed = true;
      await route.fulfill({status: 503, json: {error: {code: 'UNAVAILABLE'}}});
    } else await route.continue();
  });
  await create(page).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', {name: 'Thử lại', exact: true}).click();
  await expect(success(page)).toBeVisible();
  expect(writes).toBe(1);
});
