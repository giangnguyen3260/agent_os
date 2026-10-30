import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync, readFileSync} from 'node:fs';
import {createHash} from 'node:crypto';
import {join} from 'node:path';

const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
function scope(info) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('56000000', n),
    team = uuid('56010000', n * 100 + 1),
    mission = uuid('56020000', n * 100 + 1),
    task = uuid('56030000', n * 1000 + 2),
    agent = uuid('56040000', n * 1000 + 1);
  const root = `/workspace/${org}`,
    teamPath = `${root}/teams/${team}`,
    missionPath = `${teamPath}/missions/${mission}`,
    taskPath = `${missionPath}/tasks/${task}`;
  return {
    org,
    root,
    team,
    agent,
    teamPath,
    missionPath,
    taskPath,
    api: `/api/v1/organizations/${org}`,
  };
}
// One browser case for each of the 24 changed template sources. Frozen paths
// intentionally do not depend on /tmp or on production template string matching.
const sources = [
  ['task-edit/task-draft-edit', s => `${s.taskPath}/edit`],
  [
    'mission-intake-history/mission-intake-history',
    s => `${s.missionPath}/intake/history`,
  ],
  ['task-owner/task-owner', s => `${s.taskPath}/owner`],
  ['task-history/task-history', s => `${s.taskPath}/history`],
  ['agents/agent-page', s => `${s.root}/agents/${s.agent}`],
  ['agents/agent-studio-page', s => `${s.root}/agents/${s.agent}/studio`],
  ['agents/agent-list-page', s => `${s.root}/agents`],
  [
    'agents/activation-review-page',
    s => `${s.root}/agents/${s.agent}/activation-review`,
  ],
  ['task-owner-history/task-owner-history', s => `${s.taskPath}/owner-history`],
  ['missions/task-detail', s => s.taskPath],
  ['missions/new-task-create', s => `${s.missionPath}/tasks/new`],
  ['missions/mission-workspace', s => `${s.missionPath}/workspace`],
  ['teams/team-settings', s => `${s.teamPath}/settings`],
  ['teams/team-page', s => s.teamPath],
  ['teams/team-directory', s => `${s.root}/teams`],
  ['teams/team-workspace', s => `${s.teamPath}/workspace`],
  ['mission-intake/mission-intake', s => `${s.missionPath}/intake`],
  ['agents/agent-permissions', s => `${s.root}/agents/${s.agent}/permissions`],
  ['organization/organization-map', s => `${s.root}/organization-map`],
  ['missions/mission-list-page', s => `${s.root}/missions`],
  ['missions/mission-page', s => s.missionPath],
];
function business() {
  // Read-only whole-table snapshots cover both the current and foreign org and
  // catch same-value UPDATEs using tuple identity, as well as event/receipt writes.
  const tables = [
    'human_accounts',
    'organizations',
    'organization_memberships',
    'teams',
    'missions',
    'tasks',
    'agents',
    'task_dependencies',
    'task_owner_events',
    'task_owner_receipts',
    'task_edit_events',
    'audit_events',
    'approval_requests',
    'approval_action_heads',
    'approval_snapshots',
    'approval_grants',
    'approval_decisions',
    'approval_receipts',
    'approval_information_entries',
    'approval_information_receipts',
    'task_edit_receipts',
    'task_create_receipts',
    'mission_intake_receipts',
    'mission_create_receipts',
    'team_create_receipts',
    'agent_draft_config',
    'agent_create_receipts',
    'agent_configuration_versions',
    'agent_configuration_receipts',
    'team_settings_ledgers',
    'team_settings_events',
    'team_settings_receipts',
    'member_role_ledgers',
    'member_role_events',
    'member_role_receipts',
    'approval_grant_ledgers',
    'approval_grant_management_events',
    'approval_grant_management_receipts',
    'organization_invitations',
    'invitation_events',
    'invitation_receipts',
  ];
  return execFileSync(
    'docker',
    [
      'compose',
      'exec',
      '-T',
      'postgres',
      'sh',
      '-c',
      'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      encoding: 'utf8',
      timeout: 15000,
      input: tables
        .map(
          t =>
            `SELECT '${t}',md5(coalesce(jsonb_agg(x ORDER BY x::text),'[]'::jsonb)::text) FROM (SELECT row_to_json(t)::jsonb || jsonb_build_object('_xmin',xmin::text,'_ctid',ctid::text) AS x FROM ${t} t) q;`,
        )
        .join('\n'),
      stdio: ['pipe', 'pipe', 'pipe'],
    },
  ).trim();
}
async function login(
  p,
  info,
  kind = 'admin',
  account = `owner-history-${info.project.name}-${kind}@example.test`,
) {
  await p.goto('/login');
  await p.getByLabel('Email', {exact: true}).fill(account);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function get(p, path, action) {
  const [r] = await Promise.all([
    p.waitForResponse(
      r => new URL(r.url()).pathname === path && r.request().method() === 'GET',
    ),
    action(),
  ]);
  await r.finished();
  return r;
}
async function nav(p) {
  if (p.viewportSize().width >= 1024) {
    const sidebar = p.locator('aside.desktop-sidebar nav');
    await expect(sidebar).toBeVisible();
    return sidebar;
  }
  const menu = button(p, 'Menu');
  if ((await menu.getAttribute('aria-expanded')) !== 'true') await menu.click();
  const controlled = await menu.getAttribute('aria-controls');
  expect(controlled).toBeTruthy();
  const container = p.locator(`[id="${controlled}"]`);
  await expect(container).toBeVisible();
  return container;
}
async function links(p, s) {
  const n = await nav(p);
  for (const [name, suffix] of [
    ['Overview', 'dashboard'],
    ['Organization map', 'organization-map'],
    ['Agents', 'agents'],
    ['Missions', 'missions'],
    ['Approvals', 'approvals'],
    ['Trace', 'trace'],
  ]) {
    const entry = n.getByText(name, {exact: true});
    await expect(entry).toBeVisible();
    if ((await entry.evaluate(el => el.tagName)) === 'A')
      await expect(entry).toHaveAttribute('href', `${s.root}/${suffix}`);
    else await expect(entry).toHaveAttribute('aria-current', 'page');
  }
  const teams = n.getByText('Teams', {exact: true});
  await expect(teams).toBeVisible();
  if ((await teams.evaluate(el => el.tagName)) === 'A')
    await expect(teams).toHaveAttribute('href', `${s.root}/teams`);
  else await expect(teams).toHaveAttribute('aria-current', 'page');
  const workspace = n.locator('a[href="/workspace"]');
  if (p.viewportSize().width < 1024) await expect(workspace).toBeVisible();
  else if (await workspace.count())
    await expect(workspace).toHaveAttribute('href', '/workspace');
  const labels = await n
    .locator('a, span[aria-current="page"]')
    .allTextContents();
  expect(labels.map(t => t.trim()).slice(0, 7)).toEqual([
    'Overview',
    'Organization map',
    'Teams',
    'Agents',
    'Missions',
    'Approvals',
    'Trace',
  ]);
  return n;
}
async function empty(p, r) {
  expect(r.status()).toBe(200);
  const data = (await r.json()).data;
  expect(data).toMatchObject({
    items: [],
    total: 0,
    page: 0,
    size: 20,
    counts: {pending: 0, needsInformation: 0, decided: 0},
  });
  const main = p.getByTestId('approval-center');
  await expect(main).toHaveAttribute('aria-busy', 'false');
  await expect(
    main.getByRole('heading', {name: 'Approval Center', exact: true}),
  ).toBeVisible();
  await expect(main.getByTestId('approval-record')).toHaveCount(0);
  await expect(main).toContainText('No approval records in this group.');
  await expect(main).toContainText('Page 1 · 0 records');
  for (const name of ['Pending', 'Needs information', 'Decided'])
    await expect(
      main.getByRole('button', {name: `${name} · 0`, exact: true}),
    ).toBeVisible();
  await expect(
    main.getByRole('button', {name: /^(Approve|Reject|Submit decision)$/i}),
  ).toHaveCount(0);
}
async function approvals(p, s, n, keyboard = false) {
  const link = n.getByRole('link', {name: 'Approvals', exact: true});
  if (keyboard) await link.focus();
  const r = await get(p, `${s.api}/approvals`, () =>
    keyboard ? p.keyboard.press('Enter') : link.click(),
  );
  await expect(p).toHaveURL(`${s.root}/approvals`);
  // This navigation is organization-wide; it must not inherit task/team filters.
  expect(
    [...new URL(r.url()).searchParams.keys()].every(k =>
      ['group', 'page', 'size'].includes(k),
    ),
  ).toBe(true);
  await empty(p, r);
}
async function capture(p, info, s) {
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `workspace-navigation-${info.project.name}.png`,
    path = join(dir, file);
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  await p.screenshot({path, fullPage: true});
  const bytes = readFileSync(path);
  expect(bytes.readUInt32BE(16)).toBe(p.viewportSize().width);
  appendFileSync(
    join(dir, 'workspace-navigation-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      file,
      route: new URL(p.url()).pathname,
      url: p.url(),
      width: p.viewportSize().width,
      organizationId: s.org,
      state: info.project.name === 'desktop' ? 'sidebar' : 'compact-menu',
      imageSHA256: createHash('sha256').update(bytes).digest('hex'),
      capturetimestamp: new Date().toISOString(),
      test: info.title,
    }) + '\n',
  );
}
// Await only our one-shot native-dialog handler, and always remove that exact
// listener. Never override confirm, removeAllListeners, or silently auto-accept.
async function confirmNavigation(p, accept, action) {
  let handler;
  const handled = new Promise((resolve, reject) => {
    handler = async dialog => {
      try {
        expect(dialog.type()).toBe('confirm');
        expect(dialog.message()).toContain('Rời trang sẽ bỏ bản nháp');
        if (accept) await dialog.accept();
        else await dialog.dismiss();
        resolve();
      } catch (e) {
        reject(e);
      }
    };
  });
  p.once('dialog', handler);
  try {
    await Promise.all([action(), handled]);
  } finally {
    p.off('dialog', handler);
  }
}

test.describe('workspace navigation independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  const baseline = new WeakMap(),
    writes = new WeakMap();
  test.beforeEach(async ({page: p}, info) => {
    expect(info.config.workers).toBe(1);
    expect(info.project.retries).toBe(0);
    expect(process.env.E2E_PASSWORD).toBeTruthy();
    baseline.set(p, business());
    const seen = [];
    writes.set(p, seen);
    p.on('request', r => {
      if (
        new URL(r.url()).pathname.startsWith('/api/v1/organizations/') &&
        !['GET', 'HEAD', 'OPTIONS'].includes(r.method())
      )
        seen.push(`${r.method()} ${r.url()}`);
    });
  });
  test.afterEach(async ({page: p}) => {
    expect(writes.get(p)).toEqual([]);
    expect(business()).toBe(baseline.get(p));
  });
  for (const [source, route] of sources)
    test(`${source}.component.html → organization-wide Approvals`, async ({
      page: p,
    }, info) => {
      const s = scope(info);
      await login(p, info);
      await p.goto(route(s));
      await expect(p).toHaveURL(route(s));
      await expect(p.locator('main')).toBeVisible();
      await expect(p.locator('main[aria-busy="true"]')).toHaveCount(0);
      const n = await links(p, s);
      if (source === 'teams/team-directory') {
        await expect(p.getByTestId('directory-row').first()).toContainText(
          'Owner history Team',
        );
        await capture(p, info, s);
      }
      await approvals(p, s, n, source === 'teams/team-directory');
    });
  for (const kind of ['detail', 'information'])
    test(`approvals/approval-${kind}.component.html → scoped Approval Center`, async ({
      page: p,
    }, info) => {
      const org =
        kind === 'detail'
          ? '30303030-3030-4030-8030-303030303030'
          : '40404040-4040-4040-8040-404040404040';
      const root = `/workspace/${org}`,
        api = `/api/v1/organizations/${org}`;
      const offset = {desktop: 0, tablet: 100, mobile: 200}[info.project.name];
      const id = uuid(kind === 'detail' ? '30500000' : '40500000', offset + 9);
      const route = `${root}/approvals/${id}${kind === 'information' ? '/information' : ''}`;
      await login(p, info, 'admin', `approval-${kind}-admin@example.test`);
      const source = await get(
        p,
        `${api}/approvals/${id}${kind === 'information' ? '/information' : ''}`,
        () => p.goto(route),
      );
      expect(source.status()).toBe(200);
      await expect(p.locator('main')).toHaveAttribute('aria-busy', 'false');
      const n = await links(p, {root});
      const r = await get(p, `${api}/approvals`, () =>
        n.getByRole('link', {name: 'Approvals', exact: true}).click(),
      );
      expect(r.status()).toBe(200);
      const data = (await r.json()).data;
      expect(data.total).toBeGreaterThan(0);
      await expect(p).toHaveURL(`${root}/approvals`);
      await expect(p.getByTestId('approval-center')).toHaveAttribute(
        'aria-busy',
        'false',
      );
      await expect(p.getByTestId('approval-record')).toHaveCount(
        data.items.length,
      );
    });
  test('approvals/approval-list-page.component.html → real Organization map', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await login(p, info);
    const source = await get(p, `${s.api}/approvals`, () =>
      p.goto(`${s.root}/approvals`),
    );
    await empty(p, source);
    const n = await links(p, s);
    const r = await get(p, `${s.api}/organization-map`, () =>
      n.getByRole('link', {name: 'Organization map', exact: true}).click(),
    );
    expect(r.status()).toBe(200);
    await expect(p.getByTestId('org-map-overview')).toBeVisible();
    await expect(p).toHaveURL(`${s.root}/organization-map`);
  });
  test('Task Detail Organization approvals action opens unfiltered organization-wide empty list', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await login(p, info);
    await p.goto(s.taskPath);
    const action = p
      .locator('main')
      .getByRole('link', {name: 'Organization approvals', exact: true});
    await expect(action).toHaveAttribute('href', `${s.root}/approvals`);
    await expect(p.locator('main')).toContainText(
      'Shows approvals across this organization; this task is not used as a filter.',
    );
    const r = await get(p, `${s.api}/approvals`, () => action.click());
    await expect(p).toHaveURL(`${s.root}/approvals`);
    expect(
      [...new URL(r.url()).searchParams.keys()].every(k =>
        ['group', 'page', 'size'].includes(k),
      ),
    ).toBe(true);
    await empty(p, r);
  });
  test('Organization map link loads real scoped saved teams', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await login(p, info);
    await p.goto(`${s.root}/teams`);
    const n = await links(p, s);
    const r = await get(p, `${s.api}/organization-map`, () =>
      n.getByRole('link', {name: 'Organization map', exact: true}).click(),
    );
    expect(r.status()).toBe(200);
    await expect(p).toHaveURL(`${s.root}/organization-map`);
    await expect(p.getByTestId('org-map-overview')).toBeVisible();
    await expect(p.getByTestId('org-map-team').first()).toContainText(
      'Owner history Team',
    );
    await expect(p.locator('main')).not.toContainText(
      'PRIVATE_OWNER_HISTORY_FOREIGN',
    );
  });
  test('member denied history retains workspace navigation and read-only Approvals', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await login(p, info, 'member');
    await p.goto(`${s.taskPath}/owner-history`);
    await expect(p.getByTestId('owner-history-denied')).toBeVisible();
    const n = await links(p, s);
    await n.getByRole('link', {name: 'Teams', exact: true}).click();
    await expect(p.getByTestId('directory-row').first()).toContainText(
      'Owner history Team',
    );
    await approvals(p, s, await links(p, s));
    await expect(p.getByTestId('approval-center')).toContainText(
      'Quyền đọc không cấp quyền duyệt.',
    );
  });
  test('other account cannot navigate into private organization data', async ({
    page: p,
  }, info) => {
    const s = scope(info);
    await login(p, info, 'other');
    const r = await get(p, `/api/v1/auth/workspaces/${s.org}`, () =>
      p.goto(`${s.root}/teams`),
    );
    expect(r.status()).toBe(403);
    await expect(p.locator('main').getByRole('alert')).toBeVisible();
    const n = await links(p, s);
    await n.getByRole('link', {name: 'Approvals', exact: true}).click();
    await expect(p).toHaveURL(`${s.root}/approvals`);
    await expect(
      p.getByTestId('approval-center').getByRole('alert'),
    ).toHaveText('Approval access is unavailable for this workspace.');
    await expect(p.getByTestId('approval-record')).toHaveCount(0);
    await expect(p.locator('main')).not.toContainText(
      /Owner history (Team|Mission|Task|Agent)|PRIVATE_OWNER_HISTORY_FOREIGN/,
    );
    expect((await p.request.get(`${s.api}/approvals`)).status()).toBe(403);
  });
  test('dirty task2 edit cancels then accepts Approvals navigation without saving', async ({
    page: p,
  }, info) => {
    const s = scope(info),
      route = `${s.taskPath}/edit`;
    await login(p, info);
    await p.goto(route);
    const title = p.getByLabel('Title', {exact: true});
    await expect(title).toBeEditable();
    await title.fill('Unsaved navigation QC draft');
    const n = await links(p, s),
      link = n.getByRole('link', {name: 'Approvals', exact: true});
    await confirmNavigation(p, false, () => link.click());
    await expect(p).toHaveURL(route);
    await expect(title).toHaveValue('Unsaved navigation QC draft');
    const next = await nav(p);
    const r = await get(p, `${s.api}/approvals`, () =>
      confirmNavigation(p, true, () =>
        next.getByRole('link', {name: 'Approvals', exact: true}).click(),
      ),
    );
    await empty(p, r);
    await expect(p).toHaveURL(`${s.root}/approvals`);
    await p.goto(route);
    await expect(title).toHaveValue('Owner history Task 2');
  });
  test('unauthenticated scoped navigation preserves authentication guard', async ({
    page: p,
  }, info) => {
    const s = scope(info),
      reads = [];
    p.on('request', r => {
      if (new URL(r.url()).pathname.startsWith(s.api)) reads.push(r.url());
    });
    await p.goto(`${s.root}/approvals`);
    await expect(p).toHaveURL(/\/login(?:\?|$)/);
    await expect(p.getByLabel('Email', {exact: true})).toBeVisible();
    await expect(p.getByTestId('approval-center')).toHaveCount(0);
    expect(reads).toEqual([]);
  });
});
