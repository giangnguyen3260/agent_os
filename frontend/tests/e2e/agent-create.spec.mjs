import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';

const org = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const team = 'c1111111-1111-4111-8111-111111111111';
const api = `/api/v1/organizations/${org}/agents`;
const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const fields = {
  name: 'Agent name',
  role: 'Role',
  description: 'Description',
  responsibilities: 'Responsibilities',
  expectedInput: 'Expected input',
  outputFormat: 'Output format',
  acceptanceCriteria: 'Acceptance criteria',
  model: 'Model',
  skills: 'Skills',
  tools: 'Tools / requested scope',
  limits: 'Limits (requested)',
};
const input = (page, key) => page.getByLabel(fields[key], {exact: true});
const button = (page, name) => page.getByRole('button', {name, exact: true});
const create = page => button(page, 'Create draft');
const success = page =>
  page.getByRole('heading', {name: 'Agent draft created', exact: true});
const step = (page, name) =>
  expect(
    page.getByRole('heading', {
      name: `Create Agent / ${name}`,
      exact: true,
    }),
  ).toBeVisible();
function config(name, teamId = null) {
  return {
    name,
    role: 'Reviewer',
    teamId,
    description: 'Review configuration\nwithout activation',
    responsibilities: 'Review changes and report concrete defects',
    expectedInput: 'A proposed change and its acceptance criteria',
    outputFormat: 'A concise list of verified findings',
    acceptanceCriteria: 'Every finding includes reproducible evidence',
    model: 'Requested model',
    skills: 'Code review\nTest design',
    tools: 'Read repository only',
    limits: 'No provider calls; requested budget 0',
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
  await button(page, 'Đăng nhập').click();
  await expect(page).toHaveURL(/\/workspace$/);
}
async function wizard(page) {
  await login(page);
  await page.goto(`/workspace/${org}/agents/new`);
  await step(page, 'Identity');
}
async function fill(page, data, keys) {
  for (const key of keys) await input(page, key).fill(data[key]);
}
async function identity(page, data) {
  await fill(page, data, ['name', 'role', 'description']);
  if (data.teamId) {
    const response = await page.request.get(
      `${api}/eligible-teams?size=20&page=0&query=Existing%20busy%20team`,
    );
    expect(response.status()).toBe(200);
    expect((await response.json()).data.items).toEqual(
      expect.arrayContaining([
        expect.objectContaining({id: team, status: 'ACTIVE'}),
      ]),
    );
    await page.getByLabel('Tìm team', {exact: true}).fill('Existing busy team');
    await expect(
      page.getByLabel('Team', {exact: true}).getByRole('option', {
        name: /Existing busy team/,
      }),
    ).toHaveAttribute('value', data.teamId);
    await page.getByLabel('Team', {exact: true}).selectOption(data.teamId);
    await expect(page.getByLabel('Team', {exact: true})).toHaveValue(
      data.teamId,
    );
  }
  await button(page, 'Continue').click();
  await step(page, 'Contract');
}
async function contract(page, data) {
  await fill(page, data, [
    'responsibilities',
    'expectedInput',
    'outputFormat',
    'acceptanceCriteria',
  ]);
  await button(page, 'Continue').click();
  await step(page, 'Capabilities');
}
async function review(page, data) {
  await fill(page, data, ['model', 'skills', 'tools', 'limits']);
  await button(page, 'Continue').click();
  await step(page, 'Review & test');
  await expect(page.getByText(/not granted/i)).toBeVisible();
  await expect(page.getByText(/not enforced/i)).toBeVisible();
  await expect(button(page, 'Run optional sandbox test')).toBeDisabled();
  await expect(page.getByText(/Sandbox test unavailable\./)).toBeVisible();
}
async function ready(page, data) {
  await wizard(page);
  await identity(page, data);
  await contract(page, data);
  await review(page, data);
}
function posts(page) {
  const requests = [];
  page.on('request', request => {
    if (request.method() === 'POST' && new URL(request.url()).pathname === api)
      requests.push(request);
  });
  return requests;
}
async function saved(page, data) {
  await expect(success(page)).toBeVisible();
  const id = new URL(page.url()).pathname.split('/').at(-1);
  expect(id).toMatch(uuid);
  const response = await page.request.get(`${api}/${id}`);
  expect(response.status()).toBe(200);
  expect((await response.json()).data).toMatchObject({
    ...data,
    id,
    organizationId: org,
    status: 'DRAFT',
    level: 'MID',
    autonomyLevel: 'SUGGEST',
  });
  await page.reload();
  await expect(success(page)).toBeVisible();
  await expect(page.getByTestId('saved-agent')).toContainText(data.name);
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  return id;
}

test.describe('Agent Draft independent QC', () => {
  test.describe.configure({mode: 'serial'});

  test('delayed URL team prefill cannot change the reviewed unassigned team', async ({
    page,
  }) => {
    await login(page);
    const writes = posts(page);
    const data = config(`Delayed prefill ${randomUUID()}`);
    let release;
    const held = new Promise(resolve => {
      release = resolve;
    });
    let fetched;
    const candidateFetched = new Promise(resolve => {
      fetched = resolve;
    });
    await page.route(`**${api}/eligible-teams?*`, async route => {
      const response = await route.fetch({maxRetries: 0});
      const body = await response.json();
      expect(response.status()).toBe(200);
      expect(body.data.items).toEqual(
        expect.arrayContaining([
          expect.objectContaining({id: team, status: 'ACTIVE'}),
        ]),
      );
      fetched();
      await held;
      await route.fulfill({response});
    });
    try {
      await page.goto(`/workspace/${org}/agents/new?teamId=${team}`);
      await step(page, 'Identity');
      await candidateFetched;
      await expect(page.getByLabel('Team', {exact: true})).toHaveValue('');
      await identity(page, data);
      await contract(page, data);
      await review(page, data);
      const reviewedTeam = page
        .locator('.field')
        .filter({
          has: page.getByRole('heading', {name: 'Team', exact: true}),
        })
        .locator('.review-value');
      await expect(reviewedTeam).toHaveText('Thiết lập sau');
      const delivered = page.waitForResponse(
        response =>
          response.request().method() === 'GET' &&
          new URL(response.url()).pathname === `${api}/eligible-teams`,
      );
      release();
      const response = await delivered;
      await response.finished();
      await page.evaluate(
        () =>
          new Promise(resolve =>
            requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
          ),
      );
      await expect(reviewedTeam).toHaveText('Thiết lập sau');
      for (let i = 0; i < 3; i++) await button(page, 'Back').click();
      await step(page, 'Identity');
      await expect(page.getByLabel('Team', {exact: true})).toHaveValue('');
      expect(writes).toHaveLength(0);
    } finally {
      release();
    }
  });

  test('required validation, Back and Cancel never POST prematurely', async ({
    page,
  }, info) => {
    await wizard(page);
    const writes = posts(page);
    const data = config(`Cancel ${info.project.name} ${randomUUID()}`);
    await expect(input(page, 'name')).toHaveValue('');
    await expect(input(page, 'role')).toHaveValue('');
    await button(page, 'Continue').click();
    await expect(input(page, 'name')).toBeFocused();
    await expect(input(page, 'name')).toHaveAttribute('aria-invalid', 'true');
    await input(page, 'name').fill(data.name);
    await button(page, 'Continue').click();
    await expect(input(page, 'role')).toBeFocused();
    await identity(page, data);
    const required = [
      'responsibilities',
      'expectedInput',
      'outputFormat',
      'acceptanceCriteria',
    ];
    for (const key of required) {
      await expect(input(page, key)).toHaveValue('');
      await button(page, 'Continue').click();
      await expect(input(page, key)).toBeFocused();
      await expect(input(page, key)).toHaveAttribute('aria-invalid', 'true');
      await input(page, key).fill(data[key]);
    }
    await contract(page, data);
    for (const key of ['model', 'skills', 'tools', 'limits'])
      await expect(input(page, key)).toHaveValue('');
    await review(page, data);
    await button(page, 'Back').click();
    await step(page, 'Capabilities');
    await expect(input(page, 'tools')).toHaveValue(data.tools);
    await button(page, 'Back').click();
    await step(page, 'Contract');
    for (const key of required)
      await expect(input(page, key)).toHaveValue(data[key]);
    await button(page, 'Back').click();
    await step(page, 'Identity');
    await expect(input(page, 'name')).toHaveValue(data.name);
    page.once('dialog', dialog => dialog.dismiss());
    await button(page, 'Cancel').click();
    await step(page, 'Identity');
    page.once('dialog', dialog => dialog.accept());
    await button(page, 'Cancel').click();
    await expect(page).toHaveURL(new RegExp(`/workspace/${org}/dashboard$`));
    expect(writes).toHaveLength(0);
  });

  for (const teamId of [null, team]) {
    test(`full configuration persists and reloads (${teamId ? 'selected team' : 'unassigned'})`, async ({
      page,
    }) => {
      // Keep created names out of Team QC's surface-name candidate searches.
      const data = config(`Persist ${randomUUID()}`, teamId);
      const writes = posts(page);
      await ready(page, data);
      expect(writes).toHaveLength(0);
      await create(page).click();
      await saved(page, data);
      expect(writes).toHaveLength(1);
      expect(writes[0].postDataJSON()).toEqual(data);
      expect(writes[0].headers()['idempotency-key']).toMatch(uuid);
    });
  }

  test('successful commit with lost response retries identical key/body without duplicate', async ({
    page,
  }) => {
    const data = config(`Retry ${randomUUID()}`);
    await ready(page, data);
    const attempts = [];
    let id;
    await page.route(`**${api}`, async route => {
      if (route.request().method() !== 'POST') return route.continue();
      attempts.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      const response = await route.fetch({maxRetries: 0});
      const body = await response.json();
      if (attempts.length === 1) {
        expect(response.status()).toBe(201);
        id = body.data.id;
        await route.abort('failed');
      } else {
        expect(response.status()).toBe(200);
        expect(body.data.id).toBe(id);
        await route.fulfill({response});
      }
    });
    await create(page).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(button(page, 'Back')).toBeDisabled();
    await create(page).click();
    expect(await saved(page, data)).toBe(id);
    expect(attempts).toHaveLength(2);
    expect(attempts[0].key).toMatch(uuid);
    expect(attempts[1]).toEqual(attempts[0]);
    const response = await page.request.get(
      `${api}?query=${encodeURIComponent(data.name)}&size=20&page=0`,
    );
    expect(response.status()).toBe(200);
    const result = (await response.json()).data;
    expect(result.total).toBe(1);
    expect(result.items.map(item => item.id)).toEqual([id]);
  });

  test('member and missing CSRF writes are denied by real API', async ({
    page,
  }) => {
    await login(page, 'team-member@example.test');
    await page.goto(`/workspace/${org}/agents/new`);
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(create(page)).toHaveCount(0);
    const csrfResponse = await page.request.get('/api/v1/auth/csrf');
    expect(csrfResponse.status()).toBe(200);
    const csrf = (await csrfResponse.json()).data;
    const data = config(`Denied ${randomUUID()}`);
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
          headers: {
            'Idempotency-Key': randomUUID(),
          },
        })
      ).status(),
    ).toBe(403);
  });

  test('admin revoked during wizard cannot save and private form is cleared', async ({
    page,
  }) => {
    const data = config(`Revoked ${randomUUID()}`);
    await ready(page, data);
    const writes = posts(page);
    try {
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='team-admin@example.test');`,
      );
      const rejected = page.waitForResponse(
        response =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname === api,
      );
      await create(page).click();
      expect((await rejected).status()).toBe(403);
      await expect(page.getByRole('alert')).toBeVisible();
      await expect(create(page)).toHaveCount(0);
      for (const key of Object.keys(fields))
        await expect(input(page, key)).toHaveCount(0);
      await expect(page.getByLabel('Team', {exact: true})).toHaveCount(0);
      await expect(page.getByText(data.name, {exact: true})).toHaveCount(0);
      await expect(success(page)).toHaveCount(0);
      expect(writes).toHaveLength(1);
    } finally {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='team-admin@example.test');`,
      );
    }
    await page.reload();
    await step(page, 'Identity');
    await expect(input(page, 'name')).toHaveValue('');
    await expect(input(page, 'role')).toHaveValue('');
    expect(writes).toHaveLength(1);
  });

  test('detail GET failure retries only the read after confirmed creation', async ({
    page,
  }) => {
    const data = config(`Read retry ${randomUUID()}`);
    await ready(page, data);
    const writes = posts(page);
    let reads = 0;
    await page.route(`**${api}/*`, async route => {
      const request = route.request();
      if (
        request.method() === 'GET' &&
        uuid.test(new URL(request.url()).pathname.split('/').at(-1))
      ) {
        reads++;
        if (reads === 1)
          return route.fulfill({
            status: 503,
            json: {error: {code: 'UNAVAILABLE'}},
          });
      }
      await route.continue();
    });
    await create(page).click();
    await expect(page.getByRole('alert')).toBeVisible();
    await expect(page).toHaveURL(
      new RegExp(`/workspace/${org}/agents/[0-9a-f-]{36}$`),
    );
    await button(page, 'Thử lại').click();
    await expect(success(page)).toBeVisible();
    expect(reads).toBe(2);
    expect(writes).toHaveLength(1);
    await saved(page, data);
    expect(writes).toHaveLength(1);
  });
});
