import {test, expect} from '@playwright/test';

const enabled = process.env.PROVIDER_VAULT_LIVE === 'true';
test.skip(
  !enabled,
  'Requires the disposable PostgreSQL and TLS Vault harness.',
);

test('real provider Create recovers a dropped response through its own receipt', async ({
  page,
}) => {
  test.setTimeout(60_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  const secret = process.env.PROVIDER_VAULT_SECRET;
  if (!organization || !email || !password || !secret) {
    throw new Error('Missing disposable provider browser fixtures');
  }
  const root = `/api/v1/organizations/${organization}/provider-connections`;
  const creates = [];
  const lookups = [];
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path === root && request.method() === 'POST') {
      creates.push(request.headers()['idempotency-key']);
    }
    if (path.startsWith(root + '/receipts/')) {
      lookups.push({method: request.method(), path, body: request.postData()});
    }
  });
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(`/workspace/${organization}/provider-connections`);
  const form = page.getByTestId('provider-create-form');
  await expect(form).toBeVisible();
  await form.getByLabel('Nhà cung cấp').selectOption('OPENAI');
  await form.getByLabel('Tên kết nối').fill('Live Vault browser fixture');
  await form.getByLabel('API key').fill(secret);
  await form.getByRole('button', {name: 'Tạo kết nối', exact: true}).click();
  await expect(page.getByTestId('provider-create-unknown')).toBeVisible();
  expect(creates).toHaveLength(1);
  expect(creates[0]).toMatch(/^[0-9a-f-]{36}$/);
  expect(lookups).toHaveLength(0);
  expect(
    await page
      .locator('input[type="password"]')
      .evaluateAll(inputs => inputs.every(input => input.value === '')),
  ).toBe(true);
  await page.getByRole('button', {name: 'Kiểm tra biên nhận'}).click();
  await expect(
    page.getByText('Đã tạo kết nối provider.', {exact: true}),
  ).toBeVisible();
  await expect(
    page
      .getByRole('region', {name: 'Danh sách kết nối'})
      .getByRole('heading', {name: 'Live Vault browser fixture'}),
  ).toBeVisible();
  expect(creates).toHaveLength(1);
  expect(lookups).toEqual([
    {method: 'GET', path: `${root}/receipts/${creates[0]}`, body: null},
  ]);
  const storedSafely = await page.evaluate(
    ({secret, key}) => {
      const stored = JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]);
      return !stored.includes(secret) && !stored.includes(key);
    },
    {secret, key: creates[0]},
  );
  expect(storedSafely).toBe(true);
});
