import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync, readFileSync} from 'node:fs';
import {createHash, randomUUID} from 'node:crypto';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

// All business successes come from the live application. Only delivery may be
// held, aborted or malformed. SQL writes below are named, restored test setups.
const uuid = (prefix, n) =>
  `${prefix}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const hash = value =>
  createHash('sha256')
    .update(
      typeof value === 'string' || Buffer.isBuffer(value)
        ? value
        : JSON.stringify(value),
    )
    .digest('hex');
const hook = (p, name) => p.getByTestId(`dependencies-${name}`);
const button = (p, name) => p.getByRole('button', {name, exact: true});
const ack = p =>
  p.getByLabel('Tôi đã xem lại thay đổi phụ thuộc.', {exact: true});
const quote = s => "'" + String(s).replaceAll("'", "''") + "'";
const focus = p => p.evaluate(() => window.dispatchEvent(new Event('focus')));
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
      'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"',
    ],
    {
      cwd: new URL('../../../', import.meta.url),
      input,
      encoding: 'utf8',
      timeout: 15000,
    },
  ).trim();
}
function scope(info, t = 19) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('57000000', n),
    team = uuid('57010000', n * 100 + (t === 36 ? 2 : 1));
  const mission = uuid('57020000', n * 100 + (t === 36 ? 2 : 1));
  const task = uuid('57030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  return {
    n,
    org,
    team,
    mission,
    task,
    api: `/api/v1/organizations/${org}${path}/draft-dependencies`,
    url: `/workspace/${org}${path}/dependencies`,
    detail: `/workspace/${org}${path}`,
  };
}
const taskId = (s, t) => uuid('57030000', s.n * 1000 + t);
async function response(p, path, action, method = 'GET') {
  const pending = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === method,
  );
  await action();
  return pending;
}
async function authenticate(p, info, kind = 'admin') {
  await p.goto('/login');
  await p
    .getByLabel('Email', {exact: true})
    .fill(`task-dependencies-${info.project.name}-${kind}@example.test`);
  await p.getByLabel('Mật khẩu', {exact: true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, info, t = 19) {
  await authenticate(p, info);
  const s = scope(info, t);
  const r = await response(p, s.api, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  s.source = (await r.json()).data;
  await expect(hook(p, s.source.canEdit ? 'edit' : 'blocked')).toBeVisible();
  return s;
}
function observe(p, s) {
  const posts = [];
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
      posts.push({key: r.headers()['idempotency-key'], body: r.postData()});
  });
  return posts;
}
function row(s) {
  return JSON.parse(
    sql(`SELECT row_to_json(t) FROM tasks t WHERE id='${s.task}';`),
  );
}
function edges(s) {
  return JSON.parse(
    sql(
      `SELECT coalesce(json_agg(dependency_task_id ORDER BY dependency_task_id),'[]') FROM task_dependencies WHERE task_id='${s.task}';`,
    ),
  );
}
// Read-only snapshots of EVERY public org-scoped table, including runtime,
// grants, approvals, events, receipts and audit. Return only table SHA256 values.
// Protected mode retains other sources and non-dependency audit rows. Source
// provenance is checked independently; historical same-source row immutability
// and non-org-scoped tables remain native-probe responsibilities.
function snapshot(s, protectedOnly = false) {
  const tables = JSON.parse(
    sql(
      `SELECT json_agg(table_name ORDER BY table_name) FROM information_schema.columns WHERE table_schema='public' AND column_name='organization_id';`,
    ),
  );
  const skip = new Set([
    'task_dependencies',
    'task_dependency_events',
    'task_dependency_receipts',
    'audit_events',
  ]);
  const queries = tables.map(t => {
    if (!/^[a-z_]+$/.test(t)) throw Error('Unsafe schema table');
    const value =
      protectedOnly && t === 'tasks'
        ? `CASE WHEN id='${s.task}' THEN to_jsonb(x)-ARRAY['dependency_revision','updated_at'] ELSE to_jsonb(x) END`
        : 'to_jsonb(x)';
    const untouched =
      protectedOnly && skip.has(t)
        ? t === 'audit_events'
          ? ` AND (task_id IS DISTINCT FROM '${s.task}'::uuid OR event_type <> 'TASK_DEPENDENCIES_UPDATED')`
          : ` AND task_id <> '${s.task}'`
        : '';
    return `SELECT ${quote(t)} || ':' || coalesce(jsonb_agg(v ORDER BY v::text)::text,'[]') FROM (SELECT ${value} v FROM "${t}" x WHERE organization_id='${s.org}'${untouched}) q;`;
  });
  return sql(
    `BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;\nSELECT 'organizations:' || to_jsonb(x)::text FROM organizations x WHERE id='${s.org}';\n` +
      queries.join('\n') +
      '\nCOMMIT;',
  )
    .split('\n')
    .filter(Boolean)
    .map(line => {
      const i = line.indexOf(':');
      return [line.slice(0, i), hash(line.slice(i + 1))];
    });
}
function provenance(s) {
  return JSON.parse(
    sql(
      `SELECT json_build_object('events',(SELECT count(*) FROM task_dependency_events WHERE task_id='${s.task}'), 'receipts',(SELECT count(*) FROM task_dependency_receipts WHERE task_id='${s.task}'), 'audit',(SELECT count(*) FROM audit_events WHERE task_id='${s.task}'));`,
    ),
  );
}
function membership(s, info, role) {
  sql(
    `UPDATE organization_memberships SET role=${quote(role)} WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email=${quote(`task-dependencies-${info.project.name}-admin@example.test`)});`,
  );
}
// Controlled disconnected/target metadata setup; restore exact title+timestamp.
function changeTitle(s, t, title) {
  const id = taskId(s, t);
  const old = JSON.parse(
    sql(
      `SELECT json_build_object('title',title,'updated_at',updated_at::text) FROM tasks WHERE id='${id}';`,
    ),
  );
  sql(
    `UPDATE tasks SET title=${quote(title)},updated_at=clock_timestamp() WHERE id='${id}';`,
  );
  return () =>
    sql(
      `UPDATE tasks SET title=${quote(old.title)},updated_at=${quote(old.updated_at)}::timestamptz WHERE id='${id}';`,
    );
}
async function shot(p, info, s, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  await expect(hook(p, state)).toBeVisible();
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `task-dependencies-${info.project.name}-${state}.png`,
    path = join(dir, file);
  await p.screenshot({path, fullPage: true});
  const png = readFileSync(path);
  expect(png.subarray(0, 8).toString('hex')).toBe('89504e470d0a1a0a');
  const width = png.readUInt32BE(16),
    height = png.readUInt32BE(20);
  expect(width).toBe(p.viewportSize().width);
  appendFileSync(
    join(dir, 'task-dependencies-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      viewport: p.viewportSize(),
      state,
      file,
      test: info.title,
      path: new URL(p.url()).pathname,
      scope: {
        organizationId: s.org,
        teamId: s.team,
        missionId: s.mission,
        taskId: s.task,
      },
      capturetimestamp: new Date().toISOString(),
      imageSHA256: hash(png),
      width,
      height,
    }) + '\n',
  );
}
async function chooser(p) {
  await hook(p, 'add').click();
  await expect(
    p.getByRole('dialog', {name: 'Chọn task phụ thuộc', exact: true}),
  ).toBeVisible();
  await expect(
    hook(p, 'chooser')
      .locator('[data-testid^="dependency-candidate-"]')
      .first(),
  ).toBeVisible();
}
async function closeChooser(p) {
  await p.keyboard.press('Escape');
  await expect(hook(p, 'chooser')).not.toBeVisible();
  await expect(hook(p, 'add')).toBeFocused();
}
async function chooseId(p, s, t) {
  await chooser(p);
  await hook(p, 'search').fill(`Task dependencies Task ${t}`);
  await hook(p, 'search-submit').click();
  await p.getByTestId(`dependency-choose-${taskId(s, t)}`).click();
  await closeChooser(p);
  await expect(
    hook(p, 'selection').getByTestId(`dependency-row-${taskId(s, t)}`),
  ).toBeVisible();
}
async function different(p, s) {
  if (s.source.dependencies.length) await hook(p, 'clear-all').click();
  else await chooseId(p, s, 4);
}
async function review(p) {
  await hook(p, 'review-action').click();
  await expect(hook(p, 'review')).toBeVisible();
  await expect(ack(p)).not.toBeChecked();
  await expect(hook(p, 'save')).toBeDisabled();
  await ack(p).check();
}
async function save(p, s, doubleClick = false) {
  const before = row(s),
    stable = snapshot(s, true),
    counts = provenance(s);
  const r = await response(
    p,
    s.api,
    () => (doubleClick ? hook(p, 'save').dblclick() : hook(p, 'save').click()),
    'POST',
  );
  expect(r.status()).toBe(201);
  const receipt = (await r.json()).data;
  await expect(hook(p, 'saved')).toBeVisible();
  expect(row(s).dependency_revision).toBe(before.dependency_revision + 1);
  expect(receipt.revision).toBe(before.dependency_revision + 1);
  expect(edges(s)).toEqual(receipt.after.map(t => t.id));
  expect(snapshot(s, true)).toEqual(stable);
  expect(provenance(s)).toEqual({
    events: counts.events + 1,
    receipts: counts.receipts + 1,
    audit: counts.audit + 1,
  });
  const db = JSON.parse(
    sql(
      `SELECT json_build_object('event',e.id,'before',e.before_json::json,'after',e.after_json::json,'key',r.idempotency_key,'bodyHash',r.body_sha256,'audit',a.payload::json) FROM task_dependency_events e JOIN task_dependency_receipts r ON r.event_id=e.id JOIN audit_events a ON a.task_id=e.task_id AND a.payload::jsonb->>'eventId'=e.id::text WHERE e.id='${receipt.eventId}';`,
    ),
  );
  expect(db.event).toBe(receipt.eventId);
  expect(db.before).toEqual(receipt.before);
  expect(db.after).toEqual(receipt.after);
  expect(db.key).toBe(r.request().headers()['idempotency-key']);
  const body = r.request().postDataJSON();
  const actor = sql(
    `SELECT actor_id FROM task_dependency_events WHERE id='${receipt.eventId}';`,
  );
  const canonical = [...body.dependencies]
    .sort((a, b) => (a.taskId < b.taskId ? -1 : a.taskId > b.taskId ? 1 : 0))
    .map(t => [t.taskId, t.fingerprint]);
  expect(db.bodyHash).toBe(
    hash([
      'TASK_DEPENDENCIES_COMMAND_V1',
      [s.org, s.team, s.mission, s.task],
      actor,
      body.expectedRevision,
      body.expectedFingerprint,
      canonical,
    ]),
  );
  expect(db.audit).toEqual({
    eventId: receipt.eventId,
    revision: receipt.revision,
  });
  return receipt;
}
async function command(p, s, ids, key = randomUUID(), body) {
  const c = (await (await p.request.get(s.api)).json()).data;
  const targets = [];
  for (const id of ids) {
    const r = await p.request.get(`${s.api}/eligible-dependencies/${id}`);
    expect(r.status()).toBe(200);
    const t = (await r.json()).data;
    targets.push({taskId: t.id, fingerprint: t.fingerprint});
  }
  const actualBody = body || {
    expectedRevision: c.revision,
    expectedFingerprint: c.fingerprint,
    dependencies: targets,
  };
  const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
  const r = await p.request.post(s.api, {
    headers: {[csrf.headerName]: csrf.token, 'Idempotency-Key': key},
    data: actualBody,
  });
  return {r, body: actualBody, key};
}
async function competing(p, s) {
  const c = (await (await p.request.get(s.api)).json()).data;
  const ids = c.dependencies.length ? [] : [taskId(s, 4)];
  const result = await command(p, s, ids);
  expect(result.r.status()).toBe(201);
  return (await result.r.json()).data;
}
async function fault(p, s, mode) {
  const attempts = [];
  let committed;
  await p.route(`**${s.api}`, async route => {
    if (route.request().method() !== 'POST') return route.continue();
    attempts.push({
      key: route.request().headers()['idempotency-key'],
      body: route.request().postData(),
    });
    const actual = await route.fetch();
    expect(actual.status()).toBe(attempts.length === 1 ? 201 : 200);
    const receipt = (await actual.json()).data;
    if (committed) expect(receipt).toEqual(committed);
    committed = receipt;
    expect(row(s).dependency_revision).toBe(receipt.revision);
    expect(edges(s)).toEqual(receipt.after.map(t => t.id));
    if (attempts.length > 1) return route.fulfill({response: actual});
    if (mode === 'lost') return route.abort('failed');
    return route.fulfill({response: actual, body: '{malformed'});
  });
  return {attempts, receipt: () => committed};
}
async function erased(p, secret) {
  for (const state of ['current', 'selection', 'review', 'saved'])
    await expect(hook(p, state)).toHaveCount(0);
  await expect(hook(p, 'chooser')).not.toBeVisible();
  await expect(p.locator('main')).not.toContainText(secret);
}

// Hold one original browser request after a real successful backend response.
// Releasing waits for actual response/body completion (or observed cancellation),
// then drains rendering before any late-response privacy assertion.
async function hold(p, path, method, status, committed = () => {}) {
  let arrive, arrivalError, complete, completionError, openGate;
  const seen = new Promise((resolve, reject) => {
    arrive = resolve;
    arrivalError = reject;
  });
  const done = new Promise((resolve, reject) => {
    complete = resolve;
    completionError = reject;
  });
  void seen.catch(() => {});
  void done.catch(() => {});
  const gate = new Promise(resolve => {
    openGate = resolve;
  });
  let original,
    released = false,
    releaseResult;
  const match = url => url.pathname === path;
  const handler = async route => {
    const request = route.request();
    if (original || request.method() !== method) return route.continue();
    original = request; // Capture before route.fetch or any authority change.
    try {
      const actual = await route.fetch();
      expect(actual.status()).toBe(status);
      await committed(actual);
      arrive();
      await gate;
      expect(released).toBe(true);
      try {
        await route.fulfill({response: actual});
      } catch (error) {
        // A message match alone is never evidence of cancellation. Only the
        // captured original request's actual failure permits this transport race.
        if (!released || original.failure() === null) throw error;
      }
      complete();
    } catch (error) {
      arrivalError(error);
      completionError(error);
    }
  };
  await p.route(match, handler);
  return {
    seen,
    release() {
      if (releaseResult) return releaseResult;
      released = true;
      openGate();
      releaseResult = (async () => {
        if (original) {
          await done;
          const delivered = await original.response();
          if (delivered) {
            const bodyError = await delivered.finished();
            if (bodyError && original.failure() === null) throw bodyError;
          } else {
            expect(original.failure()).not.toBeNull();
          }
        }
        await p.unroute(match, handler);
        await p.evaluate(
          () =>
            new Promise(resolve =>
              requestAnimationFrame(() => requestAnimationFrame(resolve)),
            ),
        );
      })();
      return releaseResult;
    },
  };
}

test.describe('task dependencies independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  test.beforeEach(async ({}, info) => {
    expect(info.config.workers).toBe(1);
    expect(info.project.retries).toBe(0);
    expect(info.timeout).toBe(30000);
    expect(
      process.env.E2E_PASSWORD,
      'real fixture password required',
    ).toBeTruthy();
  });

  test('01 actual loading detail entry save provenance protected state and revisit', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const s = scope(info, 19),
      before = snapshot(s);
    await p.goto(s.detail);
    const link = p.getByRole('link', {name: 'Sửa phụ thuộc', exact: true});
    await expect(link).toHaveAttribute('href', s.url);
    const held = await hold(p, s.api, 'GET', 200);
    try {
      await link.click();
      await held.seen;
      await shot(p, info, s, 'loading');
    } finally {
      await held.release();
    }
    await expect(hook(p, 'edit')).toBeVisible();
    s.source = (await (await p.request.get(s.api)).json()).data;
    await expect(
      p.getByRole('heading', {
        name: 'Sửa phụ thuộc của task draft',
        exact: true,
      }),
    ).toBeVisible();
    expect(snapshot(s)).toEqual(before);
    await shot(p, info, s, 'edit');
    await different(p, s);
    const posts = observe(p, s);
    await review(p);
    expect(posts).toHaveLength(0);
    await shot(p, info, s, 'review');
    const receipt = await save(p, s, true);
    expect(posts).toHaveLength(1);
    expect(posts[0].key).toMatch(/^[0-9a-f-]{36}$/);
    await shot(p, info, s, 'saved');
    const stable = snapshot(s);
    await hook(p, 'open-task').click();
    await expect(p).toHaveURL(s.detail);
    await p.getByRole('link', {name: 'Sửa phụ thuộc', exact: true}).click();
    await expect(hook(p, 'edit')).toBeVisible();
    expect(
      (await (await p.request.get(s.api)).json()).data.dependencies,
    ).toEqual(receipt.after);
    expect(snapshot(s)).toEqual(stable);
  });

  test('02 clear-all has complete review unchecked acknowledgment and no automatic POST', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 20);
    const setup = await command(p, s, [taskId(s, 4), taskId(s, 12)]);
    expect(setup.r.status()).toBe(201);
    await p.reload();
    await expect(hook(p, 'edit')).toBeVisible();
    const stable = snapshot(s),
      posts = observe(p, s),
      targetReads = [];
    await hook(p, 'clear-all').click();
    p.on('request', r => {
      if (r.url().includes('/eligible-dependencies/'))
        targetReads.push(r.url());
    });
    await review(p);
    await expect(hook(p, 'before')).toContainText('Task dependencies Task 4');
    await expect(hook(p, 'before')).toContainText('Task dependencies Task 12');
    await expect(hook(p, 'after')).toContainText('Không có phụ thuộc');
    expect(targetReads).toHaveLength(0);
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
    await hook(p, 'back-to-edit').click();
    await review(p);
    expect(snapshot(s)).toEqual(stable);
    const receipt = await save(p, s);
    expect(receipt.after).toEqual([]);
  });

  test('03 paginated multi-select survives search page changes and Escape restores focus', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 21),
      stable = snapshot(s),
      posts = observe(p, s);
    if (p.viewportSize().width < 1024) {
      await button(p, 'Menu').click();
      const menu = p.getByTestId('dependencies-menu');
      await expect(menu).toBeVisible();
      await expect(
        menu.getByRole('link', {name: 'Teams', exact: true}),
      ).toBeVisible();
      await p.keyboard.press('Escape');
      await expect(menu).not.toBeVisible();
      await expect(button(p, 'Menu')).toBeFocused();
    } else {
      await expect(button(p, 'Menu')).toBeHidden();
      await expect(
        p.getByRole('navigation', {name: 'Workspace navigation', exact: true}),
      ).toBeVisible();
    }
    await chooser(p);
    const first = hook(p, 'chooser')
      .locator('[data-testid^="dependency-choose-"]')
      .first();
    const id = (await first.getAttribute('data-testid')).replace(
      'dependency-choose-',
      '',
    );
    await first.click();
    await response(p, `${s.api}/eligible-dependencies`, () =>
      hook(p, 'next').click(),
    );
    const second = hook(p, 'chooser')
      .locator('[data-testid^="dependency-choose-"]')
      .first();
    const id2 = (await second.getAttribute('data-testid')).replace(
      'dependency-choose-',
      '',
    );
    expect(id2).not.toBe(id);
    await second.click();
    await hook(p, 'search').fill('Task dependencies Task 4');
    await hook(p, 'search-submit').click();
    await p.getByTestId(`dependency-choose-${taskId(s, 4)}`).click();
    await closeChooser(p);
    for (const selected of [id, id2, taskId(s, 4)])
      await expect(
        hook(p, 'selection').getByTestId(`dependency-row-${selected}`),
      ).toBeVisible();
    await review(p);
    for (const selected of [id, id2, taskId(s, 4)])
      await expect(
        hook(p, 'after').getByTestId(`dependency-row-${selected}`),
      ).toBeVisible();
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  test('04 twenty selections span pages cap adds but permit remove and complete review', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 22),
      stable = snapshot(s),
      posts = observe(p, s);
    await chooser(p);
    const selected = [];
    // First page has20 actual candidates; no fixed total assertion after other commands.
    for (const control of await hook(p, 'chooser')
      .locator('[data-testid^="dependency-choose-"]')
      .all()) {
      const id = (await control.getAttribute('data-testid')).replace(
        'dependency-choose-',
        '',
      );
      selected.push(id);
      await control.click();
    }
    expect(selected).toHaveLength(20);
    await expect(
      hook(p, 'selection').getByTestId('dependencies-count'),
    ).toHaveText('20/20 task');
    await expect(p.locator('main')).toContainText('Tối đa 20 task phụ thuộc.');
    await response(p, `${s.api}/eligible-dependencies`, () =>
      hook(p, 'next').click(),
    );
    await expect(
      hook(p, 'chooser')
        .locator('[data-testid^="dependency-candidate-"]')
        .first(),
    ).toBeVisible();
    for (const c of await hook(p, 'chooser')
      .locator('[data-testid^="dependency-choose-"]')
      .all())
      await expect(c).toBeDisabled();
    await p.keyboard.press('Escape');
    await expect(hook(p, 'add')).toBeDisabled();
    await review(p);
    expect(
      await hook(p, 'after')
        .locator('[data-testid^="dependency-row-"]')
        .count(),
    ).toBe(20);
    await hook(p, 'back-to-edit').click();
    await p.getByTestId(`dependency-remove-${selected[0]}`).click();
    await expect(hook(p, 'add')).toBeEnabled();
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  test('05 percent underscore exclamation search is literal not LIKE wildcards', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 23);
    const restore = changeTitle(s, 7, 'QC literal %_! target');
    // Reload uses the actual changed graph, not a fabricated candidate response.
    try {
      await p.reload();
      await expect(hook(p, 'edit')).toBeVisible();
      const stable = snapshot(s),
        posts = observe(p, s);
      await chooser(p);
      for (const q of ['%', '_', '!', '%_!']) {
        await hook(p, 'search').fill(q);
        const r = await response(p, `${s.api}/eligible-dependencies`, () =>
          hook(p, 'search-submit').click(),
        );
        const data = (await r.json()).data;
        expect(data.query).toBe(q);
        expect(data.items.map(x => x.id)).toEqual([taskId(s, 7)]);
        await expect(
          p.getByTestId(`dependency-candidate-${taskId(s, 7)}`),
        ).toContainText('QC literal %_! target');
      }
      await closeChooser(p);
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    } finally {
      restore();
    }
  });

  test('06 unchanged empty default no-op and blank legacy source preserve null input', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 11),
      stable = snapshot(s),
      posts = observe(p, s);
    const saved = row(s);
    expect(saved.title).toBe('');
    expect(saved.description).toBeNull();
    expect(saved.acceptance_criteria).toBeNull();
    expect(saved.priority).toBeNull();
    await expect(p.locator('main')).toContainText('Không có tiêu đề');
    await expect(hook(p, 'review-action')).toBeDisabled();
    await chooseId(p, s, 4);
    await hook(p, 'clear-all').click();
    await expect(hook(p, 'review-action')).toBeDisabled();
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
    const legacy = scope(info, 1),
      legacyOwner = row(legacy).owner_agent_id;
    expect(legacyOwner).toBe(uuid('57040000', 5001));
    const context = await p.request.get(legacy.api);
    expect(context.status()).toBe(200);
    const projection = JSON.stringify((await context.json()).data);
    expect(projection).not.toContain(legacyOwner);
    expect(projection).not.toContain('PRIVATE_TASK_DEPENDENCIES_FOREIGN');
    await chooseId(p, s, 4);
    await review(p);
    await save(p, s);
    expect(hash({...row(s), dependency_revision: 0, updated_at: null})).toBe(
      hash({...saved, dependency_revision: 0, updated_at: null}),
    );
    expect(row(legacy).owner_agent_id).toBe(legacyOwner);
  });

  test('07 all persisted non-DRAFT source states are blocked and never read candidates', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const s = scope(info),
      stable = snapshot(s),
      reads = [],
      posts = observe(p, s);
    p.on('request', r => {
      if (r.url().includes('/eligible-dependencies')) reads.push(r.url());
    });
    for (const t of [4, 12, 13, 14, 15, 16, 17, 18]) {
      const x = scope(info, t);
      await p.goto(x.url);
      await expect(hook(p, 'blocked')).toBeVisible();
      await expect(hook(p, 'review-action')).toHaveCount(0);
      await expect(hook(p, 'add')).toHaveCount(0);
      if (t === 4) await shot(p, info, x, 'blocked');
    }
    expect(reads).toHaveLength(0);
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  test('08 malformed actual context is error not empty success then explicit reload', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    const s = scope(info, 24),
      stable = snapshot(s),
      posts = observe(p, s);
    const match = url => url.pathname === s.api;
    await p.route(match, async route => {
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      await route.fulfill({response: actual, body: '{malformed'});
    });
    await p.goto(s.url);
    await expect(hook(p, 'error')).toBeVisible();
    await expect(hook(p, 'current')).toHaveCount(0);
    await expect(hook(p, 'saved')).toHaveCount(0);
    await p.unroute(match);
    await button(p, 'Thử tải lại').click();
    await expect(hook(p, 'edit')).toBeVisible();
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  test('09 genuine empty candidate result differs from loading malformed and blocked', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 24),
      stable = snapshot(s),
      posts = observe(p, s);
    await chooser(p);
    await hook(p, 'search').fill('qc-no-such-dependency-570');
    const r = await response(p, `${s.api}/eligible-dependencies`, () =>
      hook(p, 'search-submit').click(),
    );
    expect((await r.json()).data.items).toEqual([]);
    await expect(hook(p, 'empty-candidates')).toHaveText(
      'Không tìm thấy task phù hợp.',
    );
    await expect(hook(p, 'error')).toHaveCount(0);
    await closeChooser(p);
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  test('10 native Back reused-route discard and beforeunload have one listener owner', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 25);
    await p.goto(s.detail);
    await p.getByRole('link', {name: 'Sửa phụ thuộc', exact: true}).click();
    await expect(hook(p, 'edit')).toBeVisible();
    await chooseId(p, s, 4);
    const stable = snapshot(s),
      posts = observe(p, s);
    // This is native Back; the reused-parameter check below is separately synthetic.
    await p.evaluate(() => history.back());
    await expect(hook(p, 'discard-dialog')).toBeVisible();
    await expect(hook(p, 'discard-dialog')).toContainText(
      'Bỏ thay đổi phụ thuộc chưa lưu?',
    );
    await button(p, 'Tiếp tục chỉnh sửa').click();
    await expect(p).toHaveURL(s.url);
    await expect(hook(p, 'selection')).toContainText(
      'Task dependencies Task 4',
    );
    let nativePrompts = 0;
    const dismiss = async d => {
      nativePrompts++;
      expect(d.type()).toBe('beforeunload');
      await d.dismiss();
    };
    p.on('dialog', dismiss);
    const unloaded = p.waitForEvent('dialog');
    await p.evaluate(() => location.reload());
    await unloaded;
    p.off('dialog', dismiss);
    expect(nativePrompts).toBe(1);
    const other = scope(info, 26);
    await pushSpaHistory(p, other.url);
    await expect(hook(p, 'discard-dialog')).toBeVisible();
    await button(p, 'Bỏ thay đổi').click();
    await expect(p).toHaveURL(other.url);
    await expect(hook(p, 'edit')).toBeVisible();
    await expect(hook(p, 'selection')).not.toContainText(
      'Task dependencies Task 4',
    );
    // Old component must not retain a beforeunload listener on a clean sibling.
    const unexpected = d => {
      nativePrompts++;
      void d.dismiss();
    };
    p.on('dialog', unexpected);
    await p.reload();
    p.off('dialog', unexpected);
    expect(nativePrompts).toBe(1);
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  for (const [stage, t] of [
    ['review', 26],
    ['save', 27],
  ])
    test(`11/12 real source competitor makes ${stage} stale without browser POST`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info, t),
        posts = observe(p, s);
      await different(p, s);
      if (stage === 'save') await review(p);
      const winner = await competing(p, s),
        stable = snapshot(s);
      await hook(p, stage === 'save' ? 'save' : 'review-action').click();
      await expect(hook(p, 'conflict')).toBeVisible();
      await expect(hook(p, 'saved')).toHaveCount(0);
      await expect(hook(p, 'review')).toHaveCount(0);
      expect(posts).toHaveLength(0);
      expect(row(s).dependency_revision).toBe(winner.revision);
      expect(snapshot(s)).toEqual(stable);
      if (stage === 'save') await shot(p, info, s, 'conflict');
    });

  test('13 target-only preflight detects fresh candidate mismatch after unchanged source read', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 28),
      posts = observe(p, s);
    await chooseId(p, s, 4);
    await review(p);
    const path = `${s.api}/eligible-dependencies/${taskId(s, 4)}`;
    const previous = (await (await p.request.get(path)).json()).data;
    let restore,
      stable,
      delivered = 0;
    const match = url => url.pathname === path;
    await p.route(match, async route => {
      // Named controlled setup AFTER source preflight but BEFORE actual target GET.
      restore = changeTitle(s, 4, 'Changed chosen target QC');
      stable = snapshot(s);
      const actual = await route.fetch();
      expect(actual.status()).toBe(200);
      expect((await actual.json()).data.fingerprint).not.toBe(
        previous.fingerprint,
      );
      delivered++;
      await route.fulfill({response: actual});
    });
    try {
      await hook(p, 'save').click();
      await expect(hook(p, 'conflict')).toBeVisible();
      await expect(hook(p, 'selection')).toContainText(
        'Task dependencies Task 4',
      );
      expect(delivered).toBe(1);
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    } finally {
      await p.unroute(match);
      if (restore) restore();
    }
  });

  test('14 disconnected off-path node metadata invalidates save and preserves draft', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 29),
      posts = observe(p, s);
    await chooseId(p, s, 4);
    await review(p);
    const restore = changeTitle(s, 7, 'Disconnected off-path QC mutation');
    try {
      const stable = snapshot(s);
      await hook(p, 'save').click();
      await expect(hook(p, 'conflict')).toBeVisible();
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    } finally {
      restore();
    }
  });

  test('15 twenty retained selections hydrate by ID independently of displayed page', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 30);
    const list = (
      await (
        await p.request.get(`${s.api}/eligible-dependencies?page=0&query=`)
      ).json()
    ).data;
    expect(list.items.length).toBe(20);
    const ids = [
      1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
    ].map(t => taskId(s, t));
    expect(ids).toHaveLength(20);
    const setup = await command(p, s, ids);
    expect(setup.r.status()).toBe(201);
    const reads = [],
      stable = snapshot(s),
      posts = observe(p, s);
    p.on('request', r => {
      const path = new URL(r.url()).pathname;
      if (path.startsWith(`${s.api}/eligible-dependencies/`))
        reads.push(path.split('/').at(-1));
    });
    await p.reload();
    await expect(hook(p, 'edit')).toBeVisible();
    expect([...new Set(reads)].sort()).toEqual([...ids].sort());
    const current = (await (await p.request.get(s.api)).json()).data;
    expect(
      [...new Set(current.dependencies.map(t => t.status))].sort(),
    ).toEqual(
      [
        'DRAFT',
        'READY',
        'RUNNING',
        'WAITING',
        'BLOCKED',
        'IN_REVIEW',
        'COMPLETED',
        'FAILED',
        'CANCELLED',
      ].sort(),
    );
    await expect(hook(p, 'selection')).toContainText('Không có tiêu đề');
    await expect(p.locator('main')).not.toContainText(
      'PRIVATE_TASK_DEPENDENCIES_FOREIGN',
    );
    expect(
      await hook(p, 'selection')
        .locator('[data-testid^="dependency-row-"]')
        .count(),
    ).toBe(20);
    await p.getByTestId(`dependency-remove-${ids[0]}`).click();
    await review(p);
    for (const id of ids.slice(1))
      await expect(
        hook(p, 'after').getByTestId(`dependency-row-${id}`),
      ).toBeVisible();
    expect(posts).toHaveLength(0);
    expect(snapshot(s)).toEqual(stable);
  });

  for (const [mode, recovery, t] of [
    ['lost', 'retry', 31],
    ['malformed', 'receipt', 32],
  ])
    test(`16/17 committed ${mode} response UNKNOWN explicit ${recovery} proves exact recovery`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info, t),
        protectedState = snapshot(s, true),
        counts = provenance(s);
      await different(p, s);
      await review(p);
      const f = await fault(p, s, mode);
      await hook(p, 'save').click();
      await expect(hook(p, 'unknown')).toBeVisible();
      await expect(hook(p, 'receipt-check')).toBeEnabled();
      expect(f.attempts).toHaveLength(1);
      const stable = snapshot(s),
        first = f.attempts[0],
        body = JSON.parse(first.body);
      expect(Object.keys(body).sort()).toEqual([
        'dependencies',
        'expectedFingerprint',
        'expectedRevision',
      ]);
      expect(body.expectedFingerprint).toBe(s.source.fingerprint);
      expect(body.expectedRevision).toBe(s.source.revision);
      expect(body.dependencies.map(x => x.taskId)).toEqual(
        f.receipt().after.map(x => x.id),
      );
      for (const x of body.dependencies)
        expect(x.fingerprint).toMatch(/^[a-f0-9]{64}$/);
      if (mode === 'lost') await shot(p, info, s, 'unknown');
      if (recovery === 'retry') {
        const r = await response(
          p,
          s.api,
          () => hook(p, 'exact-retry').click(),
          'POST',
        );
        expect(r.status()).toBe(200);
        expect(f.attempts).toEqual([first, first]);
      } else {
        const r = await response(p, `${s.api}/receipts/${first.key}`, () =>
          hook(p, 'receipt-check').click(),
        );
        expect((await r.json()).data).toEqual({receipt: f.receipt()});
        expect(f.attempts).toHaveLength(1);
      }
      await expect(hook(p, 'saved')).toBeVisible();
      expect(snapshot(s)).toEqual(stable);
      expect(snapshot(s, true)).toEqual(protectedState);
      expect(provenance(s)).toEqual({
        events: counts.events + 1,
        receipts: counts.receipts + 1,
        audit: counts.audit + 1,
      });
      const storage = await p.evaluate(() =>
        JSON.stringify({
          local: {...localStorage},
          session: {...sessionStorage},
        }),
      );
      expect(storage).not.toContain(first.key);
      expect(storage).not.toContain(first.body);
    });

  test('18 undelivered POST has actual null receipt and stays UNKNOWN without automatic retry', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 33);
    await chooseId(p, s, 4);
    await review(p);
    const stable = snapshot(s),
      posts = observe(p, s);
    await p.route(`**${s.api}`, route =>
      route.request().method() === 'POST'
        ? route.abort('failed')
        : route.continue(),
    );
    await hook(p, 'save').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    await expect(hook(p, 'receipt-check')).toBeEnabled();
    expect(posts).toHaveLength(1);
    const path = `${s.api}/receipts/${posts[0].key}`;
    const r = await response(p, path, () => hook(p, 'receipt-check').click());
    expect(r.status()).toBe(200);
    expect((await r.json()).data).toEqual({receipt: null});
    await expect(hook(p, 'unknown')).toBeVisible();
    await expect(hook(p, 'saved')).toHaveCount(0);
    await expect(hook(p, 'exact-retry')).toBeEnabled();
    expect(posts).toHaveLength(1);
    expect(snapshot(s)).toEqual(stable);
  });

  for (const kind of ['member', 'other'])
    test(`19/20 current ${kind} has no editor authority including malformed read and receipt`, async ({
      page: p,
    }, info) => {
      await authenticate(p, info, kind);
      const s = scope(info),
        stable = snapshot(s),
        posts = observe(p, s);
      if (kind === 'member') {
        await p.goto(s.detail);
        await expect(
          p.getByRole('link', {name: 'Sửa phụ thuộc', exact: true}),
        ).toHaveCount(0);
      }
      for (const path of [
        s.api,
        `${s.api}/eligible-dependencies?page=bad`,
        `${s.api}/eligible-dependencies/${taskId(s, 4)}`,
        `${s.api}/receipts/${randomUUID()}`,
      ])
        expect((await p.request.get(path)).status()).toBe(403);
      await p.goto(s.url);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, 'Task dependencies Task 4');
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    });

  for (const fence of ['route', 'role', 'actor'])
    test(`21/22/23 held actual committed response drains after ${fence} privacy fence`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info, 34),
        posts = observe(p, s);
      await different(p, s);
      await review(p);
      const held = await hold(p, s.api, 'POST', 201, async actual => {
        const receipt = (await actual.json()).data;
        expect(row(s).dependency_revision).toBe(receipt.revision);
        expect(edges(s)).toEqual(receipt.after.map(t => t.id));
      });
      let sibling;
      try {
        await hook(p, 'save').click();
        await held.seen;
        const stable = snapshot(s);
        if (fence === 'role') {
          membership(s, info, 'MEMBER');
          await focus(p);
          await expect(hook(p, 'exact-retry')).toBeDisabled();
          await erased(p, s.source.taskTitle);
        } else if (fence === 'actor') {
          sibling = await p.context().newPage();
          const csrf = (
            await (await sibling.request.get('/api/v1/auth/csrf')).json()
          ).data;
          expect([200, 204]).toContain(
            (
              await sibling.request.post('/api/v1/auth/logout', {
                headers: {[csrf.headerName]: csrf.token},
              })
            ).status(),
          );
          await authenticate(sibling, info, 'admin2');
          await response(p, '/api/v1/auth/session', () => focus(p));
          await erased(p, s.source.taskTitle);
        } else {
          const other = scope(info, 35);
          await pushSpaHistory(p, other.url);
          await expect(hook(p, 'discard-dialog')).toBeVisible();
          await expect(hook(p, 'discard-dialog')).toContainText(
            'Kết quả lưu chưa rõ.',
          );
          await button(p, 'Rời trang').click();
          await expect(p).toHaveURL(other.url);
          await expect(hook(p, 'edit')).toBeVisible();
        }
        await held.release();
        await expect(hook(p, 'saved')).toHaveCount(0);
        if (fence !== 'route') await erased(p, s.source.taskTitle);
        expect(posts).toHaveLength(1);
        if (fence === 'role') membership(s, info, 'ADMIN');
        expect(snapshot(s)).toEqual(stable);
      } finally {
        await held.release();
        if (fence === 'role') membership(s, info, 'ADMIN');
        if (sibling) await sibling.close();
      }
    });

  test('24 current ADMIN loss before review and save erases draft without command', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 35),
      posts = observe(p, s);
    await chooseId(p, s, 4);
    const baseline = snapshot(s);
    try {
      membership(s, info, 'MEMBER');
      const stable = snapshot(s);
      await hook(p, 'review-action').click();
      await erased(p, s.source.taskTitle);
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    } finally {
      membership(s, info, 'ADMIN');
    }
    expect(snapshot(s)).toEqual(baseline);
    await p.reload();
    await expect(hook(p, 'edit')).toBeVisible();
    await chooseId(p, s, 4);
    await review(p);
    try {
      membership(s, info, 'MEMBER');
      const stable = snapshot(s);
      await hook(p, 'save').click();
      await erased(p, s.source.taskTitle);
      expect(posts).toHaveLength(0);
      expect(snapshot(s)).toEqual(stable);
    } finally {
      membership(s, info, 'ADMIN');
    }
  });

  test('25 real receipt actor/key correlation changed body rejects replay has no extra effects', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 19);
    const c = (await (await p.request.get(s.api)).json()).data;
    const first = await command(
      p,
      s,
      c.dependencies.length ? [] : [taskId(s, 4)],
    );
    expect(first.r.status()).toBe(201);
    const receipt = (await first.r.json()).data,
      stable = snapshot(s);
    const replay = await command(p, s, [], first.key, first.body);
    expect(replay.r.status()).toBe(200);
    expect((await replay.r.json()).data).toEqual(receipt);
    const changed = structuredClone(first.body);
    changed.expectedFingerprint = '0'.repeat(64);
    expect((await command(p, s, [], first.key, changed)).r.status()).toBe(409);
    expect((await command(p, s, [], randomUUID(), first.body)).r.status()).toBe(
      409,
    );
    const own = await p.request.get(`${s.api}/receipts/${first.key}`);
    expect((await own.json()).data).toEqual({receipt});
    // Replace session with another real ADMIN using the existing auth API.
    const csrf = (await (await p.request.get('/api/v1/auth/csrf')).json()).data;
    await p.request.post('/api/v1/auth/logout', {
      headers: {[csrf.headerName]: csrf.token},
    });
    await authenticate(p, info, 'admin2');
    expect(
      (await (await p.request.get(`${s.api}/receipts/${first.key}`)).json())
        .data,
    ).toEqual({receipt: null});
    expect(snapshot(s)).toEqual(stable);
  });
});
