import {expect, test} from '@playwright/test';

test.skip(
  process.env.PROVIDER_ACCESS_CHECK_VAULT_LIVE !== 'true',
  'Requires disposable PostgreSQL, TLS Vault and the managed Access Check worker.',
);

test('Access Check submits once and displays the completed worker result', async ({
  page,
}) => {
  test.setTimeout(90_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const connection = process.env.PROVIDER_ACCESS_CHECK_CONNECTION;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  if (!organization || !connection || !email || !password)
    throw new Error('Missing disposable Access Check browser fixtures');

  const base = `/api/v1/organizations/${organization}/provider-connections/${connection}`;
  const submissions = [];
  page.on('request', request => {
    if (
      request.method() === 'POST' &&
      new URL(request.url()).pathname === base + '/access-checks'
    )
      submissions.push(request.postDataJSON());
  });
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(`/workspace/${organization}/provider-connections`);
  await page
    .getByRole('button', {
      name: 'Xem kết nối Access Check fixture',
      exact: true,
    })
    .click();
  await page
    .getByRole('button', {name: 'Kiểm tra kết nối', exact: true})
    .click();
  const accepted = page.waitForResponse(
    response =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === base + '/access-checks',
  );
  await page
    .getByRole('button', {name: 'Xác nhận kiểm tra kết nối', exact: true})
    .click();
  expect((await accepted).status()).toBe(201);
  const completed = page.getByText('Kết nối có quyền đọc danh sách model.', {
    exact: true,
  });
  const refresh = page.getByRole('button', {
    name: 'Cập nhật trạng thái',
    exact: true,
  });
  await expect
    .poll(
      async () => {
        if (await completed.isVisible()) return true;
        if (
          (await refresh.isVisible()) &&
          (await refresh.isEnabled({timeout: 250}).catch(() => false))
        )
          await refresh.click({timeout: 1000}).catch(() => undefined);
        return completed.isVisible();
      },
      {timeout: 25_000},
    )
    .toBe(true);
  expect(submissions).toEqual([
    {expectedRevision: '1', expectedCredentialRevision: '1'},
  ]);
});
