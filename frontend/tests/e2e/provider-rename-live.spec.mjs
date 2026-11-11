import {test, expect} from '@playwright/test';

test.skip(
  process.env.PROVIDER_RENAME_VAULT_LIVE !== 'true',
  'Requires the disposable real PostgreSQL, TLS Vault and managed HTTP fixture.',
);

test('real Rename confirms one reply and explicitly recovers a malformed reply through its own receipt', async ({
  page,
}) => {
  test.setTimeout(90_000);
  const organization = process.env.PROVIDER_VAULT_ORG;
  const connection = process.env.PROVIDER_RENAME_CONNECTION;
  const email = process.env.PROVIDER_VAULT_EMAIL;
  const password = process.env.PROVIDER_VAULT_PASSWORD;
  if (!organization || !connection || !email || !password) {
    throw new Error('Missing disposable Rename browser fixtures');
  }
  const root = `/api/v1/organizations/${organization}/provider-connections`;
  const rename = `${root}/${connection}/rename`;
  const posts = [],
    lookups = [],
    statuses = [];
  let creates = 0;
  page.on('request', request => {
    const path = new URL(request.url()).pathname;
    if (path === rename && request.method() === 'POST') {
      posts.push({
        key: request.headers()['idempotency-key'],
        body: request.postDataJSON(),
      });
    }
    if (path === root && request.method() === 'POST') creates++;
    if (path.startsWith(root + '/receipts/')) {
      lookups.push({method: request.method(), path, body: request.postData()});
    }
  });
  page.on('response', response => {
    if (
      new URL(response.url()).pathname === rename &&
      response.request().method() === 'POST'
    ) {
      statuses.push(response.status());
    }
  });
  const heading = name =>
    page
      .getByRole('region', {name: 'Danh sách kết nối'})
      .getByRole('heading', {name, exact: true});
  async function review(current, requested) {
    await expect(heading(current)).toBeVisible({timeout: 35_000});
    const region = page.getByRole('region', {name: 'Đổi tên kết nối'});
    if (!(await region.isVisible())) {
      await page
        .getByRole('button', {name: `Xem kết nối ${current}`, exact: true})
        .click();
    }
    await region.getByRole('button', {name: 'Đổi tên', exact: true}).click();
    await region
      .getByRole('textbox', {name: 'Tên mới', exact: true})
      .fill(requested);
    await region
      .getByRole('button', {name: 'Xem lại thay đổi', exact: true})
      .click();
    const reviewed = region.getByTestId('provider-rename-review');
    await expect(reviewed).toContainText(current);
    await expect(reviewed).toContainText(requested);
    await reviewed
      .getByRole('button', {name: 'Xác nhận đổi tên', exact: true})
      .click();
  }

  try {
    await page.goto('/login');
    await page.getByLabel('Email', {exact: true}).fill(email);
    await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
    await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
    await expect(page).toHaveURL(/\/workspace$/);
    await page.goto(`/workspace/${organization}/provider-connections`);

    await review('Live Rename before', 'Live Rename confirmed');
    await expect(heading('Live Rename confirmed')).toBeVisible({
      timeout: 35_000,
    });
    expect(posts).toHaveLength(1);
    expect(statuses).toEqual([201]);
    expect(lookups).toHaveLength(0);
    await expect(page.getByTestId('provider-rename-unknown')).toHaveCount(0);

    await review('Live Rename confirmed', 'Live Rename recovered');
    const unknown = page.getByTestId('provider-rename-unknown');
    await expect(unknown).toBeVisible({timeout: 35_000});
    expect(posts).toHaveLength(2);
    expect(statuses).toEqual([201, 201]);
    expect(lookups).toHaveLength(0);
    await unknown
      .getByRole('button', {name: 'Kiểm tra kết quả', exact: true})
      .click();
    await expect(heading('Live Rename recovered')).toBeVisible({
      timeout: 35_000,
    });
    await expect(unknown).toHaveCount(0);

    expect(posts).toHaveLength(2);
    expect(posts[0].body).toEqual({
      expectedRevision: '1',
      name: 'Live Rename confirmed',
    });
    expect(posts[1].body).toEqual({
      expectedRevision: '2',
      name: 'Live Rename recovered',
    });
    for (const post of posts) expect(post.key).toMatch(/^[0-9a-f-]{36}$/);
    expect(posts[1].key).not.toBe(posts[0].key);
    expect(lookups).toEqual([
      {method: 'GET', path: `${root}/receipts/${posts[1].key}`, body: null},
    ]);
    expect(creates).toBe(0);
    const stored = await page.evaluate(() =>
      JSON.stringify([
        Object.entries(localStorage),
        Object.entries(sessionStorage),
      ]),
    );
    for (const post of posts) expect(stored).not.toContain(post.key);
    await page.reload();
    await expect(heading('Live Rename recovered')).toBeVisible({
      timeout: 35_000,
    });
    expect(posts).toHaveLength(2);
    expect(lookups).toHaveLength(1);
    expect(creates).toBe(0);
  } catch (failure) {
    // The Java launcher emits only this numeric line and fixed source locations on failure.
    console.log(
      `PROVIDER_RENAME_LIVE_DIAGNOSTIC posts=${posts.length} statuses=${statuses.join(',')}`,
    );
    throw failure;
  }
});
