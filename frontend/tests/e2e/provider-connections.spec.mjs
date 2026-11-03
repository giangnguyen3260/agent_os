import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const organization = '71a00000-0000-4000-8000-000000000001';
const actor = '71a00000-0000-4000-8000-000000000002';
const url = `/workspace/${organization}/provider-connections`;
const api = `**/api/v1/organizations/${organization}/provider-connections?*`;
const envelope = data => ({data, meta: {requestId: randomUUID()}});

async function session(page) {
  await page.route('**/api/v1/auth/session', route =>
    route.fulfill({
      json: envelope({
        id: actor,
        email: 'provider-fixture@example.test',
        displayName: 'Provider fixture',
      }),
    }),
  );
}

function connection(index) {
  return {
    organizationId: organization,
    id: randomUUID(),
    provider: 'OPENAI',
    credentialMode: 'OPENAI_API_KEY',
    name: `Kết nối ${index}`,
    options: {},
    state: index === 21 ? 'DISABLED' : 'ENABLED',
    revision: '1',
    credentialRevision: '1',
    credentialState: 'UNAVAILABLE',
    credentialMask: null,
    createdAt: '2026-11-04T00:00:00.000000Z',
    updatedAt: '2026-11-04T00:00:00.000000Z',
  };
}

test('provider list browser contract renders pages without horizontal overflow', async ({
  page,
}) => {
  await session(page);
  const rows = Array.from({length: 21}, (_, i) => connection(i + 1));
  rows[0].name = 'X'.repeat(100);
  rows[0].credentialState = 'PRESENT';
  rows[0].credentialMask = '••••••••';
  await page.route(api, route => {
    const index = Number(
      new URL(route.request().url()).searchParams.get('page'),
    );
    return route.fulfill({
      json: envelope({
        organizationId: organization,
        page: index,
        pageSize: 20,
        total: rows.length,
        items: rows.slice(index * 20, (index + 1) * 20),
      }),
    });
  });
  await page.goto(url);
  await expect(
    page.getByRole('heading', {name: 'Kết nối model'}),
  ).toBeVisible();
  const list = page.getByRole('region', {name: 'Danh sách kết nối'});
  await expect(list.getByRole('article')).toHaveCount(20);
  await expect(list.getByText('••••••••', {exact: true})).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
  await expect(page.getByRole('button', {name: 'Trang trước'})).toBeDisabled();
  await page.getByRole('link', {name: 'Trang sau'}).click();
  await expect(list.getByRole('article')).toHaveCount(1);
  await expect(list.getByRole('heading', {name: 'Kết nối 21'})).toBeVisible();
  await expect(list.getByText('Đã vô hiệu hóa')).toBeVisible();
  await expect(
    list.getByText('Chưa có bằng chứng lưu trữ khóa hợp lệ'),
  ).toBeVisible();
  await page.getByRole('button', {name: 'Xem kết nối Kết nối 21'}).click();
  await expect(list.getByText('Phiên bản cấu hình: 1')).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('provider list browser contract rejects foreign rows without displaying their data', async ({
  page,
}) => {
  await session(page);
  await page.route(api, route =>
    route.fulfill({
      json: envelope({
        organizationId: organization,
        page: 0,
        pageSize: 20,
        total: 1,
        items: [{...connection(1), organizationId: randomUUID()}],
      }),
    }),
  );
  await page.goto(url);
  await expect(page.getByRole('alert')).toContainText(
    'Không thể tải danh sách kết nối',
  );
  await expect(page.getByRole('article')).toHaveCount(0);
});

test('provider list browser contract distinguishes an empty page from denied access', async ({
  page,
}) => {
  await session(page);
  let denied = false;
  await page.route(api, route =>
    denied
      ? route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}})
      : route.fulfill({
          json: envelope({
            organizationId: organization,
            page: 0,
            pageSize: 20,
            total: 0,
            items: [],
          }),
        }),
  );
  await page.goto(url);
  await expect(page.getByText('Chưa có kết nối provider.')).toBeVisible();
  denied = true;
  await page.reload();
  await expect(page.getByRole('alert')).toContainText('Bạn cần quyền ADMIN');
  await expect(page.getByText('Chưa có kết nối provider.')).toHaveCount(0);
});
