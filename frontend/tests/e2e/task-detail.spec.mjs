import {test, expect} from '@playwright/test';

const org = '20202020-2020-4020-8020-202020202020';
const foreign = '21212121-2121-4121-8121-212121212121';
const id = (prefix, n) =>
  `${prefix}0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const team = id('2010', 1),
  mission = id('2030', 1);
const base = `/workspace/${org}/teams/${team}/missions/${mission}`;
const api = `/api/v1/organizations/${org}/teams/${team}/missions/${mission}/tasks/`;
const url = n => `${base}/tasks/${id('2040', n)}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
async function login(p) {
  expect(process.env.E2E_PASSWORD, 'bootstrap password required').toBeTruthy();
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill('mission-workspace-admin@example.test');
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, n = 1) {
  const [response] = await Promise.all([
    p.waitForResponse(
      r =>
        new URL(r.url()).pathname === api + id('2040', n) &&
        r.request().method() === 'GET',
    ),
    p.goto(url(n)),
  ]);
  expect(response.status()).toBe(200);
  const data = (await response.json()).data;
  await expect(
    p.getByRole('heading', {name: data.task.title, exact: true}),
  ).toBeVisible();
  return data;
}

test('TD01 all nine persisted statuses, pending fields and honest unavailable sections', async ({
  page: p,
}) => {
  await login(p);
  const states = [
    'DRAFT',
    'READY',
    'RUNNING',
    'WAITING',
    'BLOCKED',
    'IN_REVIEW',
    'COMPLETED',
    'FAILED',
    'CANCELLED',
  ];
  for (let n = 1; n <= 9; n++) {
    const data = await open(p, n);
    expect(data.task).toMatchObject({
      id: id('2040', n),
      organizationId: org,
      teamId: team,
      missionId: mission,
      title: `Control task 0${n}`,
      status: states[n - 1],
    });
    await expect(p.getByRole('main')).toContainText(
      new RegExp(states[n - 1].replaceAll('_', '[ _]'), 'i'),
    );
    for (const field of ['createdAt', 'updatedAt'])
      expect(Number.isFinite(Date.parse(data.task[field]))).toBe(true);
    if ([4, 5].includes(n)) {
      expect(data.task.pendingReason).toBe(`Await evidence ${n}`);
      expect(data.task.resolver).toBe('Control operator');
      expect(Number.isFinite(Date.parse(data.task.waitingSince))).toBe(true);
      await expect(p.getByRole('main')).toContainText(data.task.pendingReason);
      await expect(p.getByRole('main')).toContainText(data.task.resolver);
      await expect(p.getByRole('main').locator('time').first()).toHaveAttribute(
        'datetime',
        data.task.waitingSince,
      );
    } else
      for (const field of ['pendingReason', 'waitingSince', 'resolver'])
        expect(data.task[field]).toBeNull();
    await expect(p.getByRole('heading', {name: /Overview/i})).toBeVisible();
    await expect(
      p.getByRole('heading', {name: /Runs & output history/i}),
    ).toBeVisible();
    await expect(p.getByRole('main')).toContainText(/acceptance/i);
    await expect(p.getByRole('main')).toContainText(/unavailable/i);
  }
});

test('TD02 task-only activity is 20+4 with safe keys; empty task has no invented events', async ({
  page: p,
}) => {
  await login(p);
  await open(p);
  await expect(
    p.getByRole('heading', {name: /^Recorded activity/}),
  ).toBeVisible();
  const all = [];
  for (let page = 0; page < 2; page++) {
    const response = await p.request.get(
      `${api}${id('2040', 1)}/activity?page=${page}`,
    );
    expect(response.status()).toBe(200);
    const data = (await response.json()).data;
    expect(data).toMatchObject({page, size: 20, total: 24});
    expect(data.items).toHaveLength(page === 0 ? 20 : 4);
    for (const event of data.items)
      expect(Object.keys(event).sort()).toEqual([
        'actorType',
        'createdAt',
        'eventType',
        'id',
      ]);
    all.push(...data.items);
  }
  expect(all.map(x => x.id)).toEqual(
    Array.from({length: 24}, (_, i) => id('2050', 24 - i)),
  );
  await expect(button(p, 'Previous')).toBeDisabled();
  const [next] = await Promise.all([
    p.waitForResponse(
      r =>
        new URL(r.url()).pathname === `${api}${id('2040', 1)}/activity` &&
        new URL(r.url()).searchParams.get('page') === '1',
    ),
    button(p, 'Next').click(),
  ]);
  expect((await next.json()).data.items).toEqual(all.slice(20));
  await expect(
    p.getByTestId('task-activity').getByTestId('activity-row'),
  ).toHaveCount(4);
  await expect(button(p, 'Next')).toBeDisabled();
  await expect(p.getByRole('main')).not.toContainText(
    /PRIVATE|MIXED_OTHER|MIXED_FOREIGN/,
  );
  await open(p, 2);
  const empty = await p.request.get(`${api}${id('2040', 2)}/activity?page=0`);
  expect((await empty.json()).data).toMatchObject({items: [], total: 0});
  await expect(p.getByRole('main')).not.toContainText('CONTROL_TASK_EVENT');
});

test('TD03 native Mission Control links, back navigation, disabled actions and responsive no writes', async ({
  page: p,
}) => {
  await login(p);
  const writes = [];
  p.on('request', r => {
    if (
      new URL(r.url()).pathname.startsWith('/api/v1/organizations/') &&
      !['GET', 'HEAD', 'OPTIONS'].includes(r.method())
    )
      writes.push(`${r.method()} ${r.url()}`);
  });
  await p.goto(`${base}/workspace`);
  const link = p.getByRole('link', {name: 'Control task 31', exact: true});
  await expect(link).toHaveAttribute('href', url(31));
  await link.click();
  await expect(
    p.getByRole('heading', {name: 'Control task 31', exact: true}),
  ).toBeVisible();
  for (const name of ['View approval', 'View run', 'Retry task'])
    await expect(button(p, name)).toBeDisabled();
  await expect(
    p.getByRole('link', {name: 'Back to mission', exact: true}),
  ).toHaveAttribute('href', `${base}/workspace`);
  await expect(
    p.getByRole('link', {name: 'Back to team', exact: true}),
  ).toHaveAttribute('href', `/workspace/${org}/teams/${team}/workspace`);
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  await p.reload();
  await expect(
    p.getByRole('heading', {name: 'Control task 31', exact: true}),
  ).toBeVisible();
  await p.getByRole('link', {name: 'Back to mission', exact: true}).click();
  await expect(p).toHaveURL(`${base}/workspace`);
  await p.goBack();
  await expect(
    p.getByRole('heading', {name: 'Control task 31', exact: true}),
  ).toBeVisible();
  expect(writes).toEqual([]);
});

test('TD04 every scope boundary denies both native endpoints and UI clears stale task', async ({
  page: p,
}) => {
  await login(p);
  await open(p);
  const path = api + id('2040', 1);
  const invalid = [[path.replace(org, foreign), 403]];
  for (const [old, replacement] of [
    [team, id('2010', 2)],
    [team, id('2010', 999)],
    [team, id('2110', 1)],
    [mission, id('2030', 2)],
    [mission, id('2030', 3)],
    [mission, id('2030', 4)],
    [mission, id('2130', 1)],
    [id('2040', 1), id('2040', 32)],
    [id('2040', 1), id('2040', 33)],
    [id('2040', 1), id('2140', 1)],
    [id('2040', 1), id('2040', 999)],
  ])
    invalid.push([path.replace(old, replacement), 404]);
  for (const [bad, status] of invalid)
    for (const suffix of ['', '/activity?page=0'])
      expect((await p.request.get(bad + suffix)).status(), bad + suffix).toBe(
        status,
      );
  await p.goto(url(999));
  await expect(p.getByRole('alert')).toBeVisible();
  await expect(
    p.getByRole('heading', {name: 'Control task 01', exact: true}),
  ).toHaveCount(0);
  await expect(p.getByRole('main')).not.toContainText('CONTROL_TASK_EVENT');
});

test('TD05 failure-only intercepts reject stale or malformed data; native retry recovers', async ({
  page: p,
}) => {
  await login(p);
  for (const endpoint of ['', '/activity*']) {
    for (const mode of ['503', 'null', 'malformed']) {
      await open(p);
      await expect(p.getByTestId('task-detail')).toHaveAttribute(
        'aria-busy',
        'false',
      );
      const pattern = `**${api}${id('2040', 1)}${endpoint}`;
      await p.route(pattern, async route => {
        if (mode === '503') return route.fulfill({status: 503, json: {}});
        if (mode === 'null') return route.fulfill({status: 200, json: null});
        const response = await route.fetch();
        const body = await response.json();
        if (endpoint) body.data.items[0].payload = 'MUST_REJECT';
        else body.data.task.organizationId = foreign;
        return route.fulfill({response, json: body});
      });
      try {
        await button(p, 'Refresh').click();
        await expect(p.getByRole('alert')).toBeVisible();
        await expect(p.getByTestId('task-detail')).toHaveAttribute(
          'aria-busy',
          'false',
        );
        await expect(
          p.getByRole('heading', {name: 'Control task 01', exact: true}),
        ).toHaveCount(0);
        await expect(p.getByTestId('activity-row')).toHaveCount(0);
      } finally {
        await p.unroute(pattern);
      }
      await button(p, 'Try again').click();
      await expect(
        p.getByRole('heading', {name: 'Control task 01', exact: true}),
      ).toBeVisible();
      await expect(p.getByTestId('activity-row')).toHaveCount(20);
      await expect(p.getByRole('alert')).toHaveCount(0);
    }
  }
});

test('TD06 invalid activity URLs reset safely and compact native menu restores focus', async ({
  page: p,
}, info) => {
  await login(p);
  for (const query of [
    'page=-1',
    'page=bad',
    'page=100001',
    'page=0&page=1',
    'tab=unknown',
  ]) {
    await p.goto(`${url(1)}?${query}`);
    await expect(p.getByRole('alert')).toContainText(
      'Invalid task address or activity page.',
    );
    await expect(
      p.getByRole('heading', {name: 'Control task 01', exact: true}),
    ).toHaveCount(0);
    await button(p, 'Reset activity page').click();
    await expect(
      p.getByRole('heading', {name: 'Control task 01', exact: true}),
    ).toBeVisible();
    await expect(p.getByTestId('activity-row')).toHaveCount(20);
  }
  if (info.project.name !== 'desktop') {
    await button(p, 'Menu').click();
    const dialog = p.getByRole('dialog', {
      name: 'Workspace navigation',
      exact: true,
    });
    await expect(dialog).toBeVisible();
    await expect(
      dialog.getByRole('link', {name: 'Missions', exact: true}),
    ).toHaveAttribute('href', `/workspace/${org}/missions`);
    await p.keyboard.press('Escape');
    await expect(dialog).not.toBeVisible();
    await expect(button(p, 'Menu')).toBeFocused();
  }
});
