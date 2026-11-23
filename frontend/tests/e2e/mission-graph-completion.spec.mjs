import {test, expect} from '@playwright/test';
import {createHash} from 'node:crypto';
const org = '77777777-7777-4777-8777-777777777777',
  team = '75000000-0000-4000-8000-000000000001',
  mission = '75000000-0000-4000-8000-000000000002';
const path = `/workspace/${org}/teams/${team}/missions/${mission}/graph-completion`;
const content = '<script>window.graphOutputExecuted=true</script>Verified';
const outputSha256 = createHash('sha256').update(content).digest('hex');
async function mock(page, options = {}) {
  const outputs = [1, 2].map(n => ({
    taskId: `76000000-0000-4000-8000-00000000000${n}`,
    title: `Task ${n}`,
    runId: `77000000-0000-4000-8000-00000000000${n}`,
    attemptId: `78000000-0000-4000-8000-00000000000${n}`,
    runRevision: '3',
    outputSha256,
    requestSha256: 'b'.repeat(64),
    provenanceSha256: 'c'.repeat(64),
  }));
  const state = {
    status: 'RUNNING',
    revision: '1',
    denied: false,
    ambiguous: false,
    badOutput: false,
    commands: [],
    receipts: new Map(),
    ...options,
  };
  const fingerprint = () =>
    state.status === 'RUNNING'
      ? 'a'.repeat(64)
      : state.status === 'REVIEW'
        ? 'd'.repeat(64)
        : 'e'.repeat(64);
  await page.route('**/api/v1/**', async route => {
    const r = route.request(),
      url = new URL(r.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'admin@example.test', displayName: 'Admin'};
    else if (url.pathname.includes('/auth/workspaces/')) {
      if (state.denied)
        return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
      data = {id: org, role: 'ADMIN'};
    } else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.includes('/outputs/')) {
      const o = outputs.find(x => url.pathname.endsWith(x.runId));
      data = {
        runId: o.runId,
        attemptId: o.attemptId,
        outputSha256,
        content: state.badOutput ? 'Tampered' : content,
      };
    } else if (url.pathname.endsWith('/graph-completion')) {
      if (r.method() === 'POST') {
        const c = r.postDataJSON();
        state.commands.push(c);
        data = state.receipts.get(c.idempotencyKey);
        if (!data) {
          state.status = c.action === 'SUBMIT_REVIEW' ? 'REVIEW' : 'COMPLETED';
          state.revision = String(Number(state.revision) + 1);
          data = {
            organizationId: org,
            teamId: team,
            missionId: mission,
            planRevision: '1',
            idempotencyKey: c.idempotencyKey,
            action: c.action,
            status: state.status,
            controlRevision: state.revision,
            manifestSha256: 'f'.repeat(64),
            outputs,
          };
          state.receipts.set(c.idempotencyKey, data);
        }
        if (state.ambiguous) {
          state.ambiguous = false;
          return route.abort('failed');
        }
      } else
        data = {
          organizationId: org,
          teamId: team,
          missionId: mission,
          status: state.status,
          planRevision: '1',
          controlRevision: state.revision,
          taskCount: 2,
          outputs,
          manifestSha256: 'f'.repeat(64),
          blockers: [],
          action:
            state.status === 'COMPLETED'
              ? null
              : state.status === 'REVIEW'
                ? 'COMPLETE'
                : 'SUBMIT_REVIEW',
          fingerprint: fingerprint(),
        };
    } else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return state;
}
test('full manifest requires two explicit transitions and accepted output stays escaped', async ({
  page,
}) => {
  const state = await mock(page);
  await page.goto(path);
  await expect(page.locator('article')).toHaveCount(2);
  const first = page.getByRole('button', {
    name: 'Đưa Mission vào review',
    exact: true,
  });
  await expect(first).toBeDisabled();
  await page
    .getByRole('button', {name: 'Xem output đã ACCEPT', exact: true})
    .first()
    .click();
  await expect(page.getByLabel('Nội dung output đã ACCEPT')).toHaveText(
    content,
  );
  expect(await page.evaluate(() => window.graphOutputExecuted)).toBeUndefined();
  for (const [button, status] of [
    ['Đưa Mission vào review', 'REVIEW'],
    ['Hoàn tất Mission', 'COMPLETED'],
  ]) {
    await page.getByRole('checkbox').check();
    await page.getByRole('button', {name: button, exact: true}).click();
    await expect(
      page.getByRole('heading', {
        name: `Trạng thái hiện tại: ${status}`,
        exact: true,
      }),
    ).toBeVisible();
  }
  expect(state.commands).toHaveLength(2);
  await expect(page.getByRole('checkbox')).toHaveCount(0);
});
test('ambiguous graph closure retains exact command and fresh authority erases private manifest', async ({
  page,
}) => {
  const state = await mock(page, {ambiguous: true});
  await page.goto(path);
  await page.getByRole('checkbox').check();
  await page
    .getByRole('button', {name: 'Đưa Mission vào review', exact: true})
    .click();
  await expect(
    page.getByRole('button', {name: 'Gửi lại đúng lệnh hoàn tất', exact: true}),
  ).toBeEnabled();
  await page
    .getByRole('button', {name: 'Gửi lại đúng lệnh hoàn tất', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: REVIEW',
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', {
      name: 'Kiểm tra trạng thái hiện tại',
      exact: true,
    }),
  ).toBeEnabled();
  expect(state.commands[0]).toEqual(state.commands[1]);
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(
    page.getByText('Cần quyền ADMIN hiện hành.', {exact: true}),
  ).toBeVisible();
  await expect(page.locator('article')).toHaveCount(0);
});
test('tampered accepted output is never rendered as verified text', async ({
  page,
}) => {
  await mock(page, {badOutput: true});
  await page.goto(path);
  await page
    .getByRole('button', {name: 'Xem output đã ACCEPT', exact: true})
    .first()
    .click();
  await expect(
    page.getByText('Không xác nhận được bản kê hiện tại. Hãy kiểm tra lại.', {
      exact: true,
    }),
  ).toBeVisible();
  await expect(page.getByLabel('Nội dung output đã ACCEPT')).toHaveCount(0);
});

test('download link pins exact output identity without a lifecycle command',async({page})=>{
  const state=await mock(page);await page.goto(path);
  const link=page.getByRole('link',{name:'Tải output đã ACCEPT (.txt)',exact:true}).first();
  await expect(link).toHaveAttribute('href',`/api/v1/organizations/${org}/teams/${team}/missions/${mission}/graph-completion/outputs/77000000-0000-4000-8000-000000000001/download?attemptId=78000000-0000-4000-8000-000000000001&outputSha256=${outputSha256}`);
  expect(state.commands).toHaveLength(0);
});
