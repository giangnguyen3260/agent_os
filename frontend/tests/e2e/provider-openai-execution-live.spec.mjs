import {expect, test} from '@playwright/test';

test.skip(
  process.env.PROVIDER_OPENAI_EXECUTION_VAULT_LIVE !== 'true',
  'Requires disposable PostgreSQL, TLS Vault and the controlled OpenAI worker.',
);

test('Task Start, Vault-backed worker, rework and acceptance use real backend APIs', async ({
  page,
}) => {
  test.setTimeout(110_000);
  const org = process.env.PROVIDER_VAULT_ORG;
  const team = process.env.PROVIDER_OPENAI_EXECUTION_TEAM;
  const mission = process.env.PROVIDER_OPENAI_EXECUTION_MISSION;
  const task = process.env.PROVIDER_OPENAI_EXECUTION_TASK;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  if (!org || !team || !mission || !task || !email || !password)
    throw new Error('Missing disposable OpenAI execution browser fixtures');
  const taskPath = `/workspace/${org}/teams/${team}/missions/${mission}/tasks/${task}`;
  const realProvider =
    process.env.PROVIDER_OPENAI_EXECUTION_REAL_PROVIDER === 'true';
  const model =
    process.env.PROVIDER_OPENAI_EXECUTION_MODEL || 'fixture-model-01';
  const starts = [];
  page.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname.endsWith('/start'))
      starts.push(r.postDataJSON());
  });
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(taskPath + '/start');
  for (const decision of ['REWORK', 'ACCEPT']) {
    await expect(page.getByLabel('Request đề xuất')).toContainText(
      'Vault execution Task',
    );
    await expect(page.getByRole('main')).toContainText(model);
    await expect(page.getByRole('main')).not.toContainText(
      'browser-vault-disposable-synthetic-key',
    );
    await page
      .getByRole('button', {name: 'Xác nhận Start Task', exact: true})
      .click();
    await expect(page.getByRole('status')).toContainText('Đã ghi nhận Start.');
    await page
      .getByRole('link', {
        name: 'Về lần chạy / kiểm tra trạng thái',
        exact: true,
      })
      .click();
    const review = page.getByRole('link', {
      name: 'Duyệt kết quả thực thi',
      exact: true,
    });
    await expect
      .poll(
        async () => {
          if (await review.isVisible()) return true;
          const refresh = page.getByRole('button', {
            name: 'Tải lại',
            exact: true,
          });
          if (await refresh.isEnabled().catch(() => false))
            await refresh.click();
          return review.isVisible();
        },
        {timeout: 40_000},
      )
      .toBe(true);
    await review.click();
    const output = page.getByLabel('Nội dung kết quả');
    if (realProvider) await expect(output).toContainText(/\S/);
    else await expect(output).toHaveText('Vault verified task output');
    if (decision === 'REWORK')
      await page
        .getByLabel('Lý do yêu cầu làm lại')
        .fill('Add the missing evidence');
    await page
      .getByRole('button', {
        name: decision === 'REWORK' ? 'Yêu cầu làm lại' : 'Duyệt kết quả',
        exact: true,
      })
      .click();
    await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
    await expect(page.getByRole('status')).toContainText(
      decision === 'REWORK' ? 'Đã yêu cầu' : 'Đã duyệt',
    );
    await page
      .getByRole('link', {
        name: 'Về lần chạy / kiểm tra trạng thái',
        exact: true,
      })
      .click();
    if (decision === 'REWORK')
      await page
        .getByRole('link', {name: 'Khởi chạy Task', exact: true})
        .click();
  }
  await expect(page.getByRole('main')).toContainText('COMPLETED');
  expect(starts).toHaveLength(2);
  expect(starts[0].idempotencyKey).not.toBe(starts[1].idempotencyKey);
});
