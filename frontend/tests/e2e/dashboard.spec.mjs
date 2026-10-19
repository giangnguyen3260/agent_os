import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

const organization = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
const hidden = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const empty = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const team = 'a1111111-1111-4111-8111-111111111111';
const pendingTask = 'a5555555-5555-4555-8555-555555555552';
const base = `/api/v1/organizations/${organization}/dashboard`;
function sql(statement) {
  execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'postgres',
      'sh',
      '-c',
      'psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      input: statement,
      stdio: ['pipe', 'pipe', 'pipe'],
      timeout: 15000,
    },
  );
}
async function login(page) {
  await page.goto('/login');
  await page
    .getByLabel('Email', {exact: true})
    .fill('dashboard-qc@example.test');
  await page
    .getByLabel('Mật khẩu', {exact: true})
    .fill(process.env.E2E_PASSWORD);
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page).toHaveURL(/\/workspace$/);
}
async function open(page, id = organization) {
  await login(page);
  await page.goto(`/workspace/${id}/dashboard`);
  await expect(
    page.getByRole('heading', {name: 'Organization overview', exact: true}),
  ).toBeVisible();
  await expect(page.getByTestId('metric-running')).toBeVisible();
}

test('real task snapshot and pending drilldown explain waiting work', async ({
  page,
}) => {
  await open(page);
  const summary = await page.request.get(base);
  expect(summary.status()).toBe(200);
  const data = (await summary.json()).data;
  expect(data.snapshot).toMatchObject({
    running: 1,
    pending: 2,
    review: 1,
    totalTeams: 2,
    totalAgents: 1,
    activeAgents: 1,
  });
  expect(data.capacity.available).toBe(false);
  expect(data.performance.available).toBe(false);
  await expect(page.getByTestId('metric-running')).toContainText('1');
  await expect(page.getByTestId('metric-pending')).toContainText('2');
  await expect(page.getByTestId('metric-review')).toContainText('1');
  await page.getByTestId('metric-pending').click();
  await expect(page.getByLabel('Trạng thái', {exact: true})).toHaveValue(
    'PENDING',
  );
  const tasks = page.getByRole('region', {name: 'Danh sách task', exact: true});
  await expect(
    tasks.getByText('Budget approval task', {exact: true}),
  ).toBeVisible();
  await expect(
    tasks.getByText('Cần phê duyệt ngân sách', {exact: true}),
  ).toBeVisible();
  await expect(tasks.getByText('Quản trị viên', {exact: true})).toBeVisible();
  await expect(tasks.getByText('Dependency task', {exact: true})).toBeVisible();
  await expect(tasks.getByText('Secret task title', {exact: true})).toHaveCount(
    0,
  );
  await page.getByLabel('Trạng thái', {exact: true}).selectOption('RUNNING');
  await expect(
    tasks.getByText('Implement runtime API', {exact: true}),
  ).toBeVisible();
  await expect(
    tasks.getByText('Budget approval task', {exact: true}),
  ).toHaveCount(0);
  await page
    .getByTestId('team-row')
    .filter({hasText: 'Engineering'})
    .getByRole('button', {name: '2 Pending', exact: true})
    .click();
  await expect(page.getByLabel('Team', {exact: true})).toHaveValue(team);
  await expect(page.getByLabel('Trạng thái', {exact: true})).toHaveValue(
    'PENDING',
  );
  await expect(
    tasks.getByText('Budget approval task', {exact: true}),
  ).toBeVisible();
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('refresh reflects persisted task transition without mission-count substitution', async ({
  page,
}) => {
  await open(page);
  try {
    sql(
      `UPDATE tasks SET status='RUNNING', pending_reason=null, waiting_since=null, resolver=null, updated_at=now() WHERE id='${pendingTask}';`,
    );
    await page.getByRole('button', {name: 'Làm mới', exact: true}).click();
    await expect(page.getByTestId('metric-running')).toContainText('2');
    await expect(page.getByTestId('metric-pending')).toContainText('1');
    const data = (await (await page.request.get(base)).json()).data;
    expect(data.snapshot.running).toBe(2);
    expect(data.snapshot.pending).toBe(1);
  } finally {
    sql(
      `UPDATE tasks SET status='WAITING', pending_reason='Cần phê duyệt ngân sách', waiting_since=now(), resolver='Quản trị viên', updated_at=now() WHERE id='${pendingTask}';`,
    );
  }
});

test('foreign organization and team do not disclose dashboard data', async ({
  page,
}) => {
  await open(page);
  expect(
    (
      await page.request.get(`/api/v1/organizations/${hidden}/dashboard`)
    ).status(),
  ).toBe(403);
  expect(
    (
      await page.request.get(
        `${base}/tasks?state=PENDING&teamId=b1111111-1111-4111-8111-111111111111`,
      )
    ).status(),
  ).toBe(404);
  expect((await page.request.get(`${base}/tasks?state=OTHER`)).status()).toBe(
    400,
  );
  await page.goto(`/workspace/${hidden}/dashboard`);
  await expect(page.getByTestId('metric-running')).toHaveCount(0);
  await expect(page.getByText('Secret task title', {exact: true})).toHaveCount(
    0,
  );
  await expect(
    page.getByRole('heading', {name: 'Không còn quyền truy cập', exact: true}),
  ).toBeVisible();
});

test('empty dashboard shows real zero counts and unavailable measurements', async ({
  page,
}) => {
  await open(page, empty);
  const data = (
    await (
      await page.request.get(`/api/v1/organizations/${empty}/dashboard`)
    ).json()
  ).data;
  expect(data.snapshot).toMatchObject({
    running: 0,
    pending: 0,
    review: 0,
    totalTeams: 0,
    totalAgents: 0,
  });
  await expect(page.getByTestId('metric-running')).toContainText('0');
  await expect(page.getByTestId('metric-pending')).toContainText('0');
  await expect(page.getByTestId('metric-capacity')).toContainText('—');
  expect(
    await page.evaluate(
      () => document.documentElement.scrollWidth <= innerWidth,
    ),
  ).toBe(true);
});

test('membership revocation clears dashboard on reload', async ({page}) => {
  await open(page);
  try {
    sql(
      `DELETE FROM organization_memberships WHERE organization_id='${organization}' AND account_id=(SELECT id FROM human_accounts WHERE email='dashboard-qc@example.test');`,
    );
    await page.reload();
    await expect(
      page.getByRole('heading', {
        name: 'Không còn quyền truy cập',
        exact: true,
      }),
    ).toBeVisible();
    await expect(page.getByTestId('metric-running')).toHaveCount(0);
    expect((await page.request.get(base)).status()).toBe(403);
  } finally {
    sql(
      `INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id, '${organization}', 'MEMBER' FROM human_accounts WHERE email='dashboard-qc@example.test' ON CONFLICT DO NOTHING;`,
    );
  }
});

test('dashboard transport failure offers retry without invented zero metrics', async ({
  page,
}) => {
  await login(page);
  let failed = false;
  await page.route(
    `**/api/v1/organizations/${organization}/dashboard?*`,
    async route => {
      if (!failed) {
        failed = true;
        await route.abort('failed');
      } else await route.continue();
    },
  );
  await page.goto(`/workspace/${organization}/dashboard`);
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(page.getByTestId('metric-running')).toHaveCount(0);
  await page.getByRole('button', {name: 'Thử lại', exact: true}).click();
  await expect(page.getByTestId('metric-running')).toContainText('1');
});

test('compact navigation is keyboard dismissible and team task filter is scoped', async ({
  page,
}, testInfo) => {
  await open(page);
  if (testInfo.project.name !== 'desktop') {
    await page.getByRole('button', {name: 'Menu', exact: true}).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).not.toBeVisible();
  }
  const tasks = await page.request.get(
    `${base}/tasks?state=PENDING&teamId=${team}`,
  );
  expect(tasks.status()).toBe(200);
  const items = (await tasks.json()).data.items;
  expect(items).toHaveLength(2);
  expect(items.every(item => item.teamId === team)).toBe(true);
});
