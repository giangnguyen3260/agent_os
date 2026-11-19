import {test, expect} from '@playwright/test';
const org = '95170000-0000-4000-8000-000000000001';
const team = '91170000-0000-4000-8000-000000000001';
const mission = '91170000-0000-4000-8000-000000000003';
const id = (prefix, n) =>
  `${prefix}170000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const content =
  '<script>window.privateOutputExecuted=true</script>\nRecorded review evidence';
for (const decision of ['ACCEPT', 'REWORK']) {
  test(`live recorded run full review ${decision}`, async ({page}, info) => {
    const surface = ['desktop', 'tablet', 'mobile'].indexOf(info.project.name);
    expect(surface).toBeGreaterThanOrEqual(0);
    const n = surface * 2 + (decision === 'ACCEPT' ? 1 : 2);
    const task = id('92', n),
      run = id('93', n);
    const taskPath = `/workspace/${org}/teams/${team}/missions/${mission}/tasks/${task}`;
    const api = `/api/v1/organizations/${org}/execution-runs/${run}`;
    expect(process.env.E2E_PASSWORD).toBeTruthy();
    await page.goto('/login');
    await page
      .getByLabel('Email', {exact: true})
      .fill('run-review-admin@example.test');
    await page
      .getByLabel('Mật khẩu', {exact: true})
      .fill(process.env.E2E_PASSWORD);
    await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await page.goto(taskPath);
    await page.getByRole('link', {name: 'View run', exact: true}).click();
    await expect(page.getByRole('main')).toContainText('IN_REVIEW');
    await page
      .getByRole('link', {name: 'Duyệt kết quả thực thi', exact: true})
      .click();
    await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
    expect(
      await page.evaluate(() => window.privateOutputExecuted),
    ).toBeUndefined();
    if (decision === 'REWORK')
      await page
        .getByLabel('Lý do yêu cầu làm lại')
        .fill('More evidence required');
    await page
      .getByRole('button', {
        name: decision === 'ACCEPT' ? 'Duyệt kết quả' : 'Yêu cầu làm lại',
        exact: true,
      })
      .click();
    const [response] = await Promise.all([
      page.waitForResponse(
        r =>
          new URL(r.url()).pathname === api + '/review/decision' &&
          r.request().method() === 'POST',
      ),
      page.getByRole('button', {name: 'Xác nhận', exact: true}).click(),
    ]);
    expect(response.status()).toBe(204);
    await expect(page.getByRole('status')).toContainText(
      decision === 'ACCEPT' ? 'Đã duyệt' : 'Đã yêu cầu',
    );
    await page
      .getByRole('link', {name: 'Về lần chạy / kiểm tra trạng thái'})
      .click();
    await expect(page.getByRole('main')).toContainText(
      decision === 'ACCEPT' ? 'COMPLETED' : 'CREATED',
    );
    const persisted = await page.request.get(api);
    expect(persisted.status()).toBe(200);
    expect((await persisted.json()).data).toMatchObject({
      id: run,
      status: decision === 'ACCEPT' ? 'COMPLETED' : 'CREATED',
      revision: '3',
    });
    expect((await page.request.get(api + '/review')).status()).toBe(404);
    await page.getByRole('link', {name: 'Về Task', exact: true}).click();
    await expect(page).toHaveURL(taskPath);
    await expect(page.getByRole('main')).toContainText(
      decision === 'ACCEPT' ? 'COMPLETED' : 'READY',
    );
  });
}
