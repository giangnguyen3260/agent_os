import {test, expect} from '@playwright/test';
const org = '95170000-0000-4000-8000-000000000001',
  team = '91170000-0000-4000-8000-000000000001',
  mission = '91170000-0000-4000-8000-000000000003',
  task = '92170000-0000-4000-8000-000000000001',
  run = '93170000-0000-4000-8000-000000000001';
const taskUrl = `/workspace/${org}/teams/${team}/missions/${mission}/tasks/${task}`;
const runUrl = `/workspace/${org}/execution-runs/${run}`;
const api = `/api/v1/organizations/${org}`;
const taskApi = `${api}/teams/${team}/missions/${mission}/tasks/${task}`;
const output = 'Verified model output';
async function fixture(page, {ambiguous = false, role = 'ADMIN', toolApprovalFingerprint,runReady=false} = {}) {
  let state = runReady?'CREATED':'READY',
    revision = 0,
    attemptNumber = 0;
  const starts = [],
    decisions = [],
    receipts = new Map();
  await page.route('**/api/v1/**', async route => {
    const r = route.request(),
      url = new URL(r.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'fixture@example.test', displayName: 'Fixture'};
    else if (url.pathname.includes('/auth/workspaces/'))
      data = {id: org, name: 'Fixture', role};
    else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.endsWith('/start')) {
      if (r.method() === 'GET')
        data = {
          ...(state === 'READY' ? {taskId: task} : {runId: run}),
          revision: String(revision),
          connectionId: team,
          ownerAgentId: task,
          requestPreview:
            '{\"messages\":[{\"role\":\"user\",\"content\":\"Input\"}]}',
          model: 'fixture-model',
          maxOutputTokens: 100,
          maximumMicrousd: '17508',
          fingerprint: 'a'.repeat(64),
          toolApprovalFingerprint,
        };
      else {
        expect(r.headers()['x-csrf-token']).toBe('fixture');
        const command = r.postDataJSON();
        starts.push(command);
        const old = receipts.get(command.idempotencyKey);
        if (old) data = old;
        else {
          data = {
            dispatchId: crypto.randomUUID(),
            runId: run,
            sourceRevision: String(revision),
          };
          receipts.set(command.idempotencyKey, data);
          state = 'IN_REVIEW';
          revision += 2;
          attemptNumber++;
          if (ambiguous) {
            ambiguous = false;
            return route.fulfill({
              status: 200,
              json: {data: {malformed: true}},
            });
          }
        }
      }
    } else if (url.pathname === taskApi) {
      const time = '2026-11-19T00:00:00Z';
      data = {
        generatedAt: time,
        task: {
          id: task,
          organizationId: org,
          teamId: team,
          missionId: mission,
          title: 'Runnable task',
          status: state === 'CREATED' ? 'READY' : state,
          pendingReason: null,
          waitingSince: null,
          resolver: null,
          createdAt: time,
          updatedAt: time,
          description: null,
          acceptanceCriteria: null,
          priority: null,
          ownerAgentId: null,
          ownerName: null,
          dependencyIds: [],
        },
        mission: {id: mission, title: 'Mission'},
        team: {id: team, name: 'Team'},
      };
    } else if (url.pathname === taskApi + '/activity')
      data = {items: [], total: 0, page: 0, size: 20};
    else if (
      url.pathname === `${api}/execution-runs/${run}` ||
      url.pathname === taskApi + '/execution-run'
    )
      data = {
        id: run,
        teamId: team,
        missionId: mission,
        taskId: task,
        status: state,
        revision: String(revision),
        waitReason: null,
        attempts: [{id: task, number: attemptNumber, status: 'SUCCEEDED'}],
      };
    else if (url.pathname.endsWith('/review/output'))
      data = {
        attemptId: task,
        revision,
        outputSha256: 'b'.repeat(64),
        content: output,
      };
    else if (url.pathname.endsWith('/review/decision')) {
      const d = r.postDataJSON();
      expect(d.expectedRevision).toBe(revision);
      expect(d.outputSha256).toBe('b'.repeat(64));
      decisions.push(d);
      state = d.decision === 'ACCEPT' ? 'COMPLETED' : 'CREATED';
      revision++;
      return route.fulfill({status: 204});
    } else if (url.pathname.endsWith('/review'))
      data = {
        attemptId: task,
        revision,
        outputSha256: 'b'.repeat(64),
        reworkAvailable: true,
      };
    else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return {starts, decisions};
}
test('UI Start, review, rework, new Start and acceptance form a complete loop', async ({
  page,
}) => {
  const tracked = await fixture(page);
  await page.goto(taskUrl);
  await page
    .getByRole('link', {name: 'Khởi chạy Task bằng OpenAI', exact: true})
    .click();
  await expect(page.getByRole('main')).toContainText('fixture-model');
  for (const decision of ['REWORK', 'ACCEPT']) {
    await expect(page.getByLabel('Request đề xuất')).toContainText('Input');
    expect(
      await page.evaluate(() => window.privateRequestExecuted),
    ).toBeUndefined();
    await page
      .getByRole('button', {name: 'Xác nhận Start Task', exact: true})
      .click();
    await expect(page.getByRole('status')).toContainText('Đã ghi nhận Start.');
    await page
      .getByRole('link', {name: 'Về lần chạy / kiểm tra trạng thái'})
      .click();
    await page
      .getByRole('link', {name: 'Duyệt kết quả thực thi', exact: true})
      .click();
    await expect(page.getByLabel('Nội dung kết quả')).toHaveText(output);
    if (decision === 'REWORK')
      await page.getByLabel('Lý do yêu cầu làm lại').fill('More evidence');
    await page
      .getByRole('button', {
        name: decision === 'ACCEPT' ? 'Duyệt kết quả' : 'Yêu cầu làm lại',
        exact: true,
      })
      .click();
    await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
    await expect(page.getByRole('status')).toContainText(
      decision === 'ACCEPT' ? 'Đã duyệt' : 'Đã yêu cầu',
    );
    await page
      .getByRole('link', {name: 'Về lần chạy / kiểm tra trạng thái'})
      .click();
    if (decision === 'REWORK')
      await page
        .getByRole('link', {name: 'Khởi chạy Task', exact: true})
        .click();
  }
  await expect(page.getByRole('main')).toContainText('COMPLETED');
  expect(tracked.starts).toHaveLength(2);
  expect(tracked.decisions.map(d => d.decision)).toEqual(['REWORK', 'ACCEPT']);
  expect(tracked.starts[0].idempotencyKey).not.toBe(
    tracked.starts[1].idempotencyKey,
  );
});
test('ambiguous Start retries the exact retained command', async ({page}) => {
  const tracked = await fixture(page, {ambiguous: true});
  await page.goto(taskUrl + '/start');
  await page
    .getByRole('button', {name: 'Xác nhận Start Task', exact: true})
    .click();
  await expect(page.getByRole('status')).toContainText(
    'Chưa xác nhận được kết quả Start.',
  );
  await page
    .getByRole('button', {name: 'Gửi lại đúng lệnh Start', exact: true})
    .click();
  await expect(page.getByRole('status')).toContainText('Đã ghi nhận Start.');
  expect(tracked.starts).toHaveLength(2);
  expect(tracked.starts[1]).toEqual(tracked.starts[0]);
});
test('MEMBER cannot fetch the Start proposal or send a command', async ({
  page,
}) => {
  const tracked = await fixture(page, {role: 'MEMBER'});
  const requests = [];
  page.on('request', r => {
    if (
      new URL(r.url()).pathname.startsWith('/api/v1/organizations/') &&
      new URL(r.url()).pathname.endsWith('/start')
    )
      requests.push(r.url());
  });
  await page.goto(taskUrl + '/start');
  await expect(page.getByRole('status')).toContainText('Cần quyền ADMIN');
  await expect(
    page.getByRole('button', {name: 'Xác nhận Start Task', exact: true}),
  ).toHaveCount(0);
  expect(requests).toEqual([]);
  expect(tracked.starts).toEqual([]);
});

test('Run Start preview explicitly shows approved Tool fingerprint without starting',async({page})=>{await fixture(page,{runReady:true,toolApprovalFingerprint:'f'.repeat(64)});await page.goto(runUrl+'/start');await expect(page.getByRole('heading',{name:'Tool đã chọn trước model',exact:true})).toBeVisible();await expect(page.getByText('Fingerprint bản duyệt: '+ 'f'.repeat(64),{exact:true})).toBeVisible();});
test('malformed selected Tool fingerprint prevents private preflight rendering',async({page})=>{await fixture(page,{runReady:true,toolApprovalFingerprint:'bad'});await page.goto(runUrl+'/start');await expect(page.getByText('Không xác nhận được điều kiện Start. Cần quyền ADMIN và Run READY hợp lệ; hãy tải lại.',{exact:true})).toBeVisible();await expect(page.getByLabel('Request đề xuất')).toHaveCount(0);});
