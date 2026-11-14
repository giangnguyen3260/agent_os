import {expect, test} from '@playwright/test';

test.skip(
  process.env.PROVIDER_ROTATE_VAULT_LIVE !== 'true',
  'Requires disposable PostgreSQL, TLS Vault and managed HTTP.',
);

test('Rotate recovers a malformed post-commit reply through its own receipt', async ({
  page,
}) => {
  test.setTimeout(90_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const connection = process.env.PROVIDER_ROTATE_CONNECTION;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  if (!organization || !connection || !email || !password)
    throw new Error('Missing disposable Rotate browser fixtures');
  const key = 'browser-vault-rotate-recovery-synthetic';
  const base = `/api/v1/organizations/${organization}/provider-connections`;
  const receiptPrefix = base + '/receipts/';
  const posts = [],
    lookups = [];
  let upstreamStatus;
  await page.route(`**${base}/${connection}/rotate`, async route => {
    const request = route.request();
    posts.push(request.headers()['idempotency-key']);
    expect(request.method()).toBe('POST');
    const submitted = request.postDataJSON();
    expect(submitted.expectedRevision).toBe('2');
    expect(submitted.apiKey === key).toBe(true);
    const committed = await route.fetch({maxRetries: 0, maxRedirects: 0});
    upstreamStatus = committed.status();
    await route.fulfill({
      status: upstreamStatus,
      contentType: 'application/json',
      body: '{',
    });
  });
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith(receiptPrefix))
      lookups.push({
        method: request.method(),
        path,
        hasBody: request.postData() !== null,
      });
  });

  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(`/workspace/${organization}/provider-connections`);
  await page
    .getByRole('button', {name: 'Xem kết nối Rotate fixture', exact: true})
    .click();
  await page.getByRole('button', {name: 'Thay API key', exact: true}).click();
  const review = page.getByTestId('provider-rotate-review');
  await expect(review).toContainText('Phiên bản khóa: 2');
  await review.getByLabel('API key mới').fill(key);
  await review.getByRole('button', {name: 'Xác nhận thay API key'}).click();

  const unknown = page.getByTestId('provider-rotate-unknown');
  await expect(unknown).toBeVisible({timeout: 35_000});
  await expect(page.getByLabel('API key mới')).toHaveCount(0);
  expect(upstreamStatus).toBe(201);
  expect(posts).toHaveLength(1);
  expect(lookups).toHaveLength(0);
  await unknown.getByRole('button', {name: 'Kiểm tra kết quả'}).click();
  await expect(
    page.getByText('Đã thay API key cho kết nối provider.'),
  ).toBeVisible();
  await expect(unknown).toHaveCount(0);
  expect(posts).toHaveLength(1);
  expect(lookups).toEqual([
    {method: 'GET', path: receiptPrefix + posts[0], hasBody: false},
  ]);
  const persisted = await page.evaluate(
    ([credential, idempotencyKey]) => {
      const stored = JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]);
      return stored.includes(credential) || stored.includes(idempotencyKey);
    },
    [key, posts[0]],
  );
  expect(persisted).toBe(false);
});
