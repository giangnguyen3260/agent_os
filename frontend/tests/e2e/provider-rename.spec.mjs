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

async function fixture(
  page,
  enabled = 'true',
  {
    disable = 'false',
    enable = 'false',
    adapterEnabled = false,
    initial = row(),
  } = {},
) {
  let current = initial;
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
        'X-Provider-Disable-Enabled': disable,
        'X-Provider-Enable-Enabled': enable,
        'X-Provider-Create-Enabled': 'false',
      },
      json: envelope({
        organizationId: org,
        policyRevision: '1',
        providers: [
          {
            provider: 'OPENAI',
            credentialMode: 'OPENAI_API_KEY',
            enabled: adapterEnabled,
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

const enabledRow = () => ({...row(), state: 'ENABLED'});
const disableReceipt = before => {
  const result = receipt(before, before.name);
  return {
    ...result,
    operation: 'DISABLE',
    after: {...result.after, state: 'DISABLED'},
  };
};
const disablePath = `**${base}/${target}/disable`;
const disableAction = page =>
  page.getByRole('button', {name: 'Tắt kết nối', exact: true});
const confirmDisable = page =>
  page.getByRole('button', {name: 'Xác nhận tắt kết nối', exact: true}).click();

for (const [label, flag, initial] of [
  ['flag off', 'false', enabledRow()],
  ['already disabled', 'true', row()],
]) {
  test(`Disable action is hidden with ${label}`, async ({page}) => {
    await fixture(page, 'true', {disable: flag, initial});
    await expect(
      page.getByRole('button', {name: 'Đổi tên', exact: true}),
    ).toBeVisible();
    await expect(disableAction(page)).toHaveCount(0);
  });
}

test('Disable can run independently of Rename and requires explicit confirmation', async ({
  page,
}) => {
  const before = enabledRow();
  const state = await fixture(page, 'false', {
    disable: 'true',
    initial: before,
  });
  const posts = [];
  await page.route(disablePath, async route => {
    posts.push(route.request());
    const result = disableReceipt(before);
    state.update({...before, ...result.after});
    await route.fulfill({status: 201, json: envelope(result)});
  });
  await disableAction(page).click();
  const review = page.getByTestId('provider-disable-review');
  await expect(review).toContainText(before.name);
  expect(posts).toHaveLength(0);
  await review.getByRole('button', {name: 'Hủy', exact: true}).click();
  expect(posts).toHaveLength(0);
  await disableAction(page).click();
  await confirmDisable(page);
  await expect(page.getByText('Đã tắt kết nối provider.')).toBeVisible();
  await expect(page.getByText('Đã vô hiệu hóa', {exact: true})).toBeVisible();
  expect(posts).toHaveLength(1);
  expect(posts[0].postDataJSON()).toEqual({expectedRevision: before.revision});
  expect(posts[0].headers()['x-csrf-token']).toBe('fixture-csrf-token');
  expect(posts[0].headers()['idempotency-key']).toMatch(/^[0-9a-f-]{36}$/);
});

test('Disable holds other actions until the matching historical receipt confirms recovery', async ({
  page,
}) => {
  const before = enabledRow();
  const state = await fixture(page, 'true', {disable: 'true', initial: before});
  const result = disableReceipt(before);
  const requests = [];
  let lookups = 0;
  let key;
  await page.route(disablePath, async route => {
    requests.push(route.request());
    key = route.request().headers()['idempotency-key'];
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: '{',
    });
  });
  await page.route(`**${base}/receipts/*`, async route => {
    lookups++;
    expect(route.request().method()).toBe('GET');
    expect(new URL(route.request().url()).pathname).toBe(
      `${base}/receipts/${key}`,
    );
    const found =
      lookups === 1
        ? null
        : lookups === 2
          ? {...result, actorId: randomUUID()}
          : result;
    if (lookups === 3) state.update({...before, ...result.after});
    await route.fulfill({json: envelope({receipt: found})});
  });
  await disableAction(page).click();
  await confirmDisable(page);
  const unknown = page.getByTestId('provider-disable-unknown');
  await expect(unknown).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Đổi tên', exact: true}),
  ).toHaveCount(0);
  expect(lookups).toBe(0);
  const check = unknown.getByRole('button', {name: 'Kiểm tra kết quả'});
  await check.click();
  await expect(unknown).toContainText('Chưa tìm thấy biên nhận');
  await check.click();
  await expect(unknown).toContainText('Không thể kiểm tra biên nhận');
  await check.click();
  await expect(page.getByText('Đã tắt kết nối provider.')).toBeVisible();
  expect(requests).toHaveLength(1);
  expect(lookups).toBe(3);
});

test('Disable requires a fresh list revision after a known conflict', async ({
  page,
}) => {
  const before = enabledRow();
  const state = await fixture(page, 'false', {
    disable: 'true',
    initial: before,
  });
  const posts = [];
  await page.route(disablePath, async route => {
    posts.push(route.request().postDataJSON());
    if (posts.length === 1) {
      state.update({...before, revision: '9007199254740994'});
      await route.fulfill({status: 409, json: failure('REVISION_CONFLICT')});
      return;
    }
    const result = disableReceipt({...before, revision: '9007199254740994'});
    state.update({...before, ...result.after});
    await route.fulfill({status: 201, json: envelope(result)});
  });
  await disableAction(page).click();
  await confirmDisable(page);
  await expect(page.getByRole('alert')).toContainText('Kết nối đã thay đổi');
  await expect(page.getByTestId('provider-disable-unknown')).toHaveCount(0);
  await expect(disableAction(page)).toHaveCount(0);
  await page
    .getByRole('button', {name: 'Tải lại kết nối', exact: true})
    .click();
  await page.getByRole('button', {name: 'Xem kết nối Tên hiện tại'}).click();
  await disableAction(page).click();
  await expect(page.getByTestId('provider-disable-review')).toContainText(
    '9007199254740994',
  );
  await confirmDisable(page);
  await expect(page.getByText('Đã tắt kết nối provider.')).toBeVisible();
  expect(posts).toEqual([
    {expectedRevision: '9007199254740993'},
    {expectedRevision: '9007199254740994'},
  ]);
});

const availableDisabledRow = () => ({
  ...row(),
  credentialState: 'PRESENT',
  credentialMask: '••••••••',
});
const enableReceipt = before => {
  const result = receipt(before, before.name);
  return {
    ...result,
    operation: 'ENABLE',
    after: {...result.after, state: 'ENABLED'},
  };
};
const enablePath = `**${base}/${target}/enable`;
const enableAction = page =>
  page.getByRole('button', {name: 'Bật lại kết nối', exact: true});
const confirmEnable = page =>
  page
    .getByRole('button', {name: 'Xác nhận bật lại kết nối', exact: true})
    .click();

test('Enable requires a separate capability and confirmation', async ({
  page,
}) => {
  const before = availableDisabledRow();
  const state = await fixture(page, 'false', {
    enable: 'true',
    adapterEnabled: true,
    initial: before,
  });
  const posts = [];
  await page.route(enablePath, async route => {
    posts.push(route.request());
    const result = enableReceipt(before);
    state.update({...before, ...result.after});
    await route.fulfill({status: 201, json: envelope(result)});
  });
  await enableAction(page).click();
  await expect(page.getByTestId('provider-enable-review')).toContainText(
    before.name,
  );
  expect(posts).toHaveLength(0);
  await confirmEnable(page);
  await expect(page.getByText('Đã bật lại kết nối provider.')).toBeVisible();
  await expect(page.getByText('Đang bật', {exact: true})).toBeVisible();
  expect(posts).toHaveLength(1);
  expect(posts[0].postDataJSON()).toEqual({expectedRevision: before.revision});
  expect(posts[0].headers()['x-csrf-token']).toBe('fixture-csrf-token');
});

for (const [label, adapterEnabled, initial] of [
  ['adapter disabled', false, availableDisabledRow()],
  ['credential evidence unavailable', true, row()],
]) {
  test(`Enable action is blocked with ${label}`, async ({page}) => {
    await fixture(page, 'false', {enable: 'true', adapterEnabled, initial});
    await expect(enableAction(page)).toBeDisabled();
    await expect(page.getByTestId('provider-enable-review')).toHaveCount(0);
  });
}

for (const code of ['CREDENTIAL_UNAVAILABLE', 'PROVIDER_DISABLED']) {
  test(`Enable requires refresh after ${code}`, async ({page}) => {
    const before = availableDisabledRow();
    await fixture(page, 'false', {
      enable: 'true',
      adapterEnabled: true,
      initial: before,
    });
    let posts = 0;
    await page.route(enablePath, async route => {
      posts++;
      await route.fulfill({status: 409, json: failure(code)});
    });
    await enableAction(page).click();
    await confirmEnable(page);
    await expect(
      page.getByRole('button', {name: 'Tải lại kết nối', exact: true}),
    ).toBeVisible();
    await expect(page.getByTestId('provider-enable-unknown')).toHaveCount(0);
    await expect(enableAction(page)).toHaveCount(0);
    expect(posts).toBe(1);
  });
}

test('Enable recovers an uncertain result from its own receipt without repeating the command', async ({
  page,
}) => {
  const before = availableDisabledRow();
  const state = await fixture(page, 'true', {
    enable: 'true',
    adapterEnabled: true,
    initial: before,
  });
  const result = enableReceipt(before);
  let posts = 0;
  let lookups = 0;
  let key;
  await page.route(enablePath, async route => {
    posts++;
    key = route.request().headers()['idempotency-key'];
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: '{',
    });
  });
  await page.route(`**${base}/receipts/*`, async route => {
    lookups++;
    expect(route.request().method()).toBe('GET');
    expect(new URL(route.request().url()).pathname).toBe(
      `${base}/receipts/${key}`,
    );
    state.update({...before, ...result.after});
    await route.fulfill({json: envelope({receipt: result})});
  });
  await enableAction(page).click();
  await confirmEnable(page);
  const unknown = page.getByTestId('provider-enable-unknown');
  await expect(unknown).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Đổi tên', exact: true}),
  ).toHaveCount(0);
  expect(lookups).toBe(0);
  await unknown
    .getByRole('button', {name: 'Kiểm tra kết quả', exact: true})
    .click();
  await expect(page.getByText('Đã bật lại kết nối provider.')).toBeVisible();
  expect(posts).toBe(1);
  expect(lookups).toBe(1);
});
