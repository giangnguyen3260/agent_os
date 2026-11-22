import {test, expect} from '@playwright/test';
const org = '95180000-0000-4000-8000-000000000001',
  team = '95180000-0000-4000-8000-000000000002',
  mission = '95180000-0000-4000-8000-000000000003',
  run = '95180000-0000-4000-8000-000000000004',
  task = '95180000-0000-4000-8000-000000000005',
  dispatch = '95180000-0000-4000-8000-000000000006';
const path = `/workspace/${org}/teams/${team}/missions/${mission}/execution-controls`;
const confirmation =
  'Tôi xác nhận trạng thái hiện tại và tác động của lệnh này.';
async function mock(page, options = {}) {
  const state = {
    denied: false,
    posts: [],
    revision: 1,
    control: 'OPEN',
    status: 'RUNNING',
    queued: options.queued ?? 0,
    claimed: options.claimed ?? 0,
    unknown: options.unknown ?? 0,
    receipts: new Map(),
  };
  const snapshot = () => {
    const drained = state.claimed === 0,
      reconciled = drained && state.unknown === 0,
      live = !['CANCELLED'].includes(state.status);
    return {
      organizationId: org,
      teamId: team,
      missionId: mission,
      title: 'Governed Mission',
      missionStatus: state.status,
      controlState: state.control,
      controlRevision: String(state.revision),
      planRevision: '1',
      runs: [
        {
          runId: run,
          taskId: task,
          title: 'Data <script>window.controlExecuted=true</script>',
          status:
            state.status === 'CANCELLED'
              ? 'CANCELLED'
              : state.control === 'CANCEL_REQUESTED'
                ? 'CANCELLING'
                : state.claimed
                  ? 'RUNNING'
                  : 'CREATED',
          taskStatus:
            state.status === 'CANCELLED'
              ? 'CANCELLED'
              : state.claimed
                ? 'RUNNING'
                : 'READY',
          revision: '0',
        },
      ],
      work: {
        queued: state.queued,
        claimed: state.claimed,
        unknownDispatches: state.unknown,
        activeAttempts: state.claimed,
        unknownAttempts: state.unknown,
        liveClaims: state.claimed,
        unsettledReservations: state.unknown,
      },
      canPause: live && state.control === 'OPEN',
      canResume: live && state.control === 'PAUSE_REQUESTED' && drained,
      canRequestCancel:
        live && state.control !== 'CANCEL_REQUESTED' && reconciled,
      canAcknowledgeCancel:
        live &&
        state.control === 'CANCEL_REQUESTED' &&
        reconciled &&
        state.queued === 0,
      safelyPaused: state.control === 'PAUSE_REQUESTED' && reconciled,
      blockers: state.unknown
        ? ['RECONCILIATION_PENDING']
        : state.claimed
          ? ['WORKER_DRAIN_PENDING']
          : [],
      fingerprint: 'a'.repeat(63) + state.revision,
      evidenceSha256: 'b'.repeat(64),
      ...options.badSnapshot,
    };
  };
  await page.route('**/api/v1/**', async route => {
    const request = route.request(),
      url = new URL(request.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'fixture@example.test', displayName: 'Fixture'};
    else if (url.pathname.includes('/auth/workspaces/')) {
      if (state.denied)
        return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
      data = {id: org, role: 'ADMIN'};
    } else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.endsWith('/execution-controls')) {
      if (request.method() === 'GET') data = snapshot();
      else {
        const body = request.postDataJSON();
        state.posts.push(body);
        if (options.conflict)
          return route.fulfill({
            status: 409,
            json: {error: {code: 'MISSION_EXECUTION_CONTROL_CONFLICT'}},
          });
        if (state.receipts.has(body.idempotencyKey))
          data = state.receipts.get(body.idempotencyKey);
        else {
          const before = state.revision;
          state.revision++;
          const retired =
            body.action === 'REQUEST_CANCEL' && state.queued > 0
              ? [dispatch]
              : [];
          state.control =
            body.action === 'PAUSE'
              ? 'PAUSE_REQUESTED'
              : body.action === 'RESUME'
                ? 'OPEN'
                : 'CANCEL_REQUESTED';
          if (body.action === 'REQUEST_CANCEL') state.queued = 0;
          if (body.action === 'ACK_CANCEL') state.status = 'CANCELLED';
          data = {
            organizationId: org,
            teamId: team,
            missionId: mission,
            idempotencyKey: body.idempotencyKey,
            action: body.action,
            sourceFingerprint: body.expectedFingerprint,
            controlRevisionBefore: String(before),
            controlRevisionAfter: String(state.revision),
            controlState: state.control,
            missionStatus: state.status,
            retiredQueuedDispatchIds: retired,
            snapshotSha256: 'c'.repeat(64),
          };
          state.receipts.set(body.idempotencyKey, data);
          if (options.uncertain) return route.abort('failed');
          if (options.badReceipt)
            data = {...data, controlState: 'OPEN', controlRevisionAfter: '999'};
        }
      }
    } else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
  return state;
}
async function ready(page) {
  await page.goto(path);
  await expect(
    page.getByRole('heading', {name: 'Governed Mission', exact: true}),
  ).toBeVisible();
}
async function execute(page, label) {
  await page.getByRole('button', {name: label, exact: true}).click();
  const send = page.getByRole('button', {
    name: 'Gửi lệnh đã xác nhận',
    exact: true,
  });
  await expect(send).toBeDisabled();
  await page.getByLabel(confirmation).check();
  await send.click();
}
async function refresh(page) {
  const button = page.getByRole('button', {
    name: 'Kiểm tra trạng thái hiện tại',
    exact: true,
  });
  await expect(button).toBeEnabled();
  await button.click();
  await expect(
    page.getByRole('heading', {name: 'Governed Mission', exact: true}),
  ).toBeVisible();
}
test('explicit pause retains queued work and resume requires a new confirmation', async ({
  page,
}) => {
  const state = await mock(page, {queued: 1});
  await ready(page);
  expect(state.posts).toHaveLength(0);
  expect(await page.evaluate(() => window.controlExecuted)).toBeUndefined();
  await execute(page, 'Tạm dừng thực thi');
  await expect(
    page.getByRole('status').filter({hasText: 'Đã ghi nhận Tạm dừng thực thi'}),
  ).toBeVisible();
  await refresh(page);
  await expect(page.getByText('Đã xếp hàng: 1', {exact: true})).toBeVisible();
  await expect(page.getByText(/Đã dừng tại ranh giới an toàn/)).toBeVisible();
  await execute(page, 'Tiếp tục thực thi');
  await expect(
    page.getByRole('status').filter({hasText: 'Đã ghi nhận Tiếp tục thực thi'}),
  ).toBeVisible();
  expect(state.posts.map(p => p.action)).toEqual(['PAUSE', 'RESUME']);
});
test('claimed request shows drain state and offers neither resume nor cancel', async ({
  page,
}) => {
  const state = await mock(page, {claimed: 1});
  await ready(page);
  await execute(page, 'Tạm dừng thực thi');
  await refresh(page);
  await expect(page.getByText(/Đã yêu cầu tạm dừng; đang chờ/)).toBeVisible();
  await expect(
    page.getByRole('button', {name: 'Tiếp tục thực thi', exact: true}),
  ).toHaveCount(0);
  await expect(
    page.getByRole('button', {name: 'Yêu cầu hủy Mission', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
});
test('unknown accounting is visible and never presented as safe cancellation', async ({
  page,
}) => {
  await mock(page, {unknown: 1});
  await ready(page);
  await execute(page, 'Tạm dừng thực thi');
  await refresh(page);
  await expect(
    page.getByText('Khoản giữ chi phí chưa đối soát: 1', {exact: true}),
  ).toBeVisible();
  await expect(page.getByText(/Đã dừng tại ranh giới an toàn/)).toHaveCount(0);
  await expect(
    page.getByRole('button', {name: 'Yêu cầu hủy Mission', exact: true}),
  ).toHaveCount(0);
});
test('cancellation request and acknowledgement are two explicit operations', async ({
  page,
}) => {
  const state = await mock(page, {queued: 1});
  await ready(page);
  await execute(page, 'Yêu cầu hủy Mission');
  await refresh(page);
  await expect(page.getByText(/Đã yêu cầu hủy\./)).toBeVisible();
  expect(state.status).toBe('RUNNING');
  await execute(page, 'Xác nhận hủy Mission');
  await refresh(page);
  expect(state.status).toBe('CANCELLED');
  await expect(
    page.getByRole('button', {name: 'Tiếp tục thực thi', exact: true}),
  ).toHaveCount(0);
  expect(state.posts.map(p => p.action)).toEqual([
    'REQUEST_CANCEL',
    'ACK_CANCEL',
  ]);
});
test('uncertain committed command retries its exact bytes and idempotency key', async ({
  page,
}) => {
  const state = await mock(page, {uncertain: true});
  await ready(page);
  await execute(page, 'Tạm dừng thực thi');
  const retry = page.getByRole('button', {
    name: 'Gửi lại đúng lệnh điều khiển',
    exact: true,
  });
  await expect(retry).toBeEnabled();
  await expect(
    page.getByRole('button', {
      name: 'Kiểm tra trạng thái hiện tại',
      exact: true,
    }),
  ).toBeDisabled();
  await retry.click();
  await expect(
    page.getByRole('status').filter({hasText: 'Đã ghi nhận Tạm dừng thực thi'}),
  ).toBeVisible();
  expect(state.posts[1]).toEqual(state.posts[0]);
  expect(state.revision).toBe(2);
});
test('malformed receipt remains uncertain without silently accepting state', async ({
  page,
}) => {
  const state = await mock(page, {badReceipt: true});
  await ready(page);
  await execute(page, 'Tạm dừng thực thi');
  const retry = page.getByRole('button', {
    name: 'Gửi lại đúng lệnh điều khiển',
    exact: true,
  });
  await expect(retry).toBeEnabled();
  await retry.click();
  await expect(
    page.getByRole('status').filter({hasText: 'Đã ghi nhận Tạm dừng thực thi'}),
  ).toBeVisible();
  expect(state.posts[1]).toEqual(state.posts[0]);
});
test('authority revocation on focus clears private work and confirmations', async ({
  page,
}) => {
  const state = await mock(page);
  await ready(page);
  await page
    .getByRole('button', {name: 'Tạm dừng thực thi', exact: true})
    .click();
  await page.getByLabel(confirmation).check();
  state.denied = true;
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('status')).toContainText('Quyền ADMIN');
  await expect(
    page.getByRole('heading', {name: 'Governed Mission', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});
test('stale snapshot conflict clears action and does not claim success', async ({
  page,
}) => {
  const state = await mock(page, {conflict: true});
  await ready(page);
  await execute(page, 'Tạm dừng thực thi');
  await expect(page.getByRole('status')).toContainText('Không xác nhận được');
  await expect(
    page.getByRole('status').filter({hasText: 'Đã ghi nhận'}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(1);
});
test('inconsistent resume permission with claimed work cannot offer an action', async ({
  page,
}) => {
  const state = await mock(page, {
    claimed: 1,
    badSnapshot: {
      canResume: true,
      controlState: 'PAUSE_REQUESTED',
      canPause: false,
    },
  });
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText('Không xác nhận được');
  await expect(
    page.getByRole('button', {name: 'Tiếp tục thực thi', exact: true}),
  ).toHaveCount(0);
  expect(state.posts).toHaveLength(0);
});
