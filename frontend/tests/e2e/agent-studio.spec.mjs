import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
const org = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee';
const active = 'd3000000-0000-4000-8000-000000000001';
const api = id => `/api/v1/organizations/${org}/agents/${id}/configuration`;
const url = id => `/workspace/${org}/agents/${id}/studio`;
const btn = (p, name) => p.getByRole('button', {name, exact: true});
const labels = {
  responsibilities: 'Responsibilities',
  expectedInput: 'Expected input',
  outputFormat: 'Output format',
  acceptanceCriteria: 'Acceptance criteria',
  model: 'Model',
  skills: 'Skills',
  tools: 'Tools / requested scope',
  limits: 'Limits (requested)',
  concurrency: 'Concurrency',
  maxTokensPerRun: 'Max tokens per run',
  monthlyBudget: 'Monthly budget (USD)',
};
const field = (p, k) => p.getByLabel(labels[k], {exact: true});
const base = {
  responsibilities: 'Review proposed changes',
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
async function login(p, email = 'studio-admin@example.test') {
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(email);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await btn(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function read(p, id) {
  const r = await p.request.get(api(id));
  expect(r.status()).toBe(200);
  return (await r.json()).data;
}
async function put(p, id, data, csrf = true, path = api(id)) {
  const headers = {'Idempotency-Key': randomUUID()};
  if (csrf) {
    const r = await p.request.get('/api/v1/auth/csrf');
    expect(r.status()).toBe(200);
    const t = (await r.json()).data;
    headers[t.headerName] = t.token;
  }
  return p.request.put(path, {headers, data});
}
async function open(p, id) {
  await p.goto(url(id));
  await expect(p.getByTestId('studio-view')).toBeVisible();
}
async function edit(p) {
  await btn(p, 'Edit').click();
  await expect(p.getByTestId('studio-edit')).toBeVisible();
}
async function review(p) {
  await btn(p, 'Validate & review diff').click();
  await expect(p.getByTestId('studio-diff')).toBeVisible();
}
function writes(p, id) {
  const out = [];
  p.on('request', r => {
    if (r.method() === 'PUT' && new URL(r.url()).pathname === api(id))
      out.push(r);
  });
  return out;
}
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
test.describe('Agent Studio independent QC', () => {
  test.describe.configure({mode: 'serial'});
  let id;
  test.beforeEach(async ({page: p}, info) => {
    id = `d2000000-0000-4000-8000-00000000000${{desktop: 1, tablet: 2, mobile: 3}[info.project.name]}`;
    await login(p);
    const current = await read(p, id);
    expect(
      (
        await put(p, id, {
          ...base,
          responsibilities: `Review ${randomUUID()}`,
          expectedRevision: current.revision,
        })
      ).status(),
    ).toBe(200);
  });
  test('real diff persists and reloads without identity changes', async ({
    page: p,
  }) => {
    const before = await read(p, id),
      requests = writes(p, id);
    await open(p, id);
    await expect(
      p.getByRole('heading', {
        name: `${before.agent.name} / Agent Studio`,
        exact: true,
      }),
    ).toBeVisible();
    await edit(p);
    await expect(
      p.getByRole('heading', {
        name: `${before.agent.name} / Edit configuration`,
        exact: true,
      }),
    ).toBeVisible();
    const change = {
      ...base,
      responsibilities: `Review evidence ${randomUUID()}`,
      model: 'Requested model',
      skills: 'Review\nVerification',
      tools: 'Read only',
      limits: 'No provider calls',
      concurrency: 3,
      maxTokensPerRun: 1200,
      monthlyBudget: 12.34,
    };
    for (const [k, v] of Object.entries(change))
      await field(p, k).fill(String(v ?? ''));
    await review(p);
    await expect(
      p.getByRole('heading', {
        name: `${before.agent.name} / Review configuration changes`,
        exact: true,
      }),
    ).toBeVisible();
    await expect(p.getByTestId('studio-diff')).toContainText(
      before.agent.responsibilities,
    );
    await expect(p.getByTestId('studio-diff')).toContainText(
      change.responsibilities,
    );
    expect(requests).toHaveLength(0);
    await btn(p, `Save version v${before.revision + 1}`).click();
    await expect(p.getByTestId('studio-saved')).toBeVisible();
    await expect(
      p.getByRole('heading', {
        name: `${before.agent.name} / Configuration saved`,
        exact: true,
      }),
    ).toBeVisible();
    expect(requests).toHaveLength(1);
    expect(requests[0].postDataJSON()).toEqual({
      ...change,
      expectedRevision: before.revision,
    });
    const after = await read(p, id);
    expect(after.revision).toBe(before.revision + 1);
    for (const k of [
      'id',
      'organizationId',
      'name',
      'role',
      'teamId',
      'description',
      'level',
      'autonomyLevel',
      'status',
    ])
      expect(after.agent[k]).toEqual(before.agent[k]);
    for (const k of Object.keys(base))
      expect(k in after ? after[k] : after.agent[k]).toEqual(change[k]);
    await btn(p, 'Back to studio').click();
    await expect(p.getByTestId('studio-view')).toBeVisible();
    await p.reload();
    await edit(p);
    for (const [k, v] of Object.entries(change))
      await expect(field(p, k)).toHaveValue(String(v ?? ''));
    expect(
      await p.evaluate(
        () => document.documentElement.scrollWidth <= innerWidth,
      ),
    ).toBe(true);
  });
  test('no-op, validation, Back and Cancel do not PUT', async ({page: p}) => {
    await open(p, id);
    const requests = writes(p, id);
    await edit(p);
    await btn(p, 'Validate & review diff').click();
    await expect(p.getByTestId('studio-edit')).toBeVisible();
    await expect(p.getByRole('alert')).toBeVisible();
    await field(p, 'responsibilities').fill('');
    await btn(p, 'Validate & review diff').click();
    await expect(field(p, 'responsibilities')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    await expect(field(p, 'responsibilities')).toBeFocused();
    await field(p, 'responsibilities').fill('Unsaved local review');
    await field(p, 'concurrency').fill('0');
    await btn(p, 'Validate & review diff').click();
    await expect(field(p, 'concurrency')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    await expect(field(p, 'concurrency')).toBeFocused();
    await field(p, 'concurrency').fill('2');
    await field(p, 'monthlyBudget').fill('12.340000001');
    await btn(p, 'Validate & review diff').click();
    await expect(p.getByTestId('studio-edit')).toBeVisible();
    await expect(p.getByTestId('studio-diff')).toHaveCount(0);
    await expect(field(p, 'monthlyBudget')).toHaveAttribute(
      'aria-invalid',
      'true',
    );
    await expect(field(p, 'monthlyBudget')).toBeFocused();
    expect(requests).toHaveLength(0);
    await field(p, 'monthlyBudget').fill('12.34');
    await review(p);
    await btn(p, 'Back').click();
    await expect(field(p, 'responsibilities')).toHaveValue(
      'Unsaved local review',
    );
    p.once('dialog', d => d.dismiss());
    await btn(p, 'Cancel').click();
    await expect(field(p, 'responsibilities')).toHaveValue(
      'Unsaved local review',
    );
    p.once('dialog', d => d.accept());
    await btn(p, 'Cancel').click();
    await expect(p.getByTestId('studio-edit')).toHaveCount(0);
    expect(requests).toHaveLength(0);
  });
  test('stale conflict retains edits until explicit confirmed reload', async ({
    page: p,
  }) => {
    const before = await read(p, id);
    await open(p, id);
    await edit(p);
    await field(p, 'responsibilities').fill('Local conflict edits');
    await review(p);
    expect(
      (
        await put(p, id, {
          ...base,
          responsibilities: 'Remote concurrent review',
          expectedRevision: before.revision,
        })
      ).status(),
    ).toBe(200);
    const rejected = p.waitForResponse(
      r =>
        r.request().method() === 'PUT' && new URL(r.url()).pathname === api(id),
    );
    await btn(p, `Save version v${before.revision + 1}`).click();
    expect((await rejected).status()).toBe(409);
    await expect(
      p.getByText('Local conflict edits', {exact: true}),
    ).toBeVisible();
    p.once('dialog', d => d.dismiss());
    await btn(p, 'Reload latest').click();
    await expect(
      p.getByText('Local conflict edits', {exact: true}),
    ).toBeVisible();
    p.once('dialog', d => d.accept());
    await btn(p, 'Reload latest').click();
    await expect(p.getByTestId('studio-view')).toBeVisible();
    await edit(p);
    await expect(field(p, 'responsibilities')).toHaveValue(
      'Remote concurrent review',
    );
    expect((await read(p, id)).revision).toBe(before.revision + 1);
  });
  test('committed response loss retries identical body and key', async ({
    page: p,
  }) => {
    const before = await read(p, id);
    await open(p, id);
    await edit(p);
    await field(p, 'responsibilities').fill('Ambiguous committed review');
    await review(p);
    const attempts = [];
    await p.route(`**${api(id)}`, async route => {
      if (route.request().method() !== 'PUT') return route.continue();
      attempts.push({
        key: route.request().headers()['idempotency-key'],
        body: route.request().postData(),
      });
      const r = await route.fetch({maxRetries: 0});
      expect(r.status()).toBe(200);
      expect((await r.json()).data.revision).toBe(before.revision + 1);
      if (attempts.length === 1) await route.abort('failed');
      else await route.fulfill({response: r});
    });
    await btn(p, `Save version v${before.revision + 1}`).click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(btn(p, 'Back')).toBeDisabled();
    await btn(p, 'Thử lại').click();
    await expect(p.getByTestId('studio-saved')).toBeVisible();
    expect(attempts).toHaveLength(2);
    expect(attempts[0].key).toMatch(/^[0-9a-f-]{36}$/i);
    expect(attempts[1]).toEqual(attempts[0]);
    expect((await read(p, id)).revision).toBe(before.revision + 1);
  });
  test('GET failure after confirmed save retries only GET', async ({
    page: p,
  }) => {
    const before = await read(p, id);
    await open(p, id);
    const requests = writes(p, id);
    await edit(p);
    const responsibilities = `Confirmed save read retry ${randomUUID()}`;
    await field(p, 'responsibilities').fill(responsibilities);
    await review(p);
    await btn(p, `Save version v${before.revision + 1}`).click();
    await expect(p.getByTestId('studio-saved')).toBeVisible();
    expect(requests).toHaveLength(1);

    let reads = 0;
    await p.route(`**${api(id)}`, async route => {
      if (route.request().method() !== 'GET') return route.continue();
      reads++;
      if (reads === 1)
        return route.fulfill({
          status: 503,
          json: {error: {code: 'UNAVAILABLE'}},
        });
      await route.continue();
    });
    await btn(p, 'Back to studio').click();
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(p.getByTestId('studio-saved')).toHaveCount(0);
    expect(reads).toBe(1);
    expect(requests).toHaveLength(1);
    await btn(p, 'Thử lại').click();
    await expect(p.getByTestId('studio-view')).toBeVisible();
    await expect(p.getByTestId('studio-view')).toContainText(responsibilities);
    expect(reads).toBe(2);
    expect(requests).toHaveLength(1);
    const current = await read(p, id);
    expect(current.revision).toBe(before.revision + 1);
    expect(current.agent.responsibilities).toBe(responsibilities);
    expect(requests).toHaveLength(1);
  });
  test('member, CSRF, foreign scope and ACTIVE readonly', async ({page: p}) => {
    const current = await read(p, id),
      data = {...base, expectedRevision: current.revision};
    expect((await put(p, id, data, false)).status()).toBe(403);
    await open(p, active);
    await expect(btn(p, 'Edit')).toHaveCount(0);
    const readonly = await read(p, active);
    expect(
      (
        await put(p, active, {...data, expectedRevision: readonly.revision})
      ).status(),
    ).toBe(409);
    const foreign = api(id).replace(
      org,
      'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    );
    expect((await p.request.get(foreign)).status()).toBe(404);
    expect((await put(p, id, data, true, foreign)).status()).toBe(404);
    await login(p, 'studio-member@example.test');
    await open(p, id);
    await expect(btn(p, 'Edit')).toHaveCount(0);
    expect((await put(p, id, data)).status()).toBe(403);
    expect((await read(p, id)).revision).toBe(current.revision);
  });
  test('admin revoked during review clears private data', async ({page: p}) => {
    const before = await read(p, id);
    await open(p, id);
    await edit(p);
    await field(p, 'responsibilities').fill('Private revoked edit');
    await review(p);
    try {
      sql(
        `UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='studio-admin@example.test');`,
      );
      const rejected = p.waitForResponse(
        r =>
          r.request().method() === 'PUT' &&
          new URL(r.url()).pathname === api(id),
      );
      await btn(p, `Save version v${before.revision + 1}`).click();
      expect((await rejected).status()).toBe(403);
      await expect(p.getByRole('alert')).toBeVisible();
      for (const k of Object.keys(labels))
        await expect(field(p, k)).toHaveCount(0);
      await expect(
        p.getByText('Private revoked edit', {exact: true}),
      ).toHaveCount(0);
      await expect(p.getByTestId('studio-diff')).toHaveCount(0);
      await expect(p.getByTestId('studio-saved')).toHaveCount(0);
    } finally {
      sql(
        `UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='studio-admin@example.test');`,
      );
    }
    expect((await read(p, id)).revision).toBe(before.revision);
  });
});
