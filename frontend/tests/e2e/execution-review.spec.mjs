import {test, expect} from '@playwright/test';

const org = '77777777-7777-4777-8777-777777777777';
const run = '73000000-0000-4000-8000-000000000001';
const attempt = '73000000-0000-4000-8000-000000000002';
const review = {
  attemptId: attempt,
  revision: 2,
  outputSha256: 'a'.repeat(64),
  reworkAvailable: true,
};
const content =
  '<script>window.privateOutputExecuted=true</script>\nKết quả cần kiểm tra';
const path = '/workspace/' + org + '/execution-runs/' + run + '/review';
const team = '73000000-0000-4000-8000-000000000003';
const mission = '73000000-0000-4000-8000-000000000004';
const task = '73000000-0000-4000-8000-000000000005';
const taskPath = `/workspace/${org}/teams/${team}/missions/${mission}/tasks/${task}`;
const taskApi = `/api/v1/organizations/${org}/teams/${team}/missions/${mission}/tasks/${task}`;
const reviewLink = page =>
  page.getByRole('link', {name: 'Duyệt kết quả thực thi', exact: true});
async function mockTask(page, {role = 'ADMIN', status = 'IN_REVIEW'} = {}) {
  const timestamp = '2026-11-18T00:00:00Z';
  await page.route('**/auth/workspaces/' + org, route =>
    route.fulfill({json: {data: {id: org, name: 'Fixture', role}}}),
  );
  await page.route('**' + taskApi, route =>
    route.fulfill({
      json: {
        data: {
          generatedAt: timestamp,
          task: {
            id: task,
            organizationId: org,
            teamId: team,
            missionId: mission,
            title: 'Review fixture task',
            status,
            pendingReason: null,
            waitingSince: null,
            resolver: null,
            createdAt: timestamp,
            updatedAt: timestamp,
            description: null,
            acceptanceCriteria: null,
            priority: null,
            ownerAgentId: null,
            ownerName: null,
            dependencyIds: [],
          },
          mission: {id: mission, title: 'Fixture mission'},
          team: {id: team, name: 'Fixture team'},
        },
      },
    }),
  );
  await page.route('**' + taskApi + '/activity?*', route =>
    route.fulfill({json: {data: {items: [], total: 0, page: 0, size: 20}}}),
  );
}
let decisions;
test.beforeEach(async ({page}) => {
  decisions = [];
  await page.route('**/api/v1/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    let data;
    if (url.pathname.endsWith('/session'))
      data = {id: org, email: 'admin@example.test', displayName: 'Admin'};
    else if (url.pathname.includes('/auth/workspaces/'))
      data = {id: org, name: 'Fixture', role: 'ADMIN'};
    else if (url.pathname.endsWith('/csrf'))
      data = {headerName: 'X-CSRF-TOKEN', token: 'fixture'};
    else if (url.pathname.endsWith('/review/output'))
      data = {...review, content};
    else if (url.pathname.endsWith('/review/decision')) {
      decisions.push(request.postDataJSON());
      expect(request.headers()['x-csrf-token']).toBe('fixture');
      return route.fulfill({status: 204});
    } else if (url.pathname.endsWith('/review')) data = review;
    else return route.fulfill({status: 404});
    return route.fulfill({json: {data}});
  });
});
test('reads escaped output and confirms the exact accepted version', async ({
  page,
}) => {
  await page.goto(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
  expect(
    await page.evaluate(() => window.privateOutputExecuted),
  ).toBeUndefined();
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  expect(decisions).toHaveLength(0);
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText('Đã duyệt kết quả.');
  expect(decisions).toEqual([
    {
      attemptId: attempt,
      expectedRevision: 2,
      outputSha256: review.outputSha256,
      decision: 'ACCEPT',
      reason: null,
    },
  ]);
});
test('requires a rework reason and submits it after confirmation', async ({
  page,
}) => {
  await page.goto(path);
  await expect(
    page.getByRole('button', {name: 'Yêu cầu làm lại', exact: true}),
  ).toBeDisabled();
  await page.getByLabel('Lý do yêu cầu làm lại').fill(' Thêm bằng chứng ');
  await page
    .getByRole('button', {name: 'Yêu cầu làm lại', exact: true})
    .click();
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText('Đã yêu cầu làm lại.');
  expect(decisions[0].reason).toBe('Thêm bằng chứng');
});
test('clears output on conflict and requires a reload', async ({page}) => {
  await page.route('**/review/decision', route => route.fulfill({status: 409}));
  await page.goto(path);
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText(
    'Phiên bản review đã thay đổi',
  );
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
});

test('opens the scoped current execution review from an admin task', async ({
  page,
}) => {
  await mockTask(page);
  await page.route('**' + taskApi + '/execution-review', route =>
    route.fulfill({json: {data: {runId: run}}}),
  );
  await page.goto(taskPath);
  await expect(reviewLink(page)).toHaveAttribute('href', path);
  await reviewLink(page).click();
  await expect(page).toHaveURL(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
});
for (const options of [{role: 'MEMBER'}, {status: 'COMPLETED'}]) {
  test(`does not discover a review for ${options.role || options.status}`, async ({
    page,
  }) => {
    await mockTask(page, options);
    const discoveries = [];
    page.on('request', request => {
      if (new URL(request.url()).pathname.endsWith('/execution-review'))
        discoveries.push(request.url());
    });
    await page.goto(taskPath);
    await expect(page.getByTestId('task-detail')).toHaveAttribute(
      'aria-busy',
      'false',
    );
    await expect(
      page.getByRole('heading', {name: 'Review fixture task', exact: true}),
    ).toBeVisible();
    await expect(reviewLink(page)).toHaveCount(0);
    expect(discoveries).toEqual([]);
  });
}
for (const unavailable of ['missing', 'malformed']) {
  test(`keeps the task readable when its review is ${unavailable}`, async ({
    page,
  }) => {
    await mockTask(page);
    let requested = false;
    await page.route('**' + taskApi + '/execution-review', route => {
      requested = true;
      return unavailable === 'missing'
        ? route.fulfill({status: 404})
        : route.fulfill({json: {data: {runId: '../other-workspace'}}});
    });
    await page.goto(taskPath);
    await expect(page.getByTestId('task-detail')).toHaveAttribute(
      'aria-busy',
      'false',
    );
    await expect(
      page.getByRole('heading', {name: 'Review fixture task', exact: true}),
    ).toBeVisible();
    await expect.poll(() => requested).toBe(true);
    await expect(reviewLink(page)).toHaveCount(0);
  });
}

for (const denied of ['MEMBER', 'foreign workspace']) {
  test(`does not fetch review metadata for ${denied}`, async ({page}) => {
    await page.route('**/auth/workspaces/' + org, route =>
      route.fulfill({
        json: {
          data: {
            id: denied === 'foreign workspace' ? team : org,
            name: 'Fixture',
            role: denied === 'MEMBER' ? 'MEMBER' : 'ADMIN',
          },
        },
      }),
    );
    const requests = [];
    page.on('request', request => {
      if (
        new URL(request.url()).pathname.startsWith('/api/v1/organizations/') &&
        new URL(request.url()).pathname.includes('/execution-runs/')
      )
        requests.push(request.url());
    });
    await page.goto(path);
    await expect(page.getByRole('status')).toContainText(
      'quyền ADMIN hiện hành',
    );
    await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
    expect(requests).toEqual([]);
  });
}

test('rejects malformed attempt IDs before fetching output', async ({page}) => {
  await page.route(
    '**/api/v1/organizations/' + org + '/execution-runs/' + run + '/review',
    route =>
      route.fulfill({json: {data: {...review, attemptId: '-'.repeat(36)}}}),
  );
  const outputs = [];
  page.on('request', request => {
    if (new URL(request.url()).pathname.endsWith('/review/output'))
      outputs.push(request.url());
  });
  await page.goto(path);
  await expect(page.getByRole('status')).toContainText('Hãy tải lại');
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
  expect(outputs).toEqual([]);
});

for (const field of ['attemptId', 'revision', 'outputSha256']) {
  test(`rejects output with mismatched ${field}`, async ({page}) => {
    const changed = {
      attemptId: task,
      revision: 3,
      outputSha256: 'b'.repeat(64),
    };
    await page.route('**/review/output?*', route =>
      route.fulfill({
        json: {data: {...review, content, [field]: changed[field]}},
      }),
    );
    await page.goto(path);
    await expect(page.getByRole('status')).toContainText('Hãy tải lại');
    await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
    await expect(
      page.getByRole('button', {name: 'Duyệt kết quả', exact: true}),
    ).toHaveCount(0);
  });
}

test('clears output and confirmation when current permission is revoked', async ({
  page,
}) => {
  await page.goto(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  await page.route('**/review/decision', route => route.fulfill({status: 403}));
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText(
    'Bạn cần quyền ADMIN hiện hành',
  );
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
  await expect(
    page.getByRole('button', {name: 'Xác nhận', exact: true}),
  ).toHaveCount(0);
});

test('focus reload discards the prepared decision before checking current access', async ({
  page,
}) => {
  await page.goto(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  await page.route('**/auth/workspaces/' + org, route =>
    route.fulfill({json: {data: {id: org, name: 'Fixture', role: 'MEMBER'}}}),
  );
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page.getByRole('status')).toContainText('quyền ADMIN hiện hành');
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
  await expect(
    page.getByRole('button', {name: 'Xác nhận', exact: true}),
  ).toHaveCount(0);
  expect(decisions).toEqual([]);
});

test('rechecks authority before sending a decision', async ({page}) => {
  await page.goto(path);
  await expect(page.getByLabel('Nội dung kết quả')).toHaveText(content);
  await page.getByRole('button', {name: 'Duyệt kết quả', exact: true}).click();
  await page.route('**/auth/workspaces/' + org, route =>
    route.fulfill({json: {data: {id: org, name: 'Fixture', role: 'MEMBER'}}}),
  );
  const writes = [];
  page.on('request', request => {
    if (request.method() === 'POST') writes.push(request.url());
  });
  await page.getByRole('button', {name: 'Xác nhận', exact: true}).click();
  await expect(page.getByRole('status')).toContainText('quyền ADMIN hiện hành');
  await expect(page.getByLabel('Nội dung kết quả')).toHaveCount(0);
  expect(writes).toEqual([]);
});
