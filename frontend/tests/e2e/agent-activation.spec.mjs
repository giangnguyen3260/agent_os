import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';

const org = '77777777-7777-4777-8777-777777777777';
const admin = 'activation-admin@example.test';
const member = 'activation-member@example.test';
const active = '73000000-0000-4000-8000-000000000001';
const root = id => `/api/v1/organizations/${org}/agents/${id}`;
const reviewApi = id => `${root(id)}/activation-review`;
const studio = id => `/workspace/${org}/agents/${id}/studio`;
const reviewUrl = id => `/workspace/${org}/agents/${id}/activation-review`;
// Frozen contract labels: changes require coordination with the screen owner.
const button = (p, name) => p.getByRole('button', {name, exact: true});
const codes = [
  'DRAFT_STATUS',
  'CONTRACT',
  'MODEL_SELECTION',
  'STRUCTURED_LIMITS',
  'TEAM_ACTIVE',
  'TEAM_ENVELOPE',
  'MODEL_AUTHORIZATION',
  'TOOL_AUTHORIZATION',
  'EXECUTION_RUNTIME',
];
const base = {
  responsibilities: 'Review activation evidence',
  expectedInput: 'A proposed change',
  outputFormat: 'Verified findings',
  acceptanceCriteria: 'Reproducible evidence',
  model: null,
  skills: null,
  tools: null,
  limits: null,
  concurrency: null,
  maxTokensPerRun: null,
  monthlyBudget: null,
};
function sql(input) {
  return execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'postgres',
      'sh',
      '-c',
      'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      input,
      encoding: 'utf8',
      timeout: 15000,
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  ).trim();
}
async function login(p, email = admin) {
  expect(
    process.env.E2E_PASSWORD,
    'Dedicated fixture password is required',
  ).toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(email);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function data(p, path) {
  const r = await p.request.get(path);
  expect(r.status()).toBe(200);
  return (await r.json()).data;
}
async function headers(p) {
  const token = await data(p, '/api/v1/auth/csrf');
  return {[token.headerName]: token.token};
}
async function configure(p, id, changes = {}) {
  const current = await data(p, `${root(id)}/configuration`);
  const r = await p.request.put(`${root(id)}/configuration`, {
    headers: {...(await headers(p)), 'Idempotency-Key': randomUUID()},
    data: {...base, ...changes, expectedRevision: current.revision},
  });
  expect(r.status()).toBe(200);
  return data(p, `${root(id)}/configuration`);
}
function observePosts(p) {
  const posts = [];
  p.on('request', r => {
    if (
      r.method() === 'POST' &&
      /\/activation$/.test(new URL(r.url()).pathname)
    )
      posts.push(r);
  });
  return posts;
}
async function open(p, id) {
  await p.goto(reviewUrl(id));
  await expect(p.getByTestId('activation-review')).toBeVisible();
  await expect(
    p.getByTestId('activation-check-EXECUTION_RUNTIME'),
  ).toContainText('UNAVAILABLE');
}
async function matches(p, review) {
  expect(review.activationAllowed).toBe(false);
  expect(review.checks.map(c => c.code)).toEqual(codes);
  expect(Number.isNaN(Date.parse(review.checkedAt))).toBe(false);
  await expect(
    p.getByRole('heading', {
      name: `${review.name} / Activation review`,
      exact: true,
    }),
  ).toBeVisible();
  await expect(
    p.getByRole('heading', {name: 'Activation blocked', exact: true}),
  ).toBeVisible();
  for (const check of review.checks) {
    const row = p.getByTestId(`activation-check-${check.code}`);
    await expect(row).toContainText(check.status);
    await expect(row).toContainText(check.message);
  }
  await expect(button(p, 'Activate')).toBeDisabled();
  await expect(p.getByText('Validation passed', {exact: true})).toHaveCount(0);
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
}
function snapshot() {
  // Native PostgreSQL rows, including append-only history and receipts, must be identical.
  return sql(`SELECT jsonb_build_object(
    'agents',(SELECT jsonb_agg(to_jsonb(a) ORDER BY id) FROM agents a WHERE organization_id='${org}'),
    'config',(SELECT jsonb_agg(to_jsonb(c) ORDER BY agent_id) FROM agent_draft_config c JOIN agents a ON a.id=c.agent_id WHERE a.organization_id='${org}'),
    'versions',(SELECT jsonb_agg(to_jsonb(v) ORDER BY agent_id,revision) FROM agent_configuration_versions v WHERE organization_id='${org}'),
    'receipts',(SELECT jsonb_agg(to_jsonb(r) ORDER BY agent_id,idempotency_key) FROM agent_configuration_receipts r WHERE organization_id='${org}'),
    'createReceipts',(SELECT jsonb_agg(to_jsonb(r) ORDER BY idempotency_key) FROM agent_create_receipts r WHERE organization_id='${org}'),
    'tasks',(SELECT jsonb_agg(to_jsonb(t) ORDER BY id) FROM tasks t WHERE organization_id='${org}'),
    'audit',(SELECT jsonb_agg(to_jsonb(e) ORDER BY id) FROM audit_events e WHERE organization_id='${org}')
  )::text;`);
}

test.describe('Agent activation independent QC', () => {
  test.describe.configure({mode: 'serial'});
  let id;
  test.beforeEach(async ({page: p}, info) => {
    id = `72000000-0000-4000-8000-00000000000${{desktop: 1, tablet: 2, mobile: 3}[info.project.name]}`;
    await login(p);
    // Force an actual revision even on repeated runs; never seed fake activation success.
    await configure(p, id, {
      responsibilities: `Review evidence ${randomUUID()}`,
    });
  });
  test('Studio entry, real missing checks and Keep draft never POST', async ({
    page: p,
  }) => {
    const posts = observePosts(p);
    const review = await data(p, reviewApi(id));
    expect(review.checks.find(c => c.code === 'MODEL_SELECTION').status).toBe(
      'BLOCKED',
    );
    expect(review.checks.find(c => c.code === 'STRUCTURED_LIMITS').status).toBe(
      'BLOCKED',
    );
    await p.goto(studio(id));
    await button(p, 'Validate & Activate').click();
    await expect(p).toHaveURL(reviewUrl(id));
    await matches(p, review);
    if (p.viewportSize().width < 1000) {
      await button(p, 'Menu').click();
      await expect(p.getByRole('dialog')).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(p.getByRole('dialog')).not.toBeVisible();
      await expect(button(p, 'Menu')).toBeFocused();
    }
    await button(p, 'Keep draft').click();
    await expect(p).toHaveURL(studio(id));
    await expect(p.getByTestId('studio-view')).toBeVisible();
    expect(posts).toHaveLength(0);
  });
  test('Check again reads current API configuration and over-budget revision', async ({
    page: p,
  }) => {
    const posts = observePosts(p);
    await open(p, id);
    const configured = {
      model: 'Requested model only',
      concurrency: 4,
      maxTokensPerRun: 1200,
      monthlyBudget: 100,
    };
    const current = await configure(p, id, configured);
    await button(p, 'Check again').click();
    const review = await data(p, reviewApi(id));
    expect(review.revision).toBe(current.revision);
    for (const code of codes.slice(0, 6))
      expect(review.checks.find(c => c.code === code).status).toBe('PASS');
    for (const code of codes.slice(6))
      expect(review.checks.find(c => c.code === code).status).toBe(
        'UNAVAILABLE',
      );
    await matches(p, review);
    const over = await configure(p, id, {...configured, monthlyBudget: 100.01});
    await button(p, 'Check again').click();
    const blocked = await data(p, reviewApi(id));
    expect(blocked.revision).toBe(over.revision);
    expect(blocked.revision).toBeGreaterThan(review.revision);
    expect(blocked.checks.find(c => c.code === 'TEAM_ENVELOPE').status).toBe(
      'BLOCKED',
    );
    await matches(p, blocked);
    await p.getByRole('link', {name: 'Open Agent Studio', exact: true}).click();
    await expect(p).toHaveURL(studio(id));
    expect(posts).toHaveLength(0);
  });
  test('transport failure and null body clear stale checks and retry only GETs', async ({
    page: p,
  }) => {
    const posts = observePosts(p);
    await open(p, id);
    const writes = [];
    p.on('request', request => {
      if (!['GET', 'HEAD', 'OPTIONS'].includes(request.method()))
        writes.push(request);
    });
    let attempts = 0;
    await p.route(`**${reviewApi(id)}`, async route => {
      expect(route.request().method()).toBe('GET');
      attempts++;
      if (attempts === 1) return route.abort('failed');
      if (attempts === 3)
        return route.fulfill({
          status: 200,
          contentType: 'application/json',
          body: 'null',
        });
      return route.continue();
    });
    await button(p, 'Check again').click();
    await expect(p.getByRole('alert')).toBeVisible();
    for (const code of codes)
      await expect(p.getByTestId(`activation-check-${code}`)).toHaveCount(0);
    await button(p, 'Thử lại').click();
    await matches(p, await data(p, reviewApi(id)));
    expect(attempts).toBe(2);
    // A malformed successful response must clear the now-visible real review too.
    await button(p, 'Check again').click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(p.getByTestId('activation-review')).toHaveAttribute(
      'aria-busy',
      'false',
    );
    for (const code of codes)
      await expect(p.getByTestId(`activation-check-${code}`)).toHaveCount(0);
    await expect(button(p, 'Activate')).toBeDisabled();
    await button(p, 'Thử lại').click();
    await matches(p, await data(p, reviewApi(id)));
    await expect(p.getByRole('alert')).toHaveCount(0);
    expect(attempts).toBe(4);
    expect(writes).toHaveLength(0);
    expect(posts).toHaveLength(0);
  });
  test('blocked repeated/concurrent, stale and CSRF POSTs have no DB mutation', async ({
    page: p,
  }) => {
    const review = await data(p, reviewApi(id));
    const csrf = await headers(p);
    const before = snapshot();
    const post = (revision, h = csrf) =>
      p.request.post(`${root(id)}/activation`, {
        headers: h,
        data: {expectedRevision: revision},
      });
    expect((await post(review.revision, {})).status()).toBe(403);
    expect((await post(review.revision - 1)).status()).toBe(409);
    const stale = await post(review.revision - 1);
    expect((await stale.json()).error.code).toBe('CONFIGURATION_CONFLICT');
    for (const response of [
      await post(review.revision),
      ...(await Promise.all([post(review.revision), post(review.revision)])),
    ]) {
      expect(response.status()).toBe(409);
      const error = (await response.json()).error;
      expect(error.code).toBe('ACTIVATION_BLOCKED');
      expect(error.details.review.revision).toBe(review.revision);
      expect(error.details.review.activationAllowed).toBe(false);
    }
    expect(snapshot()).toBe(before);
    expect(await data(p, `${root(id)}/configuration`)).toMatchObject({
      revision: review.revision,
      agent: {status: 'DRAFT'},
    });
  });
  test('foreign target 404, unauthorized organization 403 and ACTIVE read-only', async ({
    page: p,
  }) => {
    const csrf = await headers(p);
    for (const [path, status] of [
      [root('c4444444-4444-4444-8444-444444444444'), 404],
      [root(id).replace(org, 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'), 403],
    ]) {
      expect((await p.request.get(`${path}/activation-review`)).status()).toBe(
        status,
      );
      expect(
        (
          await p.request.post(`${path}/activation`, {
            headers: csrf,
            data: {expectedRevision: 0},
          })
        ).status(),
      ).toBe(status);
    }
    await p.goto(studio(active));
    await expect(p.getByTestId('studio-view')).toBeVisible();
    await expect(button(p, 'Validate & Activate')).toBeDisabled();
    await open(p, active);
    const review = await data(p, reviewApi(active));
    expect(review.checks.find(c => c.code === 'DRAFT_STATUS').status).toBe(
      'BLOCKED',
    );
    await matches(p, review);
    await expect(button(p, 'Keep draft')).toHaveCount(0);
    await button(p, 'Back to studio').click();
    await expect(p).toHaveURL(studio(active));
  });
  test('member denial and revoked ADMIN clear private checks', async ({
    page: p,
  }) => {
    const posts = observePosts(p);
    await login(p, member);
    await p.goto(reviewUrl(id));
    await expect(p.getByRole('alert')).toBeVisible();
    expect((await p.request.get(reviewApi(id))).status()).toBe(403);
    expect(
      (
        await p.request.post(`${root(id)}/activation`, {
          headers: await headers(p),
          data: {expectedRevision: 0},
        })
      ).status(),
    ).toBe(403);
    for (const code of codes)
      await expect(p.getByTestId(`activation-check-${code}`)).toHaveCount(0);
    await login(p);
    await open(p, id);
    const role = sql(
      `SELECT role FROM organization_memberships WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${admin}');`,
    );
    expect(role).toBe('ADMIN');
    try {
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${admin}');`,
      );
      await button(p, 'Check again').click();
      await expect(p.getByRole('alert')).toBeVisible();
      for (const code of codes)
        await expect(p.getByTestId(`activation-check-${code}`)).toHaveCount(0);
      await expect(button(p, 'Activate')).toHaveCount(0);
    } finally {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='${admin}');`,
      );
    }
    expect(posts).toHaveLength(0);
  });
});
