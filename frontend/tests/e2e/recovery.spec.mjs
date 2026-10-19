import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';

const mailbox = process.env.E2E_MAILPIT_URL;
const originalPassword = process.env.E2E_PASSWORD;

test.beforeAll(() => {
  if (!mailbox || !originalPassword)
    throw new Error('Recovery requires disposable SMTP/backend fixtures.');
});

async function csrf(request) {
  const response = await request.get('/api/v1/auth/csrf');
  expect(response.status()).toBe(200);
  const {token, headerName} = (await response.json()).data;
  return {[headerName]: token};
}

async function receivedToken(request, email) {
  let token;
  await expect
    .poll(
      async () => {
        const listing = await request.get(`${mailbox}/api/v1/messages`);
        if (!listing.ok()) return false;
        const messages = (await listing.json()).messages || [];
        for (const message of messages) {
          if (!(message.To || []).some(to => to.Address === email)) continue;
          const details = await request.get(
            `${mailbox}/api/v1/message/${message.ID}`,
          );
          const content = (await details.json()).Text || '';
          const found = content.match(/\/reset#token=([A-Za-z0-9_-]{43})/);
          if (found) {
            token = found[1];
            return true;
          }
        }
        return false;
      },
      {timeout: 30000, message: 'Real SMTP recovery message must arrive'},
    )
    .toBe(true);
  return token;
}

test('forgot form validates, stays honest on network failure and fits viewport', async ({
  page,
}) => {
  await page.goto('/login');
  await page.getByRole('link', {name: 'Quên mật khẩu', exact: true}).click();
  await expect(
    page.getByRole('heading', {name: 'Quên mật khẩu', exact: true}),
  ).toBeVisible();
  await expect(page.getByLabel('Email', {exact: true})).toHaveValue('');
  const layout = await page.evaluate(
    () => document.documentElement.scrollWidth <= innerWidth,
  );
  expect(layout).toBe(true);
  await page
    .getByRole('button', {name: 'Gửi liên kết đặt lại', exact: true})
    .click();
  await expect(page.locator('[aria-invalid="true"]')).toHaveCount(1);
  await page.getByLabel('Email', {exact: true}).fill('network@example.test');
  // Deliberate transport fault tests the UI; it supplies no fake backend success.
  await page.route('**/api/v1/auth/recovery/request', route => route.abort());
  await page
    .getByRole('button', {name: 'Gửi liên kết đặt lại', exact: true})
    .click();
  await expect(page.getByRole('alert')).toBeVisible();
  await expect(
    page.getByRole('heading', {name: 'Kiểm tra email', exact: true}),
  ).toHaveCount(0);
});

test('real SMTP link resets password once and revokes an existing session', async ({
  page,
  request,
}, testInfo) => {
  const email = `recovery-${testInfo.project.name}@example.test`;
  const newPassword = `Recovery-${randomUUID()}`;
  expect(
    (
      await request.post('/api/v1/auth/login', {
        form: {username: email, password: originalPassword},
        headers: await csrf(request),
      })
    ).status(),
  ).toBe(200);

  await page.goto('/forgot-password');
  await page.getByLabel('Email', {exact: true}).fill(email);
  await page
    .getByRole('button', {name: 'Gửi liên kết đặt lại', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Kiểm tra email', exact: true}),
  ).toBeVisible();
  const token = await receivedToken(request, email);
  await page.goto(`/reset#token=${token}`);
  await expect(
    page.getByRole('heading', {name: 'Đặt lại mật khẩu', exact: true}),
  ).toBeVisible();
  expect(new URL(page.url()).hash === '').toBe(true);
  await page.getByLabel('Mật khẩu mới', {exact: true}).fill(newPassword);
  await page
    .getByLabel('Nhập lại mật khẩu', {exact: true})
    .fill('does-not-match');
  await page
    .getByRole('button', {name: 'Lưu mật khẩu mới', exact: true})
    .click();
  await expect(page.locator('[aria-invalid="true"]')).not.toHaveCount(0);
  await page.getByLabel('Nhập lại mật khẩu', {exact: true}).fill(newPassword);
  await page
    .getByRole('button', {name: 'Lưu mật khẩu mới', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Đã đổi mật khẩu', exact: true}),
  ).toBeVisible();
  expect((await request.get('/api/v1/auth/session')).status()).toBe(401);
  expect((await page.request.get('/api/v1/auth/session')).status()).toBe(401);
  const replay = await page.request.post('/api/v1/auth/recovery/confirm', {
    headers: await csrf(page.request),
    data: {token, password: newPassword},
  });
  expect(replay.status()).toBe(400);
  expect((await replay.json()).error.code).toBe('INVALID_RESET_TOKEN');
  expect(
    (
      await page.request.post('/api/v1/auth/login', {
        headers: await csrf(page.request),
        form: {username: email, password: originalPassword},
      })
    ).status(),
  ).toBe(401);
  expect(
    (
      await page.request.post('/api/v1/auth/login', {
        headers: await csrf(page.request),
        form: {username: email, password: newPassword},
      })
    ).status(),
  ).toBe(200);
});

test('recovery requires CSRF and unknown address gets only a generic acknowledgement', async ({
  request,
}) => {
  const data = {email: `unknown-${randomUUID()}@example.test`};
  expect(
    (await request.post('/api/v1/auth/recovery/request', {data})).status(),
  ).toBe(403);
  const response = await request.post('/api/v1/auth/recovery/request', {
    data,
    headers: await csrf(request),
  });
  expect(response.status()).toBe(202);
  expect((await response.json()).data).toEqual({accepted: true});
});

test('missing reset link shows expired state and offers a working new request', async ({
  page,
}) => {
  await page.goto('/reset');
  await expect(
    page.getByRole('heading', {
      name: 'Liên kết không còn hiệu lực',
      exact: true,
    }),
  ).toBeVisible();
  await page
    .getByRole('link', {name: 'Yêu cầu liên kết mới', exact: true})
    .click();
  await expect(
    page.getByRole('heading', {name: 'Quên mật khẩu', exact: true}),
  ).toBeVisible();
});
