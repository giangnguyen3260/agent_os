import {test, expect} from '@playwright/test';

const email = process.env.E2E_EMAIL;
const password = process.env.E2E_PASSWORD;

test.beforeAll(() => {
  if (!email || !password)
    throw new Error(
      'Set E2E_EMAIL and E2E_PASSWORD for a disposable real backend.',
    );
});

test('anonymous APIs reject access and CSRF protects sign-in', async ({
  request,
}) => {
  expect((await request.get('/api/v1/organizations')).status()).toBe(401);
  expect((await request.get('/api/v1/auth/session')).status()).toBe(401);
  const rejected = await request.post('/api/v1/auth/login', {
    form: {username: email, password},
  });
  expect(rejected.status()).toBe(403);
});

test('login form is responsive, empty and keyboard accessible', async ({
  page,
}) => {
  await page.goto('/login');
  await expect(
    page.getByRole('heading', {name: 'Đăng nhập', exact: true}),
  ).toBeVisible();
  await expect(page.getByLabel('Email', {exact: true})).toHaveValue('');
  await expect(page.getByLabel('Mật khẩu', {exact: true})).toHaveValue('');
  await expect(page.getByLabel('Mật khẩu', {exact: true})).toHaveAttribute(
    'type',
    'password',
  );
  const geometry = await page.evaluate(() => ({
    width: innerWidth,
    scroll: document.documentElement.scrollWidth,
  }));
  expect(geometry.scroll).toBeLessThanOrEqual(geometry.width);
  const submit = page.getByRole('button', {name: 'Đăng nhập', exact: true});
  expect((await submit.boundingBox()).height).toBeGreaterThanOrEqual(44);
  await page.getByLabel('Email', {exact: true}).focus();
  await page.keyboard.press('Tab');
  await expect(page.getByLabel('Mật khẩu', {exact: true})).toBeFocused();
});

test('invalid credentials show an error and never create a session', async ({
  page,
}) => {
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page
    .getByLabel('Mật khẩu', {exact: true})
    .fill('incorrect-test-password');
  await page.getByRole('button', {name: 'Đăng nhập', exact: true}).click();
  await expect(page.getByRole('alert')).toContainText('Email hoặc mật khẩu');
  expect((await page.request.get('/api/v1/auth/session')).status()).toBe(401);
  await expect(page.getByLabel('Mật khẩu', {exact: true})).toHaveValue('');
});

test('real persisted login survives reload; logout revokes the session', async ({
  page,
}) => {
  await page.goto('/login');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page.getByLabel('Mật khẩu', {exact: true}).fill(password);
  await page.getByLabel('Mật khẩu', {exact: true}).press('Enter');
  await expect(page).toHaveURL(/\/workspace$/);
  const session = await page.request.get('/api/v1/auth/session');
  expect(session.status()).toBe(200);
  expect((await session.json()).data.email).toBe(email);
  const cookies = await page.context().cookies();
  const authCookie = cookies.find(cookie => cookie.name === 'SESSION');
  expect(authCookie).toBeDefined();
  expect(authCookie.httpOnly).toBe(true);
  expect(authCookie.sameSite).toBe('Lax');
  await page.reload();
  await expect(
    page.getByRole('button', {name: 'Đổi tài khoản', exact: true}),
  ).toBeVisible();
  expect((await page.request.get('/api/v1/auth/workspaces')).status()).toBe(
    200,
  );
  await page.getByRole('button', {name: 'Đổi tài khoản', exact: true}).click();
  await expect(page).toHaveURL(/\/login$/);
  expect((await page.request.get('/api/v1/auth/session')).status()).toBe(401);
  const replay = await page.request.get('/api/v1/auth/session', {
    headers: {Cookie: `SESSION=${authCookie.value}`},
  });
  expect(replay.status()).toBe(401);
  await page.goto('/dashboard');
  await expect(page).toHaveURL(/\/login$/);
});
