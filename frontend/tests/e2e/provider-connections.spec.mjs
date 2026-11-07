import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const organization = '71a00000-0000-4000-8000-000000000001';
const actor = '71a00000-0000-4000-8000-000000000002';
const url = `/workspace/${organization}/provider-connections`;
const api = `**/api/v1/organizations/${organization}/provider-connections?*`;
const envelope = data => ({data, meta: {requestId: randomUUID()}});

async function session(page) {
  await page.route('**/api/v1/auth/session', route =>
    route.fulfill({
      json: envelope({
        id: actor,
        email: 'provider-fixture@example.test',
        displayName: 'Provider fixture',
      }),
    }),
  );
}

function connection(index) {
  return {
    organizationId: organization,
    id: randomUUID(),
    provider: 'OPENAI',
    credentialMode: 'OPENAI_API_KEY',
    name: `Kết nối ${index}`,
    options: {},
    state: index === 21 ? 'DISABLED' : 'ENABLED',
    revision: '1',
    credentialRevision: '1',
    credentialState: 'UNAVAILABLE',
    credentialMask: null,
    createdAt: '2026-11-04T00:00:00.000000Z',
    updatedAt: '2026-11-04T00:00:00.000000Z',
  };
}

test('provider list browser contract renders pages without horizontal overflow', async ({
  page,
}) => {
  await session(page);
  const rows = Array.from({length: 21}, (_, i) => connection(i + 1));
  rows[0].name = 'X'.repeat(100);
  rows[0].credentialState = 'PRESENT';
  rows[0].credentialMask = '••••••••';
  await page.route(api, route => {
    const index = Number(
      new URL(route.request().url()).searchParams.get('page'),
    );
    return route.fulfill({
      json: envelope({
        organizationId: organization,
        page: index,
        pageSize: 20,
        total: rows.length,
        items: rows.slice(index * 20, (index + 1) * 20),
      }),
    });
  });
  await page.goto(url);
  await expect(
    page.getByRole('heading', {name: 'Kết nối model'}),
  ).toBeVisible();
  const list = page.getByRole('region', {name: 'Danh sách kết nối'});
  await expect(list.getByRole('article')).toHaveCount(20);
  await expect(list.getByText('••••••••', {exact: true})).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(page.getByRole('button', {name: 'Trang trước'})).toBeDisabled();
  await page.getByRole('link', {name: 'Trang sau'}).click();
  await expect(list.getByRole('article')).toHaveCount(1);
  await expect(list.getByRole('heading', {name: 'Kết nối 21'})).toBeVisible();
  await expect(list.getByText('Đã vô hiệu hóa')).toBeVisible();
  await expect(
    list.getByText('Chưa có bằng chứng lưu trữ khóa hợp lệ'),
  ).toBeVisible();
  await page.getByRole('button', {name: 'Xem kết nối Kết nối 21'}).click();
  await expect(list.getByText('Phiên bản cấu hình: 1')).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('provider list browser contract rejects foreign rows without displaying their data', async ({
  page,
}) => {
  await session(page);
  await page.route(api, route =>
    route.fulfill({
      json: envelope({
        organizationId: organization,
        page: 0,
        pageSize: 20,
        total: 1,
        items: [{...connection(1), organizationId: randomUUID()}],
      }),
    }),
  );
  await page.goto(url);
  await expect(page.getByRole('alert')).toContainText(
    'Không thể tải danh sách kết nối',
  );
  await expect(page.getByRole('article')).toHaveCount(0);
});

test('provider list browser contract distinguishes an empty page from denied access', async ({
  page,
}) => {
  await session(page);
  let denied = false;
  await page.route(api, route =>
    denied
      ? route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}})
      : route.fulfill({
          json: envelope({
            organizationId: organization,
            page: 0,
            pageSize: 20,
            total: 0,
            items: [],
          }),
        }),
  );
  await page.goto(url);
  await expect(page.getByText('Chưa có kết nối provider.')).toBeVisible();
  denied = true;
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('Bạn cần quyền ADMIN');
  await expect(page.getByText('Chưa có kết nối provider.')).toHaveCount(0);
});

test('provider Create recovers an unknown result without sending the API key again', async ({
  page,
}) => {
  await session(page);
  const collection = `/api/v1/organizations/${organization}/provider-connections`;
  const secret = 'synthetic-create-browser-api-key';
  const csrf = 'synthetic-create-csrf';
  const saved = {...connection(1), name: 'Provider recovery fixture'};
  const {credentialState, credentialMask, ...snapshot} = saved;
  const receipt = {
    organizationId: organization,
    commandId: randomUUID(),
    operation: 'CREATE',
    connectionId: saved.id,
    actorId: actor,
    committedAt: saved.createdAt,
    before: null,
    after: snapshot,
    checkId: null,
  };
  const submissions = [];
  const lookups = [];
  let showSaved = false;
  await page.route(api, route =>
    route.fulfill({
      json: envelope({
        organizationId: organization,
        page: 0,
        pageSize: 20,
        total: showSaved ? 1 : 0,
        items: showSaved ? [saved] : [],
      }),
    }),
  );
  await page.route(`**${collection}/options`, route =>
    route.fulfill({
      headers: {'X-Provider-Create-Enabled': 'true'},
      json: envelope({
        organizationId: organization,
        policyRevision: '1',
        providers: [
          {
            provider: 'OPENAI',
            credentialMode: 'OPENAI_API_KEY',
            enabled: true,
            optionsSchema: 'EMPTY',
          },
          {
            provider: 'ANTHROPIC',
            credentialMode: 'ANTHROPIC_API_KEY',
            enabled: false,
            optionsSchema: 'ANTHROPIC_WORKSPACE',
          },
          {
            provider: 'GOOGLE_GEMINI',
            credentialMode: 'GEMINI_API_KEY',
            enabled: false,
            optionsSchema: 'EMPTY',
          },
        ],
      }),
    }),
  );
  await page.route('**/api/v1/auth/csrf', route =>
    route.fulfill({
      json: envelope({headerName: 'X-CSRF-TOKEN', token: csrf}),
    }),
  );
  await page.route(`**${collection}`, async route => {
    const request = route.request();
    submissions.push({
      method: request.method(),
      key: request.headers()['idempotency-key'],
      csrf: request.headers()['x-csrf-token'],
      body: request.postDataJSON(),
    });
    await route.abort('failed');
  });
  await page.route(`**${collection}/receipts/*`, async route => {
    const request = route.request();
    lookups.push({
      method: request.method(),
      url: new URL(request.url()),
      headers: request.headers(),
      body: request.postData(),
    });
    showSaved = lookups.length === 2;
    await route.fulfill({
      json: envelope({receipt: showSaved ? receipt : null}),
    });
  });

  await page.goto(url);
  const form = page.getByTestId('provider-create-form');
  await expect(form).toBeVisible();
  await form.getByLabel('Nhà cung cấp').selectOption('OPENAI');
  await form.getByLabel('Tên kết nối').fill(saved.name);
  await form.getByLabel('API key').fill(secret);
  await form.getByRole('button', {name: 'Tạo kết nối', exact: true}).click();

  const unresolved = page.getByTestId('provider-create-unknown');
  await expect(unresolved).toContainText(
    'Chưa xác nhận được kết quả tạo kết nối.',
  );
  expect(submissions).toHaveLength(1);
  const [{key}] = submissions;
  expect(key).toMatch(/^[0-9a-f-]{36}$/);
  expect(submissions[0]).toEqual({
    method: 'POST',
    key,
    csrf,
    body: {
      provider: 'OPENAI',
      credentialMode: 'OPENAI_API_KEY',
      name: saved.name,
      options: {},
      apiKey: secret,
    },
  });
  expect(lookups).toHaveLength(0);
  expect(
    await page
      .locator('input[type="password"]')
      .evaluateAll(inputs => inputs.every(input => input.value === '')),
  ).toBe(true);
  async function expectNoPersistedSecrets() {
    const stored = await page.evaluate(() =>
      JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]),
    );
    expect(stored).not.toContain(secret);
    expect(stored).not.toContain(key);
  }
  await expectNoPersistedSecrets();

  const check = page.getByRole('button', {name: 'Kiểm tra biên nhận'});
  await check.click();
  await expect(unresolved).toContainText(
    'Chưa tìm thấy biên nhận. Kết quả vẫn chưa xác định.',
  );
  expect(lookups).toHaveLength(1);
  expect(submissions).toHaveLength(1);
  await check.click();

  await expect(
    page.getByText('Đã tạo kết nối provider.', {exact: true}),
  ).toBeVisible();
  await expect(
    page
      .getByRole('region', {name: 'Danh sách kết nối'})
      .getByRole('heading', {name: saved.name}),
  ).toBeVisible();
  expect(submissions).toHaveLength(1);
  expect(lookups).toHaveLength(2);
  for (const lookup of lookups) {
    expect(lookup.method).toBe('GET');
    expect(lookup.url.pathname).toBe(`${collection}/receipts/${key}`);
    expect(lookup.url.search).toBe('');
    expect(lookup.body).toBeNull();
    expect(JSON.stringify(lookup.headers)).not.toContain(secret);
  }
  await expectNoPersistedSecrets();
});

// Synthetic browser contracts; these fixtures do not exercise server auth or admission.
async function knownRejectionFixture(page, status, retryAfter) {
  await session(page);
  const collection = `/api/v1/organizations/${organization}/provider-connections`;
  await page.route(api, route =>
    route.fulfill({
      json: envelope({
        organizationId: organization,
        page: 0,
        pageSize: 20,
        total: 0,
        items: [],
      }),
    }),
  );
  await page.route(`**${collection}/options`, route =>
    route.fulfill({
      headers: {'X-Provider-Create-Enabled': 'true'},
      json: envelope({
        organizationId: organization,
        policyRevision: '1',
        providers: [
          {
            provider: 'OPENAI',
            credentialMode: 'OPENAI_API_KEY',
            enabled: true,
            optionsSchema: 'EMPTY',
          },
          {
            provider: 'ANTHROPIC',
            credentialMode: 'ANTHROPIC_API_KEY',
            enabled: false,
            optionsSchema: 'ANTHROPIC_WORKSPACE',
          },
          {
            provider: 'GOOGLE_GEMINI',
            credentialMode: 'GEMINI_API_KEY',
            enabled: false,
            optionsSchema: 'EMPTY',
          },
        ],
      }),
    }),
  );
  await page.route('**/api/v1/auth/csrf', route =>
    route.fulfill({
      json: envelope({
        headerName: 'X-CSRF-TOKEN',
        token: 'synthetic-known-rejection-csrf',
      }),
    }),
  );
  const submissions = [],
    lookups = [];
  let release;
  const firstResponse = new Promise(resolve => {
    release = resolve;
  });
  await page.route(`**${collection}`, async route => {
    submissions.push({
      method: route.request().method(),
      key: route.request().headers()['idempotency-key'],
      body: route.request().postDataJSON(),
    });
    if (submissions.length === 1) await firstResponse;
    await route.fulfill({
      status,
      headers: retryAfter === null ? {} : {'Retry-After': retryAfter},
      json: {
        error: {
          code:
            status === 429
              ? 'RATE_LIMITED'
              : status === 401
                ? 'UNAUTHORIZED'
                : 'FORBIDDEN',
          message: 'Synthetic browser contract rejection',
          details: {},
        },
        meta: {requestId: randomUUID()},
      },
    });
  });
  await page.route(`**${collection}/receipts/*`, route => {
    lookups.push(route.request().url());
    return route.fulfill({json: envelope({receipt: null})});
  });
  await page.goto(url);
  const form = page.getByTestId('provider-create-form');
  await expect(form).toBeVisible();
  await form.getByLabel('Tên kết nối').fill('Known rejection fixture');
  await form.getByLabel('API key').fill('synthetic-rejected-create-key');
  await form.getByRole('button', {name: 'Tạo kết nối', exact: true}).click();
  await expect.poll(() => submissions.length).toBe(1);
  // Observe erasure during the pending POST, before a denied response unmounts the form.
  await expect(form.getByLabel('API key')).toHaveValue('');
  expect(submissions[0].method).toBe('POST');
  expect(submissions[0].body.apiKey).toBe('synthetic-rejected-create-key');
  release();
  return {form, submissions, lookups};
}

for (const [label, retryAfter, seconds] of [
  ['minimum', '1', 1],
  ['maximum', '60', 60],
  ['missing', null, null],
  ['zero', '0', null],
  ['over maximum', '61', null],
  ['fraction', '1.5', null],
  ['noncanonical', '01', null],
  ['date', 'Wed, 21 Oct 2015 07:28:00 GMT', null],
  ['arbitrary text', 'untrusted-header-content', null],
]) {
  test(`provider Create browser contract keeps 429 ${label} known and requires a fresh explicit submission`, async ({
    page,
  }) => {
    await page.clock.install();
    const {form, submissions, lookups} = await knownRejectionFixture(
      page,
      429,
      retryAfter,
    );
    const alert = page.getByRole('alert');
    await expect(alert).toHaveText(
      seconds === null
        ? 'Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau.'
        : `Bạn đã gửi quá nhiều yêu cầu. Vui lòng thử lại sau ${seconds} giây.`,
    );
    await expect(form.getByLabel('API key')).toHaveValue('');
    await expect(page.getByTestId('provider-create-unknown')).toHaveCount(0);
    await expect(
      page.getByRole('button', {name: 'Kiểm tra biên nhận'}),
    ).toHaveCount(0);
    await page.clock.fastForward(65_000);
    expect(submissions).toHaveLength(1);
    expect(lookups).toHaveLength(0);
    // An explicit submit without a freshly entered key must remain local.
    await form.getByRole('button', {name: 'Tạo kết nối', exact: true}).click();
    await expect(alert).toHaveText(
      'Kiểm tra tên kết nối, API key và Workspace ID rồi thử lại.',
    );
    expect(submissions).toHaveLength(1);
    await form.getByLabel('API key').fill('synthetic-fresh-create-key');
    await form.getByRole('button', {name: 'Tạo kết nối', exact: true}).click();
    await expect(alert).toContainText('Bạn đã gửi quá nhiều yêu cầu.');
    expect(submissions).toHaveLength(2);
    expect(submissions[1].body.apiKey).toBe('synthetic-fresh-create-key');
    expect(submissions[1].key).toMatch(/^[0-9a-f-]{36}$/);
    expect(submissions[1].key).not.toBe(submissions[0].key);
    await expect(form.getByLabel('API key')).toHaveValue('');
    await page.clock.fastForward(65_000);
    expect(submissions).toHaveLength(2);
    expect(lookups).toHaveLength(0);
    const stored = await page.evaluate(() =>
      JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]),
    );
    expect(stored).not.toContain('synthetic-rejected-create-key');
    expect(stored).not.toContain('synthetic-fresh-create-key');
    for (const submission of submissions)
      expect(stored).not.toContain(submission.key);
  });
}

for (const status of [401, 403]) {
  test(`provider Create browser contract clears and unmounts the form after ${status}`, async ({
    page,
  }) => {
    await page.clock.install();
    const {form, submissions, lookups} = await knownRejectionFixture(
      page,
      status,
      null,
    );
    await expect(page.getByRole('alert')).toHaveText(
      status === 401
        ? 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.'
        : 'Yêu cầu bị từ chối. Vui lòng tải lại trang để kiểm tra phiên và quyền truy cập.',
    );
    await expect(form).toHaveCount(0);
    await expect(page.getByTestId('provider-create-unknown')).toHaveCount(0);
    await expect(
      page.getByRole('button', {name: 'Kiểm tra biên nhận'}),
    ).toHaveCount(0);
    await expect(page.locator('input[type="password"]')).toHaveCount(0);
    await page.clock.fastForward(65_000);
    expect(submissions).toHaveLength(1);
    expect(lookups).toHaveLength(0);
    const stored = await page.evaluate(() =>
      JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]),
    );
    expect(stored).not.toContain('synthetic-rejected-create-key');
    expect(stored).not.toContain(submissions[0].key);
  });
}
