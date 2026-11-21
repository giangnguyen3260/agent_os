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
    commands: [],
    saved: null,
    admitted: false,
    graphCommands: [],
    badReceipt: false,
    ambiguous: false,
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
  review.status = 'READY';
  state.saved = {
    organizationId: org,
    teamId: team,
    missionId: mission,
    planRevision: '1',
    snapshot: structuredClone(review),
    matchesCurrent: true,
    executionAuthorized: false,
  };
  const runs = [
    {
      taskId: a,
      runId: '76000000-0000-4000-8000-000000000001',
      root: true,
      taskStatus: 'READY',
      runStatus: 'CREATED',
    },
    {
      taskId: b,
      runId: '76000000-0000-4000-8000-000000000002',
      root: false,
      taskStatus: 'BLOCKED',
      runStatus: 'BLOCKED',
    },
  ];
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
    } else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'test-token'};
    else if (url.pathname.endsWith('/graph-admission')) {
      if (r.method() === 'POST') {
        const command = r.postDataJSON();
        state.graphCommands.push(command);
        state.admitted = true;
        state.saved.matchesCurrent = false;
        review.status = 'RUNNING';
        review.canReviewConfiguration = false;
        review.blockers = [
          'MISSION_STATE_UNAVAILABLE',
          'TASK_STATE_UNAVAILABLE',
        ];
        review.tasks[0].status = 'READY';
        review.tasks[1].status = 'BLOCKED';
        if (state.ambiguous) {
          state.ambiguous = false;
          return route.abort('failed');
        }
        data = {
          organizationId: org,
          teamId: team,
          missionId: mission,
          planRevision: '1',
          idempotencyKey: command.idempotencyKey,
          sourceFingerprint: command.expectedFingerprint,
          runs: state.badReceipt
            ? [...runs].reverse().map(x => ({...x, root: !x.root}))
            : runs,
          executionAuthorized: false,
        };
      } else
        data = {
          organizationId: org,
          teamId: team,
          missionId: mission,
          status: state.admitted ? 'RUNNING' : 'READY',
          planRevision: '1',
          snapshot: state.saved.snapshot,
          runs: state.admitted ? runs : [],
          admitted: state.admitted,
          canAdmit: !state.admitted,
          blockers: state.admitted ? ['ALREADY_ADMITTED'] : [],
          fingerprint: 'd'.repeat(64),
          executionAuthorized: false,
        };
    } else if (url.pathname.endsWith('/plan-review/accept')) {
      const command = r.postDataJSON();
      state.commands.push(command);
      const source = structuredClone(review);
      if (!state.saved)
        state.saved = {
          organizationId: org,
          teamId: team,
          missionId: mission,
          planRevision: '1',
          snapshot: source,
          matchesCurrent: true,
          executionAuthorized: false,
        };
      review.status = 'READY';
      review.fingerprint = 'b'.repeat(64);
      if (state.ambiguous) {
        state.ambiguous = false;
        return route.abort('failed');
      }
      data = {
        organizationId: org,
        teamId: team,
        missionId: mission,
        planRevision: '1',
        idempotencyKey: command.idempotencyKey,
        sourceFingerprint: command.expectedFingerprint,
        executionAuthorized: false,
      };
    } else if (url.pathname.endsWith('/accepted-plan'))
      data = state.saved || {
        organizationId: org,
        teamId: team,
        missionId: mission,
        planRevision: null,
        snapshot: null,
        matchesCurrent: false,
        executionAuthorized: false,
      };
    else if (url.pathname.endsWith('/plan-review')) {
      state.reads++;
      data = review;
    } else return route.fulfill({status: 404});
    return route.fulfill({
      headers: {
        'X-AgentOS-Plan-Acceptance': 'true',
        'X-AgentOS-Graph-Admission': 'true',
      },
      json: {data},
    });
  });
  return state;
}

test('explicit graph confirmation admits complete Runs and shows only dependency-ready Start links', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  const button = page.getByRole('button', {
    name: 'Tiếp nhận toàn bộ kế hoạch',
    exact: true,
  });
  await expect(button).toBeDisabled();
  await page
    .getByLabel('Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.')
    .check();
  await button.click();
  const panel = page.locator('section').filter({
    has: page.getByRole('heading', {
      name: 'Thực thi theo kế hoạch',
      exact: true,
    }),
  });
  await expect(panel.locator('article')).toHaveCount(2);
  await expect(
    panel.getByRole('link', {name: 'Xem trước và Start Run', exact: true}),
  ).toHaveCount(1);
  expect(state.graphCommands).toHaveLength(1);
  expect(state.graphCommands[0].expectedPlanRevision).toBe('1');
  await expect(button).toHaveCount(0);
});
test('ambiguous admission reuses exact command and erases private state after role revocation', async ({
  page,
}) => {
  const state = await mock(page, {ambiguous: true});
  await page.goto(path);
  await page
    .getByLabel('Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.')
    .check();
  await page
    .getByRole('button', {name: 'Tiếp nhận toàn bộ kế hoạch', exact: true})
    .click();
  await expect(
    page.getByRole('button', {name: 'Gửi lại lệnh tiếp nhận', exact: true}),
  ).toBeEnabled();
  await page
    .getByRole('button', {name: 'Gửi lại lệnh tiếp nhận', exact: true})
    .click();
  await expect(
    page.getByText(/Đã ghi nhận tiếp nhận phiên bản 1/),
  ).toBeVisible();
  expect(state.graphCommands).toHaveLength(2);
  expect(state.graphCommands[0]).toEqual(state.graphCommands[1]);
  // Receipt visibility precedes the mandatory current-state reload finishing.
  await expect(
    page.getByRole('button', {name: 'Tải lại cấu hình', exact: true}),
  ).toBeEnabled();
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Cần quyền ADMIN hiện hành.', {exact: true}),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', {name: 'Thực thi theo kế hoạch', exact: true}),
  ).toHaveCount(0);
});
test('malformed admission mapping cannot confirm a successful receipt', async ({
  page,
}) => {
  const state = await mock(page, {badReceipt: true});
  await page.goto(path);
  await page
    .getByLabel('Tôi xác nhận tiếp nhận toàn bộ kế hoạch đã duyệt này.')
    .check();
  await page
    .getByRole('button', {name: 'Tiếp nhận toàn bộ kế hoạch', exact: true})
    .click();
  await expect(
    page.getByRole('button', {name: 'Gửi lại lệnh tiếp nhận', exact: true}),
  ).toBeEnabled();
  await expect(page.getByText(/Đã ghi nhận tiếp nhận phiên bản 1/)).toHaveCount(
    0,
  );
  expect(state.graphCommands).toHaveLength(1);
});
