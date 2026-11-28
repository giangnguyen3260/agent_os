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
      if(options.retryConflict && state.attempts===2)return route.fulfill({status:409,json:{error:'Conflict'}});
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

test('planner guidance is read-only and never previews or applies a sample',async({page})=>{const s=await mock(page);await page.goto(path);await page.getByText('Chuẩn bị đầu ra cho Task lập kế hoạch',{exact:true}).click();const sample=page.getByLabel('Mẫu JSON đầu ra',{exact:true});await expect(sample).toHaveAttribute('readonly','');const value=JSON.parse(await sample.inputValue());expect(value.schemaVersion).toBe(1);expect(value.tasks.map(t=>t.key)).toEqual(['research','write']);expect(value.tasks[1].dependencies).toEqual(['research']);expect(s.previewPosts).toHaveLength(0);expect(s.posts).toHaveLength(0);});

test('invalid JSON feedback blocks preview until correction without changing source',async({page})=>{const s=await mock(page);await source(page);await page.getByLabel('JSON đề xuất',{exact:true}).fill('{bad');await expect(page.getByRole('alert')).toContainText('JSON chưa hợp lệ.');expect(s.previewPosts).toHaveLength(0);await page.getByLabel('JSON đề xuất',{exact:true}).fill(JSON.stringify({schemaVersion:2,tasks}));await expect(page.getByRole('alert')).toHaveText('schemaVersion phải là số 1.');await page.getByLabel('JSON đề xuất',{exact:true}).fill(JSON.stringify({schemaVersion:1,tasks:[tasks[0],tasks[0]]}));await expect(page.getByRole('alert')).toContainText('key riêng');await page.getByLabel('JSON đề xuất',{exact:true}).fill(content);await expect(page.getByRole('alert')).toHaveCount(0);expect(s.posts).toHaveLength(0);});

test('restoring accepted source requires confirmation and invalidates reviewed owners',async({page})=>{const s=await mock(page);await source(page);const restore=page.getByRole('button',{name:'Khôi phục JSON nguồn đã ACCEPT',exact:true});await expect(restore).toBeDisabled();const changed=JSON.stringify({...JSON.parse(content),tasks:tasks.map(t=>({...t,title:t.title+' corrected'}))});await page.getByLabel('JSON đề xuất',{exact:true}).fill(changed);await page.getByLabel('Agent cho a',{exact:true}).selectOption(agent);page.once('dialog',d=>d.dismiss());await restore.click();await expect(page.getByLabel('JSON đề xuất',{exact:true})).toHaveValue(changed);page.once('dialog',d=>d.accept());await restore.click();await expect(page.getByLabel('JSON đề xuất',{exact:true})).toHaveValue(content);await expect(page.getByLabel('Agent cho a',{exact:true})).toHaveValue('');await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toHaveCount(0);await expect(page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true})).toBeDisabled();expect(s.posts).toHaveLength(0);expect(s.previewPosts).toHaveLength(0);});

test('bulk owner selection is explicit and invalidates prior preview without applying',async({page})=>{const s=await mock(page);await source(page);const bulk=page.getByRole('button',{name:'Gán Agent đã chọn cho toàn bộ Task',exact:true});await expect(bulk).toBeDisabled();await page.getByLabel('Agent dùng cho toàn bộ Task',{exact:true}).selectOption(agent);await expect(page.getByLabel('Agent cho a',{exact:true})).toHaveValue('');await bulk.click();for(const key of ['a','b'])await expect(page.getByLabel('Agent cho '+key,{exact:true})).toHaveValue(agent);expect(s.previewPosts).toHaveLength(0);expect(s.posts).toHaveLength(0);await page.getByRole('button',{name:'Xem trước toàn bộ kế hoạch',exact:true}).click();await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toBeVisible();await bulk.click();await expect(page.getByRole('heading',{name:'Kế hoạch sẽ nhập',exact:true})).toHaveCount(0);expect(s.previewPosts).toHaveLength(1);expect(s.posts).toHaveLength(0);});

test('dismissed navigation preserves correction and permits explicit departure',async({page})=>{const s=await mock(page);await source(page);const changed=content+' ';await page.getByLabel('JSON đề xuất',{exact:true}).fill(changed);page.once('dialog',d=>d.dismiss());await page.getByRole('link',{name:'Về Mission workspace',exact:true}).click();await expect(page).toHaveURL(new RegExp('/plan-proposal$'));await expect(page.getByLabel('JSON đề xuất',{exact:true})).toHaveValue(changed);page.once('dialog',d=>d.accept());await page.getByRole('link',{name:'Về Mission workspace',exact:true}).click();await expect(page).not.toHaveURL(new RegExp('/plan-proposal$'));expect(s.posts).toHaveLength(0);});
test('uncertain command remains exactly retryable after canceled departure',async({page})=>{const s=await mock(page,{uncertain:true});await source(page);await bind(page);await page.getByRole('button',{name:'Áp dụng thành Task DRAFT',exact:true}).click();await expect(page.getByRole('button',{name:'Gửi lại đúng lệnh áp dụng',exact:true})).toBeEnabled();page.once('dialog',async d=>{expect(d.message()).toContain('chưa rõ kết quả');await d.dismiss();});await page.getByRole('link',{name:'Về Mission workspace',exact:true}).click();await expect(page).toHaveURL(new RegExp('/plan-proposal$'));await page.getByRole('button',{name:'Gửi lại đúng lệnh áp dụng',exact:true}).click();await expect(page.getByRole('heading',{name:'Đã nhập 2 Task',exact:true})).toBeVisible();expect(s.posts[0]).toEqual(s.posts[1]);});

test('conflict after lost apply response preserves exact command through another retry',async({page})=>{const s=await mock(page,{uncertain:true,retryConflict:true});await source(page);await bind(page);await page.getByRole('button',{name:'Áp dụng thành Task DRAFT',exact:true}).click();const retry=page.getByRole('button',{name:'Gửi lại đúng lệnh áp dụng',exact:true});await expect(page.getByRole('status')).toHaveText('Chưa xác định kết quả. Gửi lại đúng lệnh đang giữ để đối chiếu.');await expect(retry).toBeEnabled();await retry.click();await expect.poll(()=>s.posts.length).toBe(2);await expect(retry).toBeEnabled();await expect(page.getByRole('button',{name:'Tải lại điều kiện nhập',exact:true})).toBeDisabled();expect(s.posts).toHaveLength(2);expect(s.posts[1]).toEqual(s.posts[0]);await retry.click();await expect(page.getByRole('heading',{name:'Đã nhập 2 Task',exact:true})).toBeVisible();expect(s.posts).toHaveLength(3);expect(s.posts[2]).toEqual(s.posts[0]);});
