import {test, expect} from '@playwright/test';
const org = '95170000-0000-4000-8000-000000000001',
  team = '95170000-0000-4000-8000-000000000002',
  mission = '95170000-0000-4000-8000-000000000003',
  run = '95170000-0000-4000-8000-000000000004',
  agent = '95170000-0000-4000-8000-000000000005';
const path = `/workspace/${org}/teams/${team}/missions/${mission}/plan-proposal`;
const tasks = [
  {
    key: 'a',
    title: 'Research',
    description: 'Reference <script>window.proposalExecuted=true</script>',
    acceptanceCriteria: 'Useful evidence',
    priority: 'NORMAL',
    dependencies: [],
  },
  {
    key: 'b',
    title: 'Write',
    description: 'Combine evidence',
    acceptanceCriteria: 'Final text',
    priority: 'HIGH',
    dependencies: ['a'],
  },
];
const content = JSON.stringify({schemaVersion: 1, tasks});
async function mock(page, options = {}) {
  const state = {denied: false, posts: [], previewPosts: [], attempts: 0};
  await page.route('**/api/v1/**', async route => {
    const req = route.request(),
      url = new URL(req.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'fixture@example.test', displayName: 'Fixture'};
    else if (url.pathname.includes('/auth/workspaces/')) {
      if (state.denied)
        return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
      data = {id: org, role: 'ADMIN'};
    } else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.endsWith('/plan-proposal'))
      data = {
        organizationId: org,
        teamId: team,
        missionId: mission,
        title: 'Empty Mission',
        intakeVersion: '0',
        targetFingerprint: 'a'.repeat(64),
        owners: [{id: agent, name: 'Writer'}],
      };
    else if (url.pathname.endsWith('/source'))
      data = {
        runId: run,
        content: options.invalidSource ? 'Invalid agent response' : content,
      };
    else if (url.pathname.endsWith('/preview')) {
      const input = req.postDataJSON();
      state.previewPosts.push(input);
      data = {
        organizationId: org,
        teamId: team,
        missionId: mission,
        sourceRunId: run,
        sourceMissionId: '95170000-0000-4000-8000-000000000006',
        sourceAttemptId: '95170000-0000-4000-8000-000000000007',
        sourceOutputSha256: 'a'.repeat(64),
        sourceProvenanceSha256: 'b'.repeat(64),
        sourceCompletionFingerprint: 'c'.repeat(64),
        sourceJson: options.invalidSource ? 'Invalid agent response' : content,
        proposalJson: input.proposalJson,
        origin: options.invalidSource ? 'HUMAN_CORRECTED' : 'AGENT_OUTPUT',
        proposal: {...JSON.parse(input.proposalJson), order: ['a', 'b']},
        owners: input.owners,
        intakeVersion: '0',
        fingerprint: 'd'.repeat(64),
        executionAuthorized: false,
      };
      if (options.duplicatePreview) data.proposal.tasks = [tasks[0], tasks[0]];
      if (options.reverseOrder) data.proposal.order = ['b', 'a'];
      if (options.badPreview)
        data.proposal.tasks = [{...tasks[0], title: 'Changed task'}, tasks[1]];
    } else if (url.pathname.endsWith('/apply')) {
      const command = req.postDataJSON();
      state.posts.push(command);
      state.attempts++;
      if (options.conflict)
        return route.fulfill({
          status: 409,
          json: {error: {code: 'MISSION_PLAN_PROPOSAL_CONFLICT'}},
        });
      if (options.uncertain && state.attempts === 1)
        return route.abort('failed');
      data = {
        organizationId: org,
        teamId: team,
        missionId: mission,
        idempotencyKey: command.idempotencyKey,
        sourceRunId: run,
        reviewedFingerprint: command.expectedFingerprint,
        snapshotSha256: 'e'.repeat(64),
        origin: options.invalidSource ? 'HUMAN_CORRECTED' : 'AGENT_OUTPUT',
        intakeVersionBefore: '0',
        intakeVersionAfter: '1',
        taskIds: {
          a: '95170000-0000-4000-8000-000000000008',
          b: '95170000-0000-4000-8000-000000000009',
        },
        executionAuthorized: false,
      };
      if (options.badReceipt && state.attempts === 1)
        data.intakeVersionAfter = '2';
    } else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return state;
}
async function source(page) {
  await page.goto(path);
  await expect(
    page.getByRole('heading', {name: 'Empty Mission', exact: true}),
  ).toBeVisible();
  await page.getByLabel('Run lập kế hoạch nguồn', {exact: true}).fill(run);
  await page
    .getByRole('button', {name: 'Đọc output nguồn đã ACCEPT', exact: true})
    .click();
  await expect(page.getByLabel('JSON đề xuất', {exact: true})).not.toHaveValue(
    '',
  );
}
async function bind(page) {
  for (const key of ['a', 'b'])
    await page
      .getByLabel('Agent cho ' + key, {exact: true})
      .selectOption(agent);
  await page
    .getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Kế hoạch sẽ nhập', exact: true}),
  ).toBeVisible();
}
test('reviews every task with explicit owners and escaped data before atomic apply', async ({
  page,
}) => {
  const state = await mock(page);
  await source(page);
  await expect(
    page.getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true}),
  ).toBeDisabled();
  await bind(page);
  await expect(page.locator('article')).toHaveCount(2);
  await expect(page.locator('article').first()).toContainText(
    tasks[0].description,
  );
  expect(await page.evaluate(() => window.proposalExecuted)).toBeUndefined();
  expect(state.posts).toHaveLength(0);
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Đã nhập 2 Task', exact: true}),
  ).toBeVisible();
  expect(state.posts).toHaveLength(1);
  expect(state.posts[0].owners).toEqual({a: agent, b: agent});
  expect(state.posts[0].proposalJson).toBe(content);
  await expect(
    page.getByRole('link', {
      name: 'Xem và duyệt kế hoạch từ Task',
      exact: true,
    }),
  ).toHaveAttribute('href', path.replace('/plan-proposal', '/plan-review'));
});
test('human correction retains source attribution and requires new review after edits', async ({
  page,
}) => {
  await mock(page, {invalidSource: true});
  await source(page);
  await page.getByLabel('JSON đề xuất', {exact: true}).fill(content);
  await bind(page);
  await expect(page.getByText(/HUMAN_CORRECTED · Output/)).toBeVisible();
  await page.getByLabel('JSON đề xuất', {exact: true}).fill(content + ' ');
  await expect(
    page.getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true}),
  ).toHaveCount(0);
});
test('uncertain application retries exact command with one idempotency key', async ({
  page,
}) => {
  const state = await mock(page, {uncertain: true});
  await source(page);
  await bind(page);
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  const retry = page.getByRole('button', {
    name: 'Gửi lại đúng lệnh áp dụng',
    exact: true,
  });
  await expect(retry).toBeEnabled();
  await expect(page.getByLabel('JSON đề xuất', {exact: true})).toBeDisabled();
  await retry.click();
  await expect(
    page.getByRole('heading', {name: 'Đã nhập 2 Task', exact: true}),
  ).toBeVisible();
  expect(state.posts).toHaveLength(2);
  expect(state.posts[1]).toEqual(state.posts[0]);
});
test('malformed receipt stays uncertain and never creates a new command', async ({
  page,
}) => {
  const state = await mock(page, {badReceipt: true});
  await source(page);
  await bind(page);
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  const retry = page.getByRole('button', {
    name: 'Gửi lại đúng lệnh áp dụng',
    exact: true,
  });
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(
    page.getByRole('heading', {name: 'Đã nhập 2 Task', exact: true}),
  ).toBeVisible();
  expect(state.posts[1]).toEqual(state.posts[0]);
});
test('fresh authority revocation clears private source and removes apply', async ({
  page,
}) => {
  const state = await mock(page);
  await source(page);
  await bind(page);
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('status')).toContainText('Quyền ADMIN');
  await expect(page.getByLabel('JSON đề xuất', {exact: true})).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});
test('changed target rejects apply without claiming partial success', async ({
  page,
}) => {
  const state = await mock(page, {conflict: true});
  await source(page);
  await bind(page);
  await page
    .getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true})
    .click();
  await expect(page.getByRole('status')).toContainText('Không thể xác nhận');
  await expect(page.getByRole('heading', {name: /Đã nhập/})).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
});
test('server preview cannot replace reviewed task content', async ({page}) => {
  const state = await mock(page, {badPreview: true});
  await source(page);
  for (const key of ['a', 'b'])
    await page
      .getByLabel('Agent cho ' + key, {exact: true})
      .selectOption(agent);
  await page
    .getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true})
    .click();
  await expect(page.getByRole('status')).toContainText('Không thể xác nhận');
  await expect(
    page.getByRole('button', {name: 'Áp dụng thành Task DRAFT', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});

for (const option of ['duplicatePreview', 'reverseOrder'])
  test(
    'rejects incomplete or non-topological preview: ' + option,
    async ({page}) => {
      const state = await mock(page, {[option]: true});
      await source(page);
      for (const key of ['a', 'b'])
        await page
          .getByLabel('Agent cho ' + key, {exact: true})
          .selectOption(agent);
      await page
        .getByRole('button', {name: 'Xem trước toàn bộ kế hoạch', exact: true})
        .click();
      await expect(page.getByRole('status')).toContainText(
        'Không thể xác nhận',
      );
      await expect(
        page.getByRole('button', {
          name: 'Áp dụng thành Task DRAFT',
          exact: true,
        }),
      ).toHaveCount(0);
      expect(state.posts).toHaveLength(0);
    },
  );
