import {expect, test} from '@playwright/test';
test.skip(
  process.env.PROVIDER_GRAPH_ADMISSION_VAULT_LIVE !== 'true',
  'Requires isolated native PostgreSQL, TLS Vault and controlled worker.',
);
test('accepted diamond graph admits all Runs and releases dependencies after exact ACCEPT using real backend', async ({
  page,
}) => {
  test.setTimeout(110000);
  const org = process.env.PROVIDER_VAULT_ORG,
    team = process.env.PROVIDER_OPENAI_EXECUTION_TEAM,
    mission = process.env.PROVIDER_OPENAI_EXECUTION_MISSION;
  if (!org || !team || !mission) throw new Error('Missing graph fixture');
  const path = `/workspace/${org}/teams/${team}/missions/${mission}/plan-review`;
  await page.goto('/login');
  await page
    .getByLabel('Email', {exact: true})
    .fill(process.env.PROVIDER_VAULT_EMAIL);
  await page
    .getByLabel('Mật khẩu', {exact: true})
    .fill(process.env.PROVIDER_VAULT_PASSWORD);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
  await page.goto(path);
  await page
    .getByLabel(
      'Tôi xác nhận toàn bộ Task, nội dung và quan hệ phụ thuộc hiển thị hiện tại.',
    )
    .check();
  await page
    .getByRole('button', {name: 'Chấp nhận kế hoạch', exact: true})
    .click();
  await expect(
    page.getByText('Khớp cấu hình hiện tại.', {exact: true}),
  ).toBeVisible();
  await page
    .getByLabel('Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.')
    .check();
  await page
    .getByRole('button', {name: 'Tiếp nhận toàn bộ kế hoạch', exact: true})
    .click();
  const panel = () =>
    page
      .locator('section')
      .filter({
        has: page.getByRole('heading', {
          name: 'Thực thi theo kế hoạch',
          exact: true,
        }),
      });
  const task = name =>
    panel()
      .locator('article')
      .filter({has: page.getByRole('heading', {name, exact: true})});
  await expect(panel().locator('article')).toHaveCount(4);
  await expect(task('Graph B')).toContainText('BLOCKED');
  await expect(task('Graph C')).toContainText('BLOCKED');
  await expect(task('Graph D')).toContainText('BLOCKED');
  for (const name of ['Graph A', 'Graph B', 'Graph C', 'Graph D']) {
    await expect(
      task(name).getByRole('link', {
        name: 'Xem trước và Start Run',
        exact: true,
      }),
    ).toBeVisible();
    await task(name)
      .getByRole('link', {name: 'Xem trước và Start Run', exact: true})
      .click();
    await expect(page.getByLabel('Request đề xuất')).toContainText(name);
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
        {timeout: 40000},
      )
      .toBe(true);
    await review.click();
    await expect(page.getByLabel('Nội dung kết quả')).toHaveText(
      'Vault verified task output',
    );
    await page
      .getByRole('button', {name: 'Duyệt kết quả', exact: true})
      .click();
    await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
    await expect(page.getByRole('status')).toContainText('Đã duyệt');
    await page.goto(path);
    await expect(task(name)).toContainText('COMPLETED');
    if (name === 'Graph B')
      await expect(task('Graph D')).toContainText('BLOCKED');
  }
  await expect(panel()).toContainText('RUNNING');
});
