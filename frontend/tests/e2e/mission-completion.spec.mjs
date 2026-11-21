import {test, expect} from '@playwright/test';
import {createHash} from 'node:crypto';

const org = '77777777-7777-4777-8777-777777777777';
const team = '74000000-0000-4000-8000-000000000001';
const mission = '74000000-0000-4000-8000-000000000002';
const task = '74000000-0000-4000-8000-000000000003';
const run = '74000000-0000-4000-8000-000000000004';
const attempt = '74000000-0000-4000-8000-000000000005';
const path = `/workspace/${org}/teams/${team}/missions/${mission}/completion`;
const output =
  '<script>window.completionOutputExecuted=true</script>\nAccepted output';
const sha = createHash('sha256').update(output).digest('hex');
const fingerprint = state => createHash('sha256').update(state).digest('hex');
async function fixture(page, options = {}) {
  const state = {
    status: 'RUNNING',
    role: 'ADMIN',
    posts: [],
    ambiguous: false,
    ...options,
  };
  const receipts = new Map();
  await page.route('**/api/v1/**', async route => {
    const request = route.request(),
      url = new URL(request.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'admin@example.test', displayName: 'Admin'};
    else if (url.pathname.includes('/auth/workspaces/')) {
      if (state.workspaceDenied)
        return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
      data = {id: org, name: 'Workspace', role: state.role};
    } else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (
      url.pathname.endsWith('/completion') &&
      request.method() === 'GET'
    ) {
      const blocked = !!state.blockers?.length;
      data = {
        organizationId: org,
        teamId: team,
        missionId: mission,
        status: state.status,
        controlRevision:
          state.status === 'RUNNING'
            ? '1'
            : state.status === 'REVIEW'
              ? '2'
              : '3',
        taskId: blocked ? null : task,
        runId: blocked ? null : run,
        attemptId: blocked ? null : attempt,
        outputSha256: blocked ? null : sha,
        outputText: blocked ? null : state.tamper ? 'Changed output' : output,
        action:
          blocked || state.status === 'COMPLETED'
            ? null
            : state.status === 'RUNNING'
              ? 'SUBMIT_REVIEW'
              : 'COMPLETE',
        blockers: state.blockers || [],
        fingerprint: fingerprint(state.status),
      };
    } else if (
      url.pathname.endsWith('/completion') &&
      request.method() === 'POST'
    ) {
      const body = request.postDataJSON();
      state.posts.push(body);
      expect(request.headers()['x-csrf-token']).toBe('fixture');
      if (state.conflict)
        return route.fulfill({
          status: 409,
          json: {error: {code: 'MISSION_COMPLETION_CONFLICT'}},
        });
      if (!receipts.has(body.idempotencyKey)) {
        const next = body.action === 'SUBMIT_REVIEW' ? 'REVIEW' : 'COMPLETED';
        receipts.set(body.idempotencyKey, {
          missionId: mission,
          action: body.action,
          status: next,
          controlRevision: next === 'REVIEW' ? '2' : '3',
          taskId: task,
          runId: run,
          attemptId: attempt,
          outputSha256: sha,
        });
        state.status = next;
      }
      if (state.ambiguous && state.posts.length === 1)
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: '{"data":',
        });
      data = {...receipts.get(body.idempotencyKey)};
      if (state.badReceipt) data.outputSha256 = 'f'.repeat(64);
    } else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return state;
}
const submit = page =>
  page.getByRole('button', {name: 'Đưa Mission vào review', exact: true});
const confirm = page =>
  page.getByRole('button', {name: 'Xác nhận', exact: true});

test('reviews accepted text and explicitly transitions through REVIEW before COMPLETED', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto(path);
  await expect(page.getByLabel('Output đã ACCEPT')).toHaveText(output);
  expect(
    await page.evaluate(() => window.completionOutputExecuted),
  ).toBeUndefined();
  await submit(page).click();
  expect(state.posts).toHaveLength(0);
  await confirm(page).click();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: REVIEW',
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole('button', {name: 'Hoàn tất Mission', exact: true})
    .click();
  expect(state.posts).toHaveLength(1);
  await confirm(page).click();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: COMPLETED',
      exact: true,
    }),
  ).toBeVisible();
  expect(state.posts.map(p => p.action)).toEqual(['SUBMIT_REVIEW', 'COMPLETE']);
  expect(state.posts.map(p => p.expectedFingerprint)).toEqual([
    fingerprint('RUNNING'),
    fingerprint('REVIEW'),
  ]);
  expect(state.posts[0].idempotencyKey).not.toBe(state.posts[1].idempotencyKey);
});

test('unsupported scope explains the blocker without a completion action', async ({
  page,
}) => {
  const state = await fixture(page, {blockers: ['UNSUPPORTED_SCOPE']});
  await page.goto(path);
  await expect(
    page.getByText(
      'Phiên bản này yêu cầu Mission có đúng một Task và một lần chạy.',
      {exact: true},
    ),
  ).toBeVisible();
  await expect(submit(page)).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});

test('ambiguous acknowledgement retains the exact command across current-state inspection', async ({
  page,
}) => {
  const state = await fixture(page, {ambiguous: true});
  await page.goto(path);
  await submit(page).click();
  await confirm(page).click();
  await expect(
    page.getByRole('heading', {name: 'Kết quả gửi chưa xác định', exact: true}),
  ).toBeVisible();
  await page
    .getByRole('button', {name: 'Kiểm tra trạng thái hiện tại', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {
      name: 'Trạng thái hiện tại: REVIEW',
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Hoàn tất Mission', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
  await page
    .getByRole('button', {name: 'Gửi lại đúng lệnh', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Kết quả gửi chưa xác định', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(2);
  expect(state.posts[1]).toEqual(state.posts[0]);
  await expect(
    page.getByRole('button', {name: 'Hoàn tất Mission', exact: true}),
  ).toBeVisible();
});

test('revoked ADMIN authority clears output and prevents command submission', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto(path);
  await submit(page).click();
  state.role = 'MEMBER';
  await confirm(page).click();
  await expect(page.getByRole('status')).toContainText('Cần quyền ADMIN');
  await expect(page.getByLabel('Output đã ACCEPT')).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});

test('conflict clears stale confirmation and requires a fresh assessment', async ({
  page,
}) => {
  const state = await fixture(page, {conflict: true});
  await page.goto(path);
  await submit(page).click();
  await confirm(page).click();
  await expect(page.getByRole('status')).toContainText('Không xác nhận được');
  await expect(confirm(page)).toHaveCount(0);
  await expect(submit(page)).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
});

test('rejects mismatched output integrity before offering an action', async ({
  page,
}) => {
  const state = await fixture(page, {tamper: true});
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText('Không xác nhận được');
  await expect(submit(page)).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});

test('mismatched receipt is uncertain and does not claim the command was recorded', async ({
  page,
}) => {
  const state = await fixture(page, {badReceipt: true});
  await page.goto(path);
  await submit(page).click();
  await confirm(page).click();
  await expect(
    page.getByRole('heading', {name: 'Kết quả gửi chưa xác định', exact: true}),
  ).toBeVisible();
  await expect.poll(() => state.posts.length).toBe(1);
  await expect(page.getByRole('status')).toContainText(
    'Kết quả gửi chưa xác định',
  );
  await expect(
    page.getByRole('region', {name: 'Biên nhận thao tác', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
});

test('workspace HTTP403 clears private assessment and pending confirmation', async ({
  page,
}) => {
  const state = await fixture(page);
  await page.goto(path);
  await submit(page).click();
  state.workspaceDenied = true;
  await confirm(page).click();
  await expect(page.getByRole('status')).toContainText('Cần quyền ADMIN');
  await expect(page.getByLabel('Output đã ACCEPT')).toHaveCount(0);
  await expect(
    page.getByRole('button', {name: 'Gửi lại đúng lệnh', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});
