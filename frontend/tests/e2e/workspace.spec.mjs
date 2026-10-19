import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

const own = '11111111-1111-4111-8111-111111111111';
const other = '22222222-2222-4222-8222-222222222222';

async function login(page, email) {
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
}

test('empty membership can refresh and change account', async ({page}) => {
  await login(page, process.env.E2E_EMAIL);
  await expect(page.getByRole('heading', {name: 'Bạn chưa có workspace'})).toBeVisible();
  await page.getByRole('button', {name: 'Kiểm tra lại quyền truy cập'}).click();
  await expect(page.getByRole('heading', {name: 'Bạn chưa có workspace'})).toBeVisible();
  await page.getByRole('button', {name: 'Đổi tài khoản', exact: true}).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await page.request.get('/api/v1/auth/session')).status()).toBe(401);
});

test('membership opens real context and reload revalidates; foreign workspace is hidden', async ({page}) => {
  await login(page, 'workspace-qc@example.test');
  await expect(page.getByRole('heading', {name: 'Chọn workspace'})).toBeVisible();
  await expect(page.getByText('QC Workspace Beta', {exact: true})).toHaveCount(0);
  await page.getByRole('button', {name: 'Vào QC Workspace Alpha', exact: true}).click();
  await expect(page).toHaveURL(new RegExp(`/workspace/${own}$`));
  await expect(page.getByRole('heading', {name: 'QC Workspace Alpha', exact: true})).toBeVisible();
  await page.reload();
  await expect(page.getByRole('heading', {name: 'QC Workspace Alpha', exact: true})).toBeVisible();
  expect((await page.request.get(`/api/v1/auth/workspaces/${other}`)).status()).toBe(404);
  await page.goto(`/workspace/${other}`);
  await expect(page).toHaveURL(new RegExp(`/workspace/${other}$`));
  await expect(page.getByRole('heading', {name: 'Không còn quyền truy cập', exact: true})).toBeVisible();
  await expect(page.getByText('QC Workspace Beta', {exact: true})).toHaveCount(0);
  await expect(page.getByRole('heading', {name: 'QC Workspace Alpha', exact: true})).toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});


test('revoked membership disappears on reload of the same workspace URL', async ({page}) => {
  const sql = statement => execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c', 'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {cwd: new URL('../../../', import.meta.url), input: statement, stdio: ['pipe', 'pipe', 'pipe']});
  await login(page, 'workspace-qc@example.test');
  await page.getByRole('button', {name: 'Vào QC Workspace Alpha', exact: true}).click();
  await expect(page.getByRole('heading', {name: 'QC Workspace Alpha', exact: true})).toBeVisible();
  try {
    sql(`DELETE FROM organization_memberships WHERE organization_id = '${own}' AND account_id = (SELECT id FROM human_accounts WHERE email = 'workspace-qc@example.test');`);
    await page.reload();
    await expect(page).toHaveURL(new RegExp(`/workspace/${own}$`));
    await expect(page.getByRole('heading', {name: 'Không còn quyền truy cập', exact: true})).toBeVisible();
    await expect(page.getByText('QC Workspace Alpha', {exact: true})).toHaveCount(0);
    expect((await page.request.get(`/api/v1/auth/workspaces/${own}`)).status()).toBe(404);
  } finally {
    sql(`INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id, '${own}', 'MEMBER' FROM human_accounts WHERE email = 'workspace-qc@example.test' ON CONFLICT DO NOTHING;`);
  }
  await page.goto('/workspace/33333333-3333-4333-8333-333333333333');
  await expect(page.getByRole('heading', {name: 'Không còn quyền truy cập', exact: true})).toBeVisible();
});
