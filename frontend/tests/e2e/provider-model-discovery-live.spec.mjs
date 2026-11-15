import {randomUUID} from 'node:crypto';
import {expect, test} from '@playwright/test';

test.skip(
  process.env.PROVIDER_MODEL_DISCOVERY_VAULT_LIVE !== 'true',
  'Requires disposable PostgreSQL, TLS Vault and the managed Model Discovery worker.',
);

test('Model Discovery supplies a model which can be bound, granted and revoked in Agent Studio', async ({
  page,
  request,
}) => {
  test.setTimeout(90_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const connection = process.env.PROVIDER_MODEL_DISCOVERY_CONNECTION;
  const agent = process.env.PROVIDER_MODEL_DISCOVERY_AGENT;
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

  if (!agent) throw new Error('Missing disposable Agent Studio fixture');
  const binding = `/api/v1/organizations/${organization}/agents/${agent}/model-binding`;
  await page.goto(`/workspace/${organization}/agents/${agent}/studio`);
  const studio = page.getByRole('region', {
    name: 'Gắn model cho Agent',
    exact: true,
  });
  await studio.getByRole('button', {name: 'Chọn model', exact: true}).click();
  await studio
    .getByRole('combobox', {name: 'Kết nối nhà cung cấp', exact: true})
    .selectOption(connection);
  await studio
    .getByRole('combobox', {name: 'Model', exact: true})
    .selectOption('fixture-model-01');
  await studio
    .getByRole('button', {name: 'Xem lại lựa chọn', exact: true})
    .click();
  const saved = page.waitForResponse(
    response =>
      response.request().method() === 'PUT' &&
      new URL(response.url()).pathname === binding,
  );
  await studio.getByRole('button', {name: 'Lưu model', exact: true}).click();
  const result = await saved;
  expect(result.status()).toBe(200);
  expect(result.request().postDataJSON()).toEqual({
    expectedRevision: 0,
    selection: {
      connectionId: connection,
      checkId: acceptedReceipt.checkId,
      modelId: 'fixture-model-01',
    },
  });
  const historical = (await result.json()).data;
  expect(historical.binding).toMatchObject({
    connectionId: connection,
    checkId: acceptedReceipt.checkId,
    modelId: 'fixture-model-01',
    status: 'CURRENT',
  });
  await expect(studio.getByTestId('agent-saved-model')).toHaveText(
    'fixture-model-01',
  );
  await page.reload();
  await expect(
    studio.getByText('fixture-model-01', {exact: true}),
  ).toBeVisible();
  const persisted = await page.request.get(binding);
  expect(persisted.status()).toBe(200);
  expect((await persisted.json()).data).toEqual(historical);

  const grantPath = binding.replace(/model-binding$/, 'model-grant');
  const activationReviewPath = binding.replace(/model-binding$/, 'activation-review');
  const expectModelAuthorization = async status => {
    const response = await page.request.get(activationReviewPath);
    expect(response.status()).toBe(200);
    const {data} = await response.json();
    expect(data.activationAllowed).toBe(false);
    expect(data.checks).toEqual(
      expect.arrayContaining([
        expect.objectContaining({code: 'MODEL_AUTHORIZATION', status}),
        expect.objectContaining({code: 'EXECUTION_RUNTIME', status: 'UNAVAILABLE'}),
      ]),
    );
  };
  await expectModelAuthorization('BLOCKED');
  const permissions = studio.getByRole('region', {
    name: 'Quyền dùng model',
    exact: true,
  });
  const current = permissions.getByTestId('agent-model-grant-state');
  const grantWrite = () =>
    page.waitForResponse(
      response =>
        response.request().method() === 'PUT' &&
        new URL(response.url()).pathname === grantPath,
    );
  await permissions
    .getByRole('button', {name: 'Cấp quyền dùng model', exact: true})
    .click();
  const granted = grantWrite();
  await permissions
    .getByRole('button', {name: 'Xác nhận cấp quyền', exact: true})
    .click();
  const grantedResponse = await granted;
  expect(grantedResponse.status()).toBe(200);
  expect(grantedResponse.request().postDataJSON()).toEqual({
    expectedRevision: 0,
    expectedBindingRevision: 1,
    enabled: true,
  });
  expect((await grantedResponse.json()).data).toMatchObject({
    revision: 1,
    bindingRevision: 1,
    state: 'GRANTED',
  });
  await expect(current).toHaveText('Đã cấp quyền');
  await expectModelAuthorization('PASS');

  await permissions
    .getByRole('button', {name: 'Thu hồi quyền dùng model', exact: true})
    .click();
  const revoked = grantWrite();
  await permissions
    .getByRole('button', {name: 'Xác nhận thu hồi', exact: true})
    .click();
  const revokedResponse = await revoked;
  expect(revokedResponse.status()).toBe(200);
  expect(revokedResponse.request().postDataJSON()).toEqual({
    expectedRevision: 1,
    expectedBindingRevision: 1,
    enabled: false,
  });
  expect((await revokedResponse.json()).data).toMatchObject({
    revision: 2,
    bindingRevision: 1,
    state: 'REVOKED',
  });
  await expect(current).toHaveText('Đã thu hồi quyền');
  await expectModelAuthorization('BLOCKED');
  await page.reload();
  await expect(current).toHaveText('Đã thu hồi quyền');
  await expect(studio.getByTestId('agent-saved-model')).toHaveText(
    'fixture-model-01',
  );
});
