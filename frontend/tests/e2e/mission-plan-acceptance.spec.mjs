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
    else if (url.pathname.endsWith('/plan-review/accept')) {
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
      headers: {'X-AgentOS-Plan-Acceptance': 'true'},
      json: {data},
    });
  });
  return state;
}

test('explicit confirmation accepts complete snapshot and displays immutable content', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  const button = page.getByRole('button', {
    name: 'Chấp nhận kế hoạch',
    exact: true,
  });
  await expect(button).toBeDisabled();
  await page.getByRole('checkbox').check();
  await button.click();
  await expect(
    page.getByRole('heading', {name: 'Kế hoạch đã duyệt · phiên bản 1'}),
  ).toBeVisible();
  await expect(
    page.getByText('Khớp cấu hình hiện tại.', {exact: true}),
  ).toBeVisible();
  expect(state.commands).toHaveLength(1);
  expect(state.commands[0].expectedFingerprint).toBe('a'.repeat(64));
  await expect(button).toHaveCount(0);
  await page.getByText('Nội dung đã lưu bất biến', {exact: true}).click();
  await expect(
    page
      .locator('section')
      .filter({
        has: page.getByRole('heading', {
          name: 'Kế hoạch đã duyệt · phiên bản 1',
        }),
      })
      .locator('pre')
      .first(),
  ).toHaveText(content);
  expect(await page.evaluate(() => window.planExecuted)).toBeUndefined();
});
test('ambiguous acceptance retains exact command and rechecks admin before retry', async ({
  page,
}) => {
  const state = await mock(page, {ambiguous: true});
  await page.goto(path);
  await page.getByRole('checkbox').check();
  await page
    .getByRole('button', {name: 'Chấp nhận kế hoạch', exact: true})
    .click();
  await expect(
    page.getByRole('button', {name: 'Gửi lại lệnh duyệt'}),
  ).toBeEnabled();
  await page.getByRole('button', {name: 'Gửi lại lệnh duyệt'}).click();
  await expect(
    page.getByRole('heading', {name: 'Kế hoạch đã duyệt · phiên bản 1'}),
  ).toBeVisible();
  expect(state.commands).toHaveLength(2);
  expect(state.commands[1]).toEqual(state.commands[0]);
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Cần quyền ADMIN hiện hành.', {exact: true}),
  ).toBeVisible();
  await expect(
    page.getByRole('heading', {name: 'Kế hoạch đã duyệt · phiên bản 1'}),
  ).toHaveCount(0);
});
test('accepted source remains visible when current configuration becomes stale', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  await page.getByRole('checkbox').check();
  await page
    .getByRole('button', {name: 'Chấp nhận kế hoạch', exact: true})
    .click();
  await expect(
    page.getByText('Khớp cấu hình hiện tại.', {exact: true}),
  ).toBeVisible();
  state.saved.matchesCurrent = false;
  await page.getByRole('button', {name: 'Tải lại cấu hình'}).click();
  await expect(
    page.getByText(
      'Cấu hình đã thay đổi hoặc không còn đủ điều kiện; cần xem xét lại.',
      {exact: true},
    ),
  ).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Chấp nhận kế hoạch', exact: true}),
  ).toBeDisabled();
});
