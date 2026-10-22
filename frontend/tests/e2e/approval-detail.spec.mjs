import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {mkdirSync} from 'node:fs';

const org = '30303030-3030-4030-8030-303030303030';
const foreign = '31313131-3131-4131-8131-313131313131';
const ident = (prefix, n) =>
  `${prefix}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const offset = info =>
  ({desktop: 0, tablet: 100, mobile: 200})[info.project.name];
const path = n => `/api/v1/organizations/${org}/approvals/${ident('3050', n)}`;
const url = n => `/workspace/${org}/approvals/${ident('3050', n)}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const record = p =>
  p.locator('section').filter({
    has: p.getByRole('heading', {name: 'Decision record', exact: true}),
  });
async function login(p, role = 'admin') {
  expect(process.env.E2E_PASSWORD).toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`approval-detail-${role}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, n) {
  const [response] = await Promise.all([
    p.waitForResponse(
      r =>
        new URL(r.url()).pathname === path(n) && r.request().method() === 'GET',
    ),
    p.goto(url(n)),
  ]);
  expect(response.status()).toBe(200);
  const data = (await response.json()).data;
  await expect(
    p.getByRole('heading', {name: data.request.title, exact: true}),
  ).toBeVisible();
  return data;
}
async function acknowledge(p) {
  await expect(button(p, 'Approve')).toBeDisabled();
  await button(p, 'Verify target & scope').click();
  await expect(
    p.getByRole('heading', {name: 'Review before approving', exact: true}),
  ).toBeVisible();
  await button(p, 'I confirm this scope').click();
  await expect(button(p, 'Approve')).toBeEnabled();
}
async function screenshot(p, info, state) {
  mkdirSync('qa-visuals', {recursive: true});
  await p.screenshot({
    path: `qa-visuals/approval-detail-${info.project.name}-${state}.png`,
  });
}
function body(data, extra = {}) {
  return {
    snapshotVersion: data.snapshot.version,
    snapshotSha256: data.snapshot.payloadSha256,
    acknowledgement: true,
    reason: '',
    ...extra,
  };
}
async function csrf(p) {
  const r = await p.request.get('/api/v1/auth/csrf');
  expect(r.status()).toBe(200);
  return (await r.json()).data;
}

test('AD-B01 real admin approval, scope acknowledgement, immutable readback and Center entry', async ({
  page: p,
}, info) => {
  const n = offset(info) + 1;
  await login(p);
  const data = await open(p, n);
  expect(data.eligibility.canApprove).toBe(true);
  await expect(p.getByRole('main')).toContainText(
    data.snapshot.parameters.resourcePath,
  );
  await expect(p.getByRole('main')).toContainText(
    data.snapshot.parameters.destination,
  );
  await expect(p.getByRole('main')).toContainText(
    data.snapshot.parameters.visibility,
  );
  await screenshot(p, info, 'pending');
  await button(p, 'Verify target & scope').click();
  await screenshot(p, info, 'verify');
  await button(p, 'Cancel').click();
  await expect(button(p, 'Approve')).toBeDisabled();
  await acknowledge(p);
  await screenshot(p, info, 'acknowledged');
  const post = p.waitForResponse(
    r => new URL(r.url()).pathname === `${path(n)}/approve`,
  );
  await button(p, 'Approve').click();
  expect((await post).status()).toBe(201);
  await expect(record(p)).toContainText('APPROVED');
  await expect(record(p)).toContainText('Approval Detail Admin');
  await expect(record(p)).toContainText(data.snapshot.payloadSha256);
  await expect(button(p, 'Approve')).toBeDisabled();
  await screenshot(p, info, 'approved');
  await p.reload();
  await expect(record(p)).toContainText('APPROVED');
  await p.getByRole('link', {name: 'Back to approvals', exact: true}).click();
  await button(p, /Decided/).click();
  await p.getByRole('link', {name: data.request.title, exact: true}).click();
  await expect(record(p)).toContainText('APPROVED');
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
});

test('AD-B02 rejection requires a blank-start reason and persists exact safe record', async ({
  page: p,
}, info) => {
  await login(p);
  await open(p, offset(info) + 3);
  await button(p, 'Reject').click();
  await expect(p.getByLabel('Reason *', {exact: true})).toHaveValue('');
  await expect(button(p, 'Confirm rejection')).toBeDisabled();
  await p
    .getByLabel('Reason *', {exact: true})
    .fill('  Scope evidence is incomplete.  ');
  await screenshot(p, info, 'reject');
  await button(p, 'Confirm rejection').click();
  await expect(record(p)).toContainText('REJECTED');
  await expect(record(p)).toContainText('Scope evidence is incomplete.');
  await screenshot(p, info, 'rejected');
  await p.reload();
  await expect(record(p)).toContainText('REJECTED');
});

test('AD-B03 explicit MEMBER grant permits a real decision; no implicit admin, self or stale eligibility', async ({
  page: p,
}, info) => {
  await login(p, 'member');
  await open(p, offset(info) + 2);
  await acknowledge(p);
  await button(p, 'Approve').click();
  await expect(record(p)).toContainText('Approval Detail Member');
  await expect(record(p)).toContainText('APPROVED');
  await p.getByRole('link', {name: 'Back to approvals', exact: true}).click();
  // Admin self-decision is denied independently of having the normal approver grant.
  await p.goto('/login');
  await login(p);
  for (const index of [4, 5, 6, 7, 8, 9, 12, 13, 14]) {
    const data = await open(p, offset(info) + index);
    expect(data.eligibility.canApprove).toBe(false);
    expect(data.eligibility.canReject).toBe(false);
    expect(data.eligibility.blockers.length).toBeGreaterThan(0);
    await expect(button(p, 'Approve')).toBeDisabled();
    await expect(button(p, 'Reject')).toBeDisabled();
    if ([5, 6, 7, 8].includes(index))
      await screenshot(p, info, `blocked-${index}`);
  }
  const closed = await open(p, offset(info) + 10);
  expect(closed.request.status).toBe('APPROVED');
  await expect(button(p, 'Approve')).toBeDisabled();
});

test('AD-B04 lost successful response retries identical operation/body/key and returns one decision', async ({
  page: p,
}, info) => {
  const n = offset(info) + 11;
  await login(p);
  await open(p, n);
  await acknowledge(p);
  let first = true;
  const sent = [];
  await p.route(`**${path(n)}/approve`, async route => {
    sent.push({
      body: route.request().postDataJSON(),
      key: route.request().headers()['idempotency-key'],
    });
    if (!first) return route.continue();
    first = false;
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    await route.abort('failed');
  });
  await button(p, 'Approve').click();
  await expect(
    p.getByRole('region', {name: 'Unconfirmed decision', exact: true}),
  ).toBeVisible();
  await expect(button(p, 'Approve')).toBeDisabled();
  const replay = p.waitForResponse(
    r => new URL(r.url()).pathname === `${path(n)}/approve`,
  );
  await button(p, 'Retry same decision').click();
  expect((await replay).status()).toBe(200);
  await expect(record(p)).toContainText('APPROVED');
  expect(sent).toHaveLength(2);
  expect(sent[1]).toEqual(sent[0]);
  expect(sent[0].key).toMatch(/^[0-9a-f-]{36}$/);
});

test('AD-B05 malformed POST acknowledgement reconciles by real GET without replacement POST', async ({
  page: p,
}, info) => {
  const n = offset(info) + 15;
  await login(p);
  await open(p, n);
  await acknowledge(p);
  let posts = 0;
  await p.route(`**${path(n)}/approve`, async route => {
    posts++;
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    await route.fulfill({status: 200, json: {data: null}});
  });
  await button(p, 'Approve').click();
  await expect(
    p.getByRole('region', {name: 'Unconfirmed decision', exact: true}),
  ).toBeVisible();
  await button(p, 'Check recorded result').click();
  await expect(record(p)).toContainText('APPROVED');
  await expect(
    p.getByRole('region', {name: 'Unconfirmed decision', exact: true}),
  ).toHaveCount(0);
  expect(posts).toBe(1);
});

test('AD-B06 refused retry after an unknown result preserves warning and suppresses writes after denial', async ({
  page: p,
}, info) => {
  const n = offset(info) + 16;
  await login(p);
  await open(p, n);
  await acknowledge(p);
  let posts = 0;
  await p.route(`**${path(n)}/approve`, async route => {
    posts++;
    if (posts > 1)
      return route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}});
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    return route.abort('failed');
  });
  await button(p, 'Approve').click();
  await button(p, 'Retry same decision').click();
  await expect(p.getByRole('alert')).toContainText(
    'Approval access or decision permission is unavailable',
  );
  await expect(p.getByRole('status')).toContainText(
    'original decision outcome remains unknown',
  );
  await expect(button(p, 'Retry same decision')).toHaveCount(0);
  await expect(
    p.getByRole('heading', {name: 'Requested action', exact: true}),
  ).toHaveCount(0);
  await button(p, 'Check recorded result').click();
  await expect(record(p)).toContainText('APPROVED');
  expect(posts).toBe(2);
});

test('AD-B07 malformed/error reads clear acknowledgement and data; fresh retry recovers', async ({
  page: p,
}, info) => {
  const n = offset(info) + 19;
  await login(p);
  const data = await open(p, n);
  await acknowledge(p);
  for (const value of [
    null,
    {...data, request: {...data.request, organizationId: foreign}},
    {...data, snapshot: {...data.snapshot, secret: 'PRIVATE'}},
    {...data, eligibility: {...data.eligibility, blockers: ['NO_GRANT']}},
  ]) {
    await p.route(`**${path(n)}`, route =>
      route.fulfill({json: {data: value}}),
    );
    await button(p, 'Refresh request').click();
    await expect(p.getByRole('alert')).toContainText(
      'Invalid approval response',
    );
    await expect(
      p.getByRole('heading', {name: 'Requested action', exact: true}),
    ).toHaveCount(0);
    await p.unroute(`**${path(n)}`);
    await button(p, 'Refresh request').click();
    await expect(button(p, 'Verify target & scope')).toBeEnabled();
    await expect(button(p, 'Approve')).toBeDisabled();
  }
  await p.route(`**${path(n)}`, route =>
    route.fulfill({status: 503, json: {error: 'Unavailable'}}),
  );
  await button(p, 'Refresh request').click();
  await expect(p.getByRole('alert')).toContainText('Unable to load approval');
  await p.unroute(`**${path(n)}`);
  await button(p, 'Refresh request').click();
  await expect(button(p, 'Verify target & scope')).toBeEnabled();
});

test('AD-B08 unsaved rejection warns before leaving; scope acknowledgement resets on refresh and menu returns focus', async ({
  page: p,
}, info) => {
  await login(p);
  await open(p, offset(info) + 20);
  await acknowledge(p);
  await button(p, 'Refresh request').click();
  await expect(button(p, 'Approve')).toBeDisabled();
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    await expect(p.getByRole('dialog')).toBeVisible();
    await p.keyboard.press('Escape');
    await expect(button(p, 'Menu')).toBeFocused();
  }
  await button(p, 'Reject').click();
  await p.getByLabel('Reason *', {exact: true}).fill('Unsaved review reason');
  let destination;
  if (info.project.name === 'desktop')
    destination = p
      .locator('aside')
      .getByRole('link', {name: 'Overview', exact: true});
  else {
    await button(p, 'Menu').click();
    destination = p
      .getByRole('dialog')
      .getByRole('link', {name: 'Overview', exact: true});
  }
  p.once('dialog', d => d.dismiss());
  await destination.click();
  await expect(p.getByLabel('Reason *', {exact: true})).toHaveValue(
    'Unsaved review reason',
  );
  if (info.project.name !== 'desktop') await p.keyboard.press('Escape');
  await button(p, 'Cancel').click();
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
});

test('AD-B09 server enforces snapshot/acknowledgement/CSRF and foreign request boundary', async ({
  page: p,
}, info) => {
  const n = offset(info) + 21;
  await login(p);
  const data = await open(p, n);
  const token = await csrf(p);
  const headers = {
    [token.headerName]: token.token,
    'Idempotency-Key': randomUUID(),
  };
  let response = await p.request.post(`${path(n)}/approve`, {
    headers,
    data: body(data, {snapshotSha256: '0'.repeat(64)}),
  });
  expect(response.status()).toBe(409);
  response = await p.request.post(`${path(n)}/approve`, {
    headers: {...headers, 'Idempotency-Key': randomUUID()},
    data: body(data, {acknowledgement: false}),
  });
  expect(response.status()).toBe(400);
  response = await p.request.post(`${path(n)}/approve`, {
    headers: {'Idempotency-Key': randomUUID()},
    data: body(data),
  });
  expect(response.status()).toBe(403);
  expect(
    (
      await p.request.get(
        `/api/v1/organizations/${org}/approvals/${ident('3050', 9999)}`,
      )
    ).status(),
  ).toBe(404);
  expect(
    (
      await p.request.get(
        `/api/v1/organizations/${foreign}/approvals/${ident('3050', 9999)}`,
      )
    ).status(),
  ).toBe(403);
  expect(
    (await (await p.request.get(path(n))).json()).data.request.status,
  ).toBe('PENDING');
});

test('AD-B10 pending mutation cannot silently cross reused route identity and duplicate click stays single', async ({
  page: p,
}, info) => {
  const n = offset(info) + 18;
  await login(p);
  const data = await open(p, n);
  await acknowledge(p);
  let release;
  const gate = new Promise(resolve => {
    release = resolve;
  });
  let seen;
  const committed = new Promise(resolve => {
    seen = resolve;
  });
  let done;
  const delivered = new Promise(resolve => {
    done = resolve;
  });
  let posts = 0;
  await p.route(`**${path(n)}/approve`, async route => {
    posts++;
    try {
      const response = await route.fetch();
      expect(response.status()).toBe(201);
      seen();
      await gate;
      await route.fulfill({response});
    } finally {
      done();
    }
  });
  try {
    await button(p, 'Approve').click();
    await committed;
    await expect(button(p, 'Approve')).toBeDisabled();
    await button(p, 'Approve').evaluate(el => el.click());
    const prompt = p.waitForEvent('dialog');
    await p.evaluate(
      target => {
        history.pushState({}, '', target);
        window.dispatchEvent(new PopStateEvent('popstate'));
      },
      url(offset(info) + 23),
    );
    await (await prompt).dismiss();
    await expect(p).toHaveURL(new RegExp(`${url(n)}$`));
    await expect(
      p.getByRole('heading', {name: data.request.title, exact: true}),
    ).toBeVisible();
    release();
    await delivered;
    await expect(record(p)).toContainText('APPROVED');
    expect(posts).toBe(1);
  } finally {
    release();
  }
});

test('AD-B11 denied workspace reconciliation erases private submission and keeps the unknown-outcome warning', async ({
  page: p,
}, info) => {
  const n = offset(info) + 24;
  await login(p);
  await open(p, n);
  await acknowledge(p);
  let posts = 0;
  await p.route(`**${path(n)}/approve`, async route => {
    posts++;
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    await route.abort('failed');
  });
  await button(p, 'Approve').click();
  await expect(button(p, 'Retry same decision')).toBeVisible();
  const workspace = `**/api/v1/auth/workspaces/${org}`;
  await p.route(workspace, route =>
    route.fulfill({status: 403, json: {error: {code: 'FORBIDDEN'}}}),
  );
  await button(p, 'Check recorded result').click();
  await expect(p.getByRole('alert')).toContainText(
    'Approval access or decision permission is unavailable',
  );
  await expect(p.getByRole('status')).toContainText(
    'original decision outcome remains unknown',
  );
  await expect(button(p, 'Retry same decision')).toHaveCount(0);
  await expect(
    p.getByRole('heading', {name: 'Requested action', exact: true}),
  ).toHaveCount(0);
  await p.unroute(workspace);
  await button(p, 'Check recorded result').click();
  await expect(record(p)).toContainText('APPROVED');
  expect(posts).toBe(1);
});
