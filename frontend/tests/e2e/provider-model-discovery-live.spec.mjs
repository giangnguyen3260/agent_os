import {randomUUID} from 'node:crypto';
import {expect, test} from '@playwright/test';

test.skip(
  process.env.PROVIDER_MODEL_DISCOVERY_VAULT_LIVE !== 'true',
  'Requires disposable PostgreSQL, TLS Vault and the managed Model Discovery worker.',
);

test('Model Discovery recovers a committed request and pages through the stored catalogue', async ({
  page,
  request,
}) => {
  test.setTimeout(90_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const connection = process.env.PROVIDER_MODEL_DISCOVERY_CONNECTION;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  if (!organization || !connection || !email || !password)
    throw new Error('Missing disposable Model Discovery browser fixtures');

  const root = `/api/v1/organizations/${organization}/provider-connections`;
  const base = `${root}/${connection}`;
  const submissions = [];
  let acceptedReceipt;
  page.on('request', outgoing => {
    if (
      outgoing.method() === 'POST' &&
      new URL(outgoing.url()).pathname === base + '/model-discoveries'
    )
      submissions.push({
        key: outgoing.headers()['idempotency-key'],
        body: outgoing.postDataJSON(),
      });
  });
  await page.route(
    `**${base}/model-discoveries`,
    async route => {
      const upstream = await route.fetch({maxRetries: 0});
      expect(upstream.status()).toBe(201);
      acceptedReceipt = (await upstream.json()).data;
      // Commit through real HTTP and return an unreadable response to force receipt recovery.
      await route.fulfill({
        status: upstream.status(),
        contentType: 'application/json',
        headers: {'Cache-Control': 'no-store'},
        body: '{"data":',
      });
    },
    {times: 1},
  );

  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(`/workspace/${organization}/provider-connections`);
  const withoutCsrf = await page.request.post(base + '/model-discoveries', {
    headers: {'Idempotency-Key': randomUUID()},
    data: {expectedRevision: '1', expectedCredentialRevision: '1'},
    maxRetries: 0,
  });
  expect(withoutCsrf.status()).toBe(403);
  await page
    .getByRole('button', {
      name: 'Xem kết nối Model Discovery fixture',
      exact: true,
    })
    .click();
  await page.getByRole('button', {name: 'Khám phá model', exact: true}).click();
  await page
    .getByRole('button', {name: 'Xác nhận khám phá model', exact: true})
    .click();

  const unresolved = page.getByTestId('provider-model-discoveries-unknown');
  await expect(unresolved).toBeVisible();
  expect(submissions).toHaveLength(1);
  const [{key, body}] = submissions;
  expect(key).toMatch(/^[a-f0-9-]{36}$/);
  expect(body).toEqual({
    expectedRevision: '1',
    expectedCredentialRevision: '1',
  });
  expect(acceptedReceipt).toMatchObject({
    organizationId: organization,
    connectionId: connection,
    operation: 'MODEL_DISCOVERY',
    checkId: expect.stringMatching(/^[a-f0-9-]{36}$/),
  });
  expect(acceptedReceipt.before).toEqual(acceptedReceipt.after);

  const recovered = page.waitForResponse(
    response =>
      response.request().method() === 'GET' &&
      new URL(response.url()).pathname === `${root}/receipts/${key}`,
  );
  await unresolved
    .getByRole('button', {name: 'Kiểm tra kết quả', exact: true})
    .click();
  const ownReceipt = await recovered;
  expect(ownReceipt.status()).toBe(200);
  expect((await ownReceipt.json()).data.receipt).toEqual(acceptedReceipt);

  const models = page.getByRole('region', {
    name: 'Danh sách model',
    exact: true,
  });
  const refresh = page.getByRole('button', {
    name: 'Cập nhật trạng thái',
    exact: true,
  });
  const firstModel = models.getByText('fixture-model-01', {exact: true});
  await expect
    .poll(
      async () => {
        if (await firstModel.isVisible()) return true;
        if (
          (await refresh.isVisible()) &&
          (await refresh.isEnabled({timeout: 250}).catch(() => false))
        )
          await refresh.click({timeout: 1000}).catch(() => undefined);
        return firstModel.isVisible();
      },
      {timeout: 25_000},
    )
    .toBe(true);
  await expect(
    models.getByText('fixture-model-20', {exact: true}),
  ).toBeVisible();
  await expect(models.getByText('fixture-model-21', {exact: true})).toHaveCount(
    0,
  );
  await models.getByRole('button', {name: 'Trang tiếp', exact: true}).click();
  await expect(
    models.getByText('fixture-model-21', {exact: true}),
  ).toBeVisible();
  await expect(
    models.getByText('Fixture model 21', {exact: true}),
  ).toBeVisible();
  await expect(firstModel).toHaveCount(0);
  await models.getByRole('button', {name: 'Trang trước', exact: true}).click();
  await expect(firstModel).toBeVisible();

  const catalogue = `/checks/${acceptedReceipt.checkId}/models?page=0`;
  const anonymous = await request.get(base + catalogue);
  expect(anonymous.status()).toBe(401);
  const wrongConnection = await page.request.get(
    `${root}/${randomUUID()}${catalogue}`,
  );
  expect(wrongConnection.status()).toBe(404);
  expect(submissions).toHaveLength(1);
});
