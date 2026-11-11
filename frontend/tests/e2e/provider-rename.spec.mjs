import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const org = '71a00000-0000-4000-8000-000000000001';
const actor = '71a00000-0000-4000-8000-000000000002';
const target = '71a00000-0000-4000-8000-000000000003';
const path = `/workspace/${org}/provider-connections`;
const base = `/api/v1/organizations/${org}/provider-connections`;
const envelope = data => ({data, meta: {requestId: randomUUID()}});
const row = () => ({
  organizationId: org,
  id: target,
  provider: 'OPENAI',
  credentialMode: 'OPENAI_API_KEY',
  name: 'Tên hiện tại',
  options: {},
  state: 'DISABLED',
  revision: '9007199254740993',
  credentialRevision: '1',
  credentialState: 'UNAVAILABLE',
  credentialMask: null,
  createdAt: '2026-11-04T00:00:00.000000Z',
  updatedAt: '2026-11-04T00:00:01.000000Z',
});
const snapshot = value => {
  const {credentialState, credentialMask, ...result} = value;
  return result;
};
const receipt = (before, name) => ({
  organizationId: org,
  commandId: randomUUID(),
  connectionId: target,
  actorId: actor,
  operation: 'RENAME',
  committedAt: '2026-11-04T00:00:03.000000Z',
  before: snapshot(before),
  after: {
    ...snapshot(before),
    name,
    revision: (BigInt(before.revision) + 1n).toString(),
    updatedAt: '2026-11-04T00:00:02.000000Z',
  },
  checkId: null,
});
const failure = code => ({
  error: {code, message: 'Yêu cầu không thể hoàn thành.', details: {}},
  meta: {requestId: randomUUID()},
});

async function fixture(page, enabled = 'true') {
  let current = row();
  const requests = [];
  await page.route('**/api/v1/auth/session', route =>
    route.fulfill({
      json: envelope({
        id: actor,
        email: 'rename@example.test',
        displayName: 'Rename fixture',
      }),
    }),
  );
  await page.route('**/api/v1/auth/csrf', route =>
    route.fulfill({
      json: envelope({headerName: 'X-CSRF-TOKEN', token: 'fixture-csrf-token'}),
    }),
  );
  await page.route(`**${base}/options`, route =>
    route.fulfill({
      headers: {
        'X-Provider-Rename-Enabled': enabled,
        'X-Provider-Create-Enabled': 'false',
      },
      json: envelope({
        organizationId: org,
        policyRevision: '1',
        providers: [
          {
            provider: 'OPENAI',
            credentialMode: 'OPENAI_API_KEY',
            enabled: false,
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
  await page.route(`**${base}?*`, route =>
    route.fulfill({
      json: envelope({
        organizationId: org,
        page: 0,
        pageSize: 20,
        total: 1,
        items: [current],
      }),
    }),
  );
  await page.route(`**${base}/${target}/rename`, async route => {
    requests.push(route.request());
    await route.fulfill({
      status: 503,
      json: failure('COMMAND_OUTCOME_UNKNOWN'),
    });
  });
  await page.goto(path);
  await expect(
    page.getByRole('heading', {name: 'Tên hiện tại', exact: true}),
  ).toBeVisible();
  await page.getByRole('button', {name: 'Xem kết nối Tên hiện tại'}).click();
  return {
    requests,
    update: next => {
      current = next;
    },
  };
}

async function review(page, name = 'Tên sau đổi') {
  await page.getByRole('button', {name: 'Đổi tên', exact: true}).click();
  await page.getByRole('textbox', {name: 'Tên mới', exact: true}).fill(name);
  await page
    .getByRole('button', {name: 'Xem lại thay đổi', exact: true})
    .click();
  await expect(page.getByTestId('provider-rename-review')).toBeVisible();
}

async function confirm(page) {
  await page
    .getByRole('button', {name: 'Xác nhận đổi tên', exact: true})
    .click();
}

test('Rename is explicitly gated independently of disabled provider and preserves int64 revision', async ({
  page,
}) => {
  const state = await fixture(page);
  const original = row();
  let sent;
  await page.route(`**${base}/${target}/rename`, async route => {
    sent = route.request();
    const result = receipt(original, 'Tên sau đổi');
    state.update({...original, ...result.after});
    await route.fulfill({status: 201, json: envelope(result)});
  });
  await review(page, '  Tên sau đổi  ');
  expect(sent).toBeUndefined();
  await expect(page.getByTestId('provider-rename-review')).toContainText(
    'Tên hiện tại',
  );
  await expect(page.getByTestId('provider-rename-review')).toContainText(
    'Tên sau đổi',
  );
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await confirm(page);
  await expect(
    page.getByRole('heading', {name: 'Tên sau đổi', exact: true}),
  ).toBeVisible();
  expect(sent.postDataJSON()).toEqual({
    expectedRevision: '9007199254740993',
    name: 'Tên sau đổi',
  });
  expect(sent.headers()['x-csrf-token']).toBe('fixture-csrf-token');
  expect(sent.headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
});

for (const capability of ['false', 'TRUE', ''])
  test(`Rename stays hidden with capability ${JSON.stringify(capability)}`, async ({
    page,
  }) => {
    const state = await fixture(page, capability);
    await expect(
      page.getByRole('button', {name: 'Đổi tên', exact: true}),
    ).toHaveCount(0);
    expect(state.requests).toHaveLength(0);
  });

test('UNKNOWN never resubmits; explicit lookup requires matching historical Rename receipt', async ({
  page,
}) => {
  const state = await fixture(page);
  let lookups = 0;
  const result = receipt(row(), 'Tên sau đổi');
  await page.route(`**${base}/receipts/*`, async route => {
    lookups++;
    if (lookups === 1) await route.fulfill({json: envelope({receipt: null})});
    else {
      state.update({...row(), ...result.after});
      await route.fulfill({json: envelope({receipt: result})});
    }
  });
  await review(page);
  await confirm(page);
  await expect(page.getByTestId('provider-rename-unknown')).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(lookups).toBe(0);
  await page
    .getByRole('button', {name: 'Kiểm tra kết quả', exact: true})
    .click();
  await expect(page.getByTestId('provider-rename-unknown')).toContainText(
    'Chưa tìm thấy',
  );
  expect(state.requests).toHaveLength(1);
  await page
    .getByRole('button', {name: 'Kiểm tra kết quả', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Tên sau đổi', exact: true}),
  ).toBeVisible();
  expect(state.requests).toHaveLength(1);
  expect(lookups).toBe(2);
});

for (const code of ['REVISION_CONFLICT', 'NO_CHANGE'])
  test(`${code} requires refreshing the connection before another review`, async ({
    page,
  }) => {
    const state = await fixture(page);
    await page.route(`**${base}/${target}/rename`, route =>
      route.fulfill({status: 409, json: failure(code)}),
    );
    await review(page);
    await confirm(page);
    await expect(page.getByTestId('provider-rename-unknown')).toHaveCount(0);
    await expect(
      page.getByRole('button', {name: 'Xác nhận đổi tên', exact: true}),
    ).toHaveCount(0);
    await expect(
      page.getByRole('button', {name: 'Tải lại kết nối', exact: true}),
    ).toBeVisible();
    state.update({
      ...row(),
      name: 'Tên mới trên máy chủ',
      revision: '9007199254740994',
    });
    await page
      .getByRole('button', {name: 'Tải lại kết nối', exact: true})
      .click();
    await expect(
      page.getByRole('heading', {name: 'Tên mới trên máy chủ', exact: true}),
    ).toBeVisible();
  });

test('malformed success remains unknown and never displays untrusted returned names', async ({
  page,
}) => {
  await fixture(page);
  await page.route(`**${base}/${target}/rename`, route =>
    route.fulfill({
      status: 201,
      json: envelope({
        ...receipt(row(), 'untrusted-name-marker'),
        actorId: randomUUID(),
      }),
    }),
  );
  await review(page);
  await confirm(page);
  await expect(page.getByTestId('provider-rename-unknown')).toBeVisible();
  await expect(page.getByText('untrusted-name-marker')).toHaveCount(0);
});

for (const status of [401, 403])
  test(`authority loss ${status} clears review and recovery`, async ({
    page,
  }) => {
    await fixture(page);
    await page.route(`**${base}/${target}/rename`, route =>
      route.fulfill({
        status,
        json: failure(status === 401 ? 'UNAUTHORIZED' : 'FORBIDDEN'),
      }),
    );
    await review(page);
    await confirm(page);
    await expect(page.getByTestId('provider-rename-review')).toHaveCount(0);
    await expect(page.getByTestId('provider-rename-unknown')).toHaveCount(0);
    await expect(page.getByRole('alert')).toContainText(
      status === 401 ? 'đăng nhập' : 'quyền',
    );
  });

test('known rate rejection renders only canonical bounded delay without automatic submission', async ({
  page,
}) => {
  const state = await fixture(page);
  let posts = 0;
  await page.route(`**${base}/${target}/rename`, route => {
    posts++;
    return route.fulfill({
      status: 429,
      headers: {'Retry-After': '7'},
      json: failure('RATE_LIMITED'),
    });
  });
  await review(page);
  await confirm(page);
  await expect(page.getByRole('alert')).toContainText('7 giây');
  await expect(page.getByTestId('provider-rename-unknown')).toHaveCount(0);
  expect(posts).toBe(1);
  expect(state.requests).toHaveLength(0);
});

test('unchanged or invalid names cannot reach the review or write endpoint', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.getByRole('button', {name: 'Đổi tên', exact: true}).click();
  for (const name of ['  Tên hiện tại  ', '', 'bad\u2028name']) {
    await page.getByRole('textbox', {name: 'Tên mới', exact: true}).fill(name);
    await page
      .getByRole('button', {name: 'Xem lại thay đổi', exact: true})
      .click();
    await expect(page.getByTestId('provider-rename-review')).toHaveCount(0);
  }
  expect(state.requests).toHaveLength(0);
});

test('closing selected connection cancels its response and discards reviewed command state', async ({
  page,
}) => {
  await fixture(page);
  let release;
  let began;
  const started = new Promise(resolve => {
    began = resolve;
  });
  const responseGate = new Promise(resolve => {
    release = resolve;
  });
  await page.route(`**${base}/${target}/rename`, async route => {
    began();
    await responseGate;
    await route
      .fulfill({status: 201, json: envelope(receipt(row(), 'Tên sau đổi'))})
      .catch(() => {});
  });
  await review(page);
  await confirm(page);
  await started;
  await page.getByRole('button', {name: 'Ẩn chi tiết', exact: true}).click();
  release();
  await expect(
    page.getByText('Đã đổi tên kết nối provider.', {exact: true}),
  ).toHaveCount(0);
  await expect(page.getByTestId('provider-rename-review')).toHaveCount(0);
  await expect(page.getByTestId('provider-rename-unknown')).toHaveCount(0);
  await page.getByRole('button', {name: 'Xem kết nối Tên hiện tại'}).click();
  await expect(
    page.getByRole('button', {name: 'Đổi tên', exact: true}),
  ).toBeVisible();
  await expect(page.getByTestId('provider-rename-review')).toHaveCount(0);
});

test('unsupported conflict code remains UNKNOWN instead of permitting a new write', async ({
  page,
}) => {
  await fixture(page);
  await page.route(`**${base}/${target}/rename`, route =>
    route.fulfill({status: 409, json: failure('CONNECTION_CONFLICT')}),
  );
  await review(page);
  await confirm(page);
  await expect(page.getByTestId('provider-rename-unknown')).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Xem lại thay đổi', exact: true}),
  ).toHaveCount(0);
});
