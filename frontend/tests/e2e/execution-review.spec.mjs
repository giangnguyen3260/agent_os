import {test, expect} from '@playwright/test';

const org = '77777777-7777-4777-8777-777777777777';
const run = '73000000-0000-4000-8000-000000000001';
const attempt = '73000000-0000-4000-8000-000000000002';
const review = {
  attemptId: attempt,
  revision: 2,
  outputSha256: 'a'.repeat(64),
  reworkAvailable: true,
};
const content =
  '<script>window.privateOutputExecuted=true</script>\nKết quả cần kiểm tra';
const path = '/workspace/' + org + '/execution-runs/' + run + '/review';
let decisions;
test.beforeEach(async ({page}) => {
  decisions = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'admin@example.test', displayName: 'Admin'};
    else if (url.pathname.includes('/auth/workspaces/'))
      data = {id: org, name: 'Fixture', role: 'ADMIN'};
    else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.endsWith('/review/output'))
      data = {...review, content};
    else if (url.pathname.endsWith('/review/decision')) {
      decisions.push(request.postDataJSON());
      expect(request.headers()['x-csrf-token']).toBe('fixture');
      return route.fulfill({status: 204});
    } else if (url.pathname.endsWith('/review')) data = review;
    else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
});
test('reads escaped output and confirms the exact accepted version', async ({
  page,
}) => {
  await page.goto(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
  expect(
    await page.evaluate(() => window.privateOutputExecuted),
  ).toBeUndefined();
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  expect(decisions).toHaveLength(0);
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText('Đã duyệt kết quả.');
  expect(decisions).toEqual([
    {
      attemptId: attempt,
      expectedRevision: 2,
      outputSha256: review.outputSha256,
      decision: 'ACCEPT',
      reason: null,
    },
  ]);
});
test('requires a rework reason and submits it after confirmation', async ({
  page,
}) => {
  await page.goto(path);
  await expect(
    page.getByRole('button', {name: 'Yêu cầu làm lại', exact: true}),
  ).toBeDisabled();
  await page.getByLabel('Lý do yêu cầu làm lại').fill(' Thêm bằng chứng ');
  await page
    .getByRole('button', {name: 'Yêu cầu làm lại', exact: true})
    .click();
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText('Đã yêu cầu làm lại.');
  expect(decisions[0].reason).toBe('Thêm bằng chứng');
});
test('clears output on conflict and requires a reload', async ({page}) => {
  await page.route('**/review/decision', route => route.fulfill({status: 409}));
  await page.goto(path);
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText(
    'Phiên bản review đã thay đổi',
  );
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
});
