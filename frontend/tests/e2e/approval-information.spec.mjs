import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {mkdirSync} from 'node:fs';
const org = '40404040-4040-4040-8040-404040404040';
const ident = n => `40500000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const offset = info =>
  ({desktop: 0, tablet: 100, mobile: 200})[info.project.name];
const api = n => `/api/v1/organizations/${org}/approvals/${ident(n)}`;
const detail = n => `/workspace/${org}/approvals/${ident(n)}`;
const url = n => `${detail(n)}/information`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const thread = p => p.getByRole('region', {name: 'Conversation', exact: true});
async function login(p, role = 'admin') {
  expect(process.env.E2E_PASSWORD).toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`approval-information-${role}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, n) {
  const [r] = await Promise.all([
    p.waitForResponse(
      r =>
        new URL(r.url()).pathname === `${api(n)}/information` &&
        r.request().method() === 'GET',
    ),
    p.goto(url(n)),
  ]);
  expect(r.status()).toBe(200);
  await expect(p.getByTestId('approval-information')).toHaveAttribute(
    'aria-busy',
    'false',
  );
  return (await r.json()).data;
}
async function screenshot(p, info, state) {
  mkdirSync('qa-visuals', {recursive: true});
  await p.screenshot({
    path: `qa-visuals/approval-information-${info.project.name}-${state}.png`,
    fullPage: true,
  });
}
async function send(p, n, operation) {
  const r = p.waitForResponse(
    r => new URL(r.url()).pathname === `${api(n)}/${operation}`,
  );
  await button(
    p,
    operation === 'reply' ? 'Submit information' : 'Send information request',
  ).click();
  const response = await r;
  expect(response.status()).toBe(201);
  return (await response.json()).data;
}
async function post(p, n, operation, body, key = randomUUID()) {
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  return p.request.post(`${api(n)}/${operation}`, {
    data: body,
    headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': key},
  });
}
async function requester(browser, viewport) {
  const p = await browser.newPage({
    baseURL: process.env.E2E_BASE_URL || 'http://127.0.0.1:8080',
    viewport,
  });
  expect(await p.evaluate(() => innerWidth)).toBe(viewport.width);
  await login(p, 'requester');
  return p;
}
async function acknowledge(p) {
  await button(p, 'Verify target & scope').click();
  await button(p, 'I confirm this scope').click();
  await expect(button(p, 'Approve')).toBeEnabled();
}

test('AI-B01 persisted ask, requester reply/evidence and fresh human decision', async ({
  page: p,
  browser,
}, info) => {
  const n = offset(info) + 15;
  await login(p);
  await p.goto(detail(n));
  await button(p, 'Request information').click();
  await expect(p).toHaveURL(new RegExp(`${url(n)}$`));
  await expect(p.getByLabel('Question *', {exact: true})).toHaveValue('');
  await expect(button(p, 'Send information request')).toBeDisabled();
  await screenshot(p, info, 'ask-blank');
  await p
    .getByLabel('Question *', {exact: true})
    .fill('  Confirm internal scope and target workspace.  ');
  await screenshot(p, info, 'ask');
  const result = await send(p, n, 'request-information');
  expect(result.revision).toBe(1);
  await expect(thread(p)).toContainText(
    'Confirm internal scope and target workspace.',
  );
  await expect(p.getByLabel('Answer *', {exact: true})).toHaveCount(0);
  await screenshot(p, info, 'waiting');
  const r = await requester(browser, p.viewportSize());
  try {
    const data = await open(r, n);
    expect(data.canReply).toBe(true);
    await expect(r.getByLabel('Answer *', {exact: true})).toHaveValue('');
    await expect(button(r, 'Submit information')).toBeDisabled();
    await r
      .getByLabel('Answer *', {exact: true})
      .fill(' Internal review only; no production deployment. ');
    await r
      .getByLabel('Evidence', {exact: true})
      .fill('  Scope validation reference v3  ');
    expect(
      await r.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
    await screenshot(r, info, 'reply');
    expect((await send(r, n, 'reply')).revision).toBe(2);
    await expect(thread(r)).toContainText('Scope validation reference v3');
    await r.reload();
    await expect(thread(r)).toContainText(
      'Internal review only; no production deployment.',
    );
  } finally {
    await r.close();
  }
  await p.reload();
  await expect(thread(p)).toContainText(
    'Internal review only; no production deployment.',
  );
  await screenshot(p, info, 'answered');
  await p.getByRole('link', {name: 'Back to approval', exact: true}).click();
  await expect(button(p, 'Approve')).toBeDisabled();
  await expect(
    p.getByRole('heading', {
      name: 'Supplemental information · revision 2',
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    p.getByRole('region', {
      name: 'Supplemental information · revision 2',
      exact: true,
    }),
  ).toContainText('Internal review only; no production deployment.');
  await acknowledge(p);
  const accepted = p.waitForResponse(
    r => new URL(r.url()).pathname === `${api(n)}/approve`,
  );
  await button(p, 'Approve').click();
  const response = await accepted;
  expect(response.status()).toBe(201);
  expect(response.request().postDataJSON().informationRevision).toBe(2);
  await expect(
    p.getByRole('heading', {name: 'Decision record', exact: true}),
  ).toBeVisible();
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
});

test('AI-B02 explicit MEMBER grant asks; approver cannot impersonate requester', async ({
  page: p,
  browser,
}, info) => {
  const n = offset(info) + 16;
  await login(p, 'member');
  await open(p, n);
  await p
    .getByLabel('Question *', {exact: true})
    .fill('Member scoped question');
  const question = await send(p, n, 'request-information');
  const denied = await post(p, n, 'reply', {
    revision: 1,
    questionId: question.entryId,
    answer: 'Impersonation',
    evidence: null,
  });
  expect(denied.status()).toBe(403);
  await expect(p.getByLabel('Answer *', {exact: true})).toHaveCount(0);
  const r = await requester(browser, p.viewportSize());
  try {
    await open(r, n);
    await r
      .getByLabel('Answer *', {exact: true})
      .fill('\u00a0Requester answer\ufeff');
    await r.getByLabel('Evidence', {exact: true}).fill('\u00a0');
    await send(r, n, 'reply');
    await expect(thread(r)).toContainText('Requester answer');
    const stored = (await (await r.request.get(`${api(n)}/information`)).json())
      .data;
    expect(stored.entries[1].text).toBe('Requester answer');
    expect(stored.entries[1].evidence).toBeNull();
  } finally {
    await r.close();
  }
});

test('AI-B03 committed lost response retries the identical body and key once', async ({
  page: p,
}, info) => {
  const n = offset(info) + 17;
  await login(p);
  await open(p, n);
  let first = true;
  const sent = [];
  await p.route(`**${api(n)}/request-information`, async route => {
    sent.push({
      body: route.request().postDataJSON(),
      key: route.request().headers()['idempotency-key'],
    });
    if (!first) return route.continue();
    first = false;
    const r = await route.fetch();
    expect(r.status()).toBe(201);
    await route.abort('failed');
  });
  await p
    .getByLabel('Question *', {exact: true})
    .fill('Recover exact original question');
  await button(p, 'Send information request').click();
  await expect(
    p.getByRole('region', {name: 'Unconfirmed information', exact: true}),
  ).toBeVisible();
  const replay = p.waitForResponse(
    r => new URL(r.url()).pathname === `${api(n)}/request-information`,
  );
  await button(p, 'Retry same submission').click();
  expect((await replay).status()).toBe(200);
  await expect(thread(p)).toContainText('Recover exact original question');
  expect(sent).toHaveLength(2);
  expect(sent[1]).toEqual(sent[0]);
  const stored = (await (await p.request.get(`${api(n)}/information`)).json())
    .data;
  expect(stored.entries).toHaveLength(1);
});

test('AI-B04 revision alone cannot resolve unknown; exact receipt recovers without replacement POST', async ({
  page: p,
}, info) => {
  const n = offset(info) + 18;
  await login(p);
  await open(p, n);
  let posts = 0;
  await p.route(`**${api(n)}/request-information`, async route => {
    posts++;
    const r = await route.fetch();
    expect(r.status()).toBe(201);
    await route.abort('failed');
  });
  await p
    .getByLabel('Question *', {exact: true})
    .fill('Receipt-bound question');
  await button(p, 'Send information request').click();
  const warning = p.getByRole('region', {
    name: 'Unconfirmed information',
    exact: true,
  });
  await expect(warning).toBeVisible();
  const receiptPattern = `**${api(n)}/information/receipts/*`;
  await p.route(receiptPattern, route => route.fulfill({json: {data: null}}));
  await button(p, 'Check conversation').click();
  await expect(thread(p)).toContainText('Receipt-bound question');
  await expect(warning).toBeVisible();
  await expect(p.getByLabel('Question *', {exact: true})).toHaveCount(0);
  await screenshot(p, info, 'unknown');
  await p.unroute(receiptPattern);
  await button(p, 'Check conversation').click();
  await expect(warning).toHaveCount(0);
  await expect(
    p.getByText('The original submission is recorded.', {exact: false}),
  ).toBeVisible();
  expect(posts).toBe(1);
});

test('AI-B05 denied reconciliation clears input and keeps original unknown outcome', async ({
  page: p,
}, info) => {
  const n = offset(info) + 19;
  await login(p);
  await open(p, n);
  let posts = 0;
  await p.route(`**${api(n)}/request-information`, async route => {
    posts++;
    const response = await route.fetch();
    expect(response.status()).toBe(201);
    await route.fulfill({json: {data: null}});
  });
  await p
    .getByLabel('Question *', {exact: true})
    .fill('Private pending question');
  await button(p, 'Send information request').click();
  const warning = p.getByRole('region', {
    name: 'Unconfirmed information',
    exact: true,
  });
  await expect(warning).toBeVisible();
  const workspace = `**/api/v1/auth/workspaces/${org}`;
  await p.route(workspace, route =>
    route.fulfill({status: 403, json: {data: null}}),
  );
  await button(p, 'Check conversation').click();
  await expect(p.getByRole('alert')).toContainText(
    'Access to this conversation is unavailable.',
  );
  await expect(warning).toContainText('original outcome remains unknown');
  await expect(button(p, 'Retry same submission')).toHaveCount(0);
  await expect(thread(p)).toHaveCount(0);
  await expect(p.getByLabel('Question *', {exact: true})).toHaveCount(0);
  await p.unroute(workspace);
  await button(p, 'Check conversation').click();
  await expect(warning).toHaveCount(0);
  await expect(thread(p)).toContainText('Private pending question');
  expect(posts).toBe(1);
});

test('AI-B06 malformed/error conversation fails closed and fresh refresh recovers', async ({
  page: p,
}, info) => {
  const n = offset(info) + 20;
  await login(p);
  await open(p, n);
  const good = (await (await p.request.get(`${api(n)}/information`)).json())
    .data;
  for (const bad of [
    {...good, approvalId: ident(9999)},
    {...good, revision: '0'},
    {...good, extra: 'unexpected'},
    {...good, canReply: true},
  ]) {
    await p.route(`**${api(n)}/information`, route =>
      route.fulfill({json: {data: bad}}),
    );
    await p.reload();
    await expect(p.getByRole('alert')).toContainText('Unable to verify');
    await expect(p.getByLabel('Question *', {exact: true})).toHaveCount(0);
    await p.unroute(`**${api(n)}/information`);
    await button(p, 'Refresh conversation').click();
    await expect(p.getByLabel('Question *', {exact: true})).toBeVisible();
  }
  await p.route(`**${api(n)}/information`, route =>
    route.fulfill({status: 503, json: {data: null}}),
  );
  await p.reload();
  await expect(p.getByRole('alert')).toContainText('Unable to load');
  await expect(p.getByLabel('Question *', {exact: true})).toHaveCount(0);
});

test('AI-B07 dirty navigation, reused route and compact menu guard input', async ({
  page: p,
}, info) => {
  const n = offset(info) + 21;
  await login(p);
  await open(p, n);
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    await expect(
      p.getByRole('dialog', {name: 'Workspace navigation'}),
    ).toBeVisible();
    await p.keyboard.press('Escape');
    await expect(button(p, 'Menu')).toBeFocused();
  }
  await p.getByLabel('Question *', {exact: true}).fill('Unsaved question');
  let prompt = p.waitForEvent('dialog');
  await p.getByRole('link', {name: 'Back to approval', exact: true}).click();
  await (await prompt).dismiss();
  await expect(p).toHaveURL(new RegExp(`${url(n)}$`));
  await expect(p.getByLabel('Question *', {exact: true})).toHaveValue(
    'Unsaved question',
  );
  prompt = p.waitForEvent('dialog');
  await p.evaluate(
    target => {
      history.pushState({}, '', target);
      dispatchEvent(new PopStateEvent('popstate'));
    },
    url(n + 1),
  );
  await (await prompt).dismiss();
  await expect(p).toHaveURL(new RegExp(`${url(n)}$`));
  await expect(p.getByLabel('Question *', {exact: true})).toHaveValue(
    'Unsaved question',
  );
  await button(p, 'Cancel').click();
  await expect(p).toHaveURL(new RegExp(`${detail(n)}$`));
});

test('AI-B08 old acknowledgement cannot approve after a completed information round', async ({
  page: p,
  browser,
}, info) => {
  const n = offset(info) + 22;
  await login(p);
  await p.goto(detail(n));
  await expect(button(p, 'Verify target & scope')).toBeEnabled();
  const original = (await (await p.request.get(api(n))).json()).data;
  expect(original.informationRevision).toBe(0);
  await acknowledge(p);
  const asked = await post(p, n, 'request-information', {
    revision: 0,
    question: 'New evidence required',
  });
  expect(asked.status()).toBe(201);
  const question = (await asked.json()).data;
  const r = await requester(browser, p.viewportSize());
  try {
    const replied = await post(r, n, 'reply', {
      revision: 1,
      questionId: question.entryId,
      answer: 'New evidence supplied',
      evidence: null,
    });
    expect(replied.status()).toBe(201);
  } finally {
    await r.close();
  }
  const rejected = p.waitForResponse(
    r => new URL(r.url()).pathname === `${api(n)}/approve`,
  );
  await button(p, 'Approve').click();
  expect((await rejected).status()).toBe(409);
  await expect(p.getByRole('alert')).toContainText('Decision was not accepted');
  await button(p, 'Refresh request').click();
  await expect(button(p, 'Approve')).toBeDisabled();
  const fresh = (await (await p.request.get(api(n))).json()).data;
  expect(fresh.informationRevision).toBe(2);
  expect(fresh.decision).toBeNull();
  await expect(
    p.getByRole('heading', {
      name: 'Supplemental information · revision 2',
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    p.getByRole('link', {name: 'Open conversation', exact: true}),
  ).toHaveAttribute('href', url(n));
});
