import {test, expect} from '@playwright/test';
const org = '77777777-7777-4777-8777-777777777777',
  team = '75000000-0000-4000-8000-000000000001',
  mission = '75000000-0000-4000-8000-000000000002',
  agent = '75000000-0000-4000-8000-000000000003',
  a = '75000000-0000-4000-8000-000000000004',
  b = '75000000-0000-4000-8000-000000000005';
const path = `/workspace/${org}/teams/${team}/missions/${mission}/plan-review`;
const content = '<script>window.planExecuted=true</script>Input';
async function mock(page, options = {}) {
  const task = id => ({
    id,
    title: id === a ? 'First Task' : 'Second Task',
    status: 'DRAFT',
    ownerId: agent,
    ownerName: 'Writer',
    ownerStatus: 'ACTIVE',
    editRevision: '0',
    ownerRevision: '0',
    dependencyRevision: '0',
    description: content,
    acceptanceCriteria: 'Complete',
    priority: 'NORMAL',
  });
  const state = {
    role: 'ADMIN',
    denied: false,
    writes: [],
    reads: 0,
    ...options,
  };
  const review = {
    organizationId: org,
    teamId: team,
    missionId: mission,
    title: 'Configuration plan',
    status: 'CREATED',
    taskCount: 2,
    edgeCount: 1,
    tasks: [task(a), task(b)],
    edges: [{taskId: b, dependencyId: a}],
    order: [a, b],
    blockers: [],
    canReviewConfiguration: true,
    executionAuthorized: false,
    fingerprint: 'a'.repeat(64),
    ...options.review,
  };
  await page.route('**/api/v1/**', async route => {
    const r = route.request(),
      url = new URL(r.url());
    if (r.method() !== 'GET') state.writes.push(r.method());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'admin@example.test', displayName: 'Admin'};
    else if (url.pathname.includes('/auth/workspaces/')) {
      if (state.denied)
        return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
      data = {id: org, name: 'Workspace', role: state.role};
    } else if (url.pathname.endsWith('/plan-review')) {
      state.reads++;
      data = review;
    } else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return state;
}
test('shows full dependency order and escaped input without authorizing execution', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  await expect(
    page.getByRole('heading', {name: 'Configuration plan', exact: true}),
  ).toBeVisible();
  await expect(page.locator('article h3')).toHaveText([
    'First Task',
    'Second Task',
  ]);
  await page
    .getByText('Đầu vào và tiêu chí chấp nhận', {exact: true})
    .first()
    .click();
  await expect(page.locator('article pre').first()).toHaveText(content);
  expect(await page.evaluate(() => window.planExecuted)).toBeUndefined();
  expect(state.writes).toHaveLength(0);
  await expect(
    page.getByRole('button', {name: /Start|Chấp nhận|Khởi chạy/}),
  ).toHaveCount(0);
});
test('cycle assessment shows all configured tasks and a blocker', async ({
  page,
}) => {
  await mock(page, {
    review: {
      order: [],
      blockers: ['INVALID_DEPENDENCY_GRAPH'],
      canReviewConfiguration: false,
      edgeCount: 2,
      edges: [
        {taskId: b, dependencyId: a},
        {taskId: a, dependencyId: b},
      ],
    },
  });
  await page.goto(path);
  await expect(
    page.getByText('Quan hệ phụ thuộc không hợp lệ hoặc có chu trình.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.locator('article')).toHaveCount(2);
});
test('rejects partial topological order rather than silently dropping a task', async ({
  page,
}) => {
  await mock(page, {review: {order: [a]}});
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText(
    'Không xác nhận được toàn bộ cấu hình',
  );
  await expect(page.locator('article')).toHaveCount(0);
});
test('rejects an execution-authorized response on this read-only page', async ({
  page,
}) => {
  await mock(page, {review: {executionAuthorized: true}});
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText(
    'Không xác nhận được toàn bộ cấu hình',
  );
  await expect(page.locator('article')).toHaveCount(0);
});
test('MEMBER cannot request private plan assessment', async ({page}) => {
  const state = await mock(page, {role: 'MEMBER'});
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText('Cần quyền ADMIN');
  expect(state.reads).toBe(0);
});
test('focus rechecks current authority and erases previously read input', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  await expect(page.locator('article')).toHaveCount(2);
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('status')).toContainText('Cần quyền ADMIN');
  await expect(page.locator('article')).toHaveCount(0);
  expect(state.writes).toHaveLength(0);
});

test('Mission workspace retains disabled Plan for MEMBER without the ADMIN review link', async ({
  page,
}) => {
  await mock(page, {role: 'MEMBER'});
  await page.route(/\/api\/v1\/.*\/workspace$/, r =>
    r.fulfill({
      json: {
        data: {
          generatedAt: '2026-11-21T00:00:00Z',
          mission: {
            id: mission,
            organizationId: org,
            teamId: team,
            teamName: 'Team',
            title: 'Configuration plan',
            status: 'CREATED',
            priority: 'NORMAL',
            managerAgentId: null,
            managerName: null,
          },
          summary: {
            totalTasks: 0,
            completedTasks: 0,
            runningTasks: 0,
            pendingTasks: 0,
            reviewTasks: 0,
          },
        },
      },
    }),
  );
  await page.route(/\/api\/v1\/.*\/workspace\/tasks(?:\?.*)?$/, r =>
    r.fulfill({json: {data: {items: [], page: 0, size: 20, total: 0}}}),
  );
  await page.goto(path.replace('/plan-review', '/workspace'));
  await expect(
    page.getByRole('button', {name: 'Plan', exact: true}),
  ).toBeDisabled();
  await expect(
    page.getByRole('link', {name: 'Xem kế hoạch từ Task', exact: true}),
  ).toHaveCount(0);
});
