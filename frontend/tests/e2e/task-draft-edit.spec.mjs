import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';
import {mkdirSync, appendFileSync} from 'node:fs';
import {join} from 'node:path';
import {pushSpaHistory} from './spa-history.mjs';

const uuid = (p, n) => `${p}-0000-4000-8000-${String(n).padStart(12, '0')}`;
const field = (p, name) => p.getByLabel(name, {exact: true});
const button = (p, name) => p.getByRole('button', {name, exact: true});
const hook = (p, state) => p.getByTestId(`task-edit-${state}`);
const ack = p => field(p, 'Tôi đã xem lại thay đổi của task draft.');
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
function scope(info, t = 1) {
  const n = {desktop: 2, tablet: 3, mobile: 4}[info.project.name];
  if (!n) throw Error(`Unsupported project ${info.project.name}`);
  const org = uuid('51000000', n),
    team = uuid('51010000', n * 100 + (t === 8 ? 2 : 1));
  const mission = uuid('51020000', n * 100 + ({6: 2, 7: 3, 8: 4}[t] || 1));
  const task = uuid('51030000', n * 1000 + t);
  const path = `/teams/${team}/missions/${mission}/tasks/${task}`;
  return {
    n,
    org,
    team,
    mission,
    task,
    api: `/api/v1/organizations/${org}${path}/draft-settings`,
    url: `/workspace/${org}${path}/edit`,
    detail: `/workspace/${org}${path}`,
  };
}
async function response(p, path, action, method = 'GET') {
  const result = p.waitForResponse(
    r => new URL(r.url()).pathname === path && r.request().method() === method,
  );
  await action();
  return result;
}
async function authenticate(p, info, kind = 'admin') {
  await p.goto('/login');
  await field(p, 'Email').fill(
    `task-edit-${info.project.name}-${kind}@example.test`,
  );
  await field(p, 'Mật khẩu').fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, info, t = 1) {
  await authenticate(p, info);
  const s = scope(info, t);
  const r = await response(p, s.api, () => p.goto(s.url));
  expect(r.status()).toBe(200);
  s.source = (await r.json()).data;
  return s;
}
function observe(p, s) {
  const posts = [];
  p.on('request', r => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === s.api)
      posts.push(r);
  });
  return posts;
}
async function shot(p, info, state) {
  expect(
    await p.evaluate(() => document.documentElement.scrollWidth <= innerWidth),
  ).toBe(true);
  const dir = process.env.E2E_VISUAL_DIR || 'qa-visuals';
  mkdirSync(dir, {recursive: true});
  const file = `task-draft-edit-${info.project.name}-${state}.png`;
  await p.screenshot({path: join(dir, file), fullPage: true});
  // Append only after the actual state assertion and successful capture.
  appendFileSync(
    join(dir, 'task-draft-edit-manifest.jsonl'),
    JSON.stringify({
      project: info.project.name,
      width: p.viewportSize().width,
      state,
      file,
      test: info.title,
    }) + '\n',
  );
}
async function change(p, info) {
  const desired = {
    title: `QC ${info.project.name} ${test.info().testId} ${Date.now()}`,
    description: 'Actual persisted input\n<script>text only</script>',
    acceptanceCriteria: 'Actual reviewed acceptance',
    priority: 'HIGH',
  };
  await field(p, 'Title').fill(desired.title);
  await field(p, 'Description / input').fill(desired.description);
  await field(p, 'Acceptance criteria').fill(desired.acceptanceCriteria);
  await field(p, 'Priority').selectOption(desired.priority);
  return desired;
}
async function review(p) {
  await button(p, 'Xem lại thay đổi').click();
  await expect(hook(p, 'review')).toBeVisible();
  await expect(ack(p)).not.toBeChecked();
  await expect(button(p, 'Lưu thay đổi')).toBeDisabled();
  await ack(p).check();
}
function row(s) {
  return JSON.parse(
    sql(`SELECT row_to_json(t) FROM tasks t WHERE id='${s.task}';`),
  );
}
function invariants(s) {
  return sql(`SELECT row_to_json(t)::jsonb - ARRAY['title','description','acceptance_criteria','priority','updated_at','task_edit_revision'] FROM tasks t WHERE id='${s.task}';
SELECT row_to_json(d) FROM task_dependencies d WHERE organization_id='${s.org}' ORDER BY task_id,dependency_task_id;
SELECT row_to_json(m) FROM missions m WHERE organization_id='${s.org}' ORDER BY id;
SELECT row_to_json(a) FROM agents a WHERE organization_id='${s.org}' ORDER BY id;`);
}
function membership(s, info, role) {
  sql(
    `UPDATE organization_memberships SET role='${role}' WHERE organization_id='${s.org}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-edit-${info.project.name}-admin@example.test');`,
  );
}
async function focus(p) {
  await p.evaluate(() => window.dispatchEvent(new Event('focus')));
}
async function erased(p, secret) {
  await expect(field(p, 'Title')).toHaveCount(0);
  await expect(hook(p, 'review')).toHaveCount(0);
  await expect(hook(p, 'saved')).toHaveCount(0);
  await expect(p.locator('main')).not.toContainText(secret);
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
    expect(row(s).task_edit_revision).toBe(receipt.revision);
    if (attempts.length > 1) return route.fulfill({response: actual});
    if (mode === 'lost') return route.abort('failed');
    return route.fulfill({response: actual, body: '{malformed'});
  });
  return {attempts, receipt: () => committed};
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

test.describe('task draft edit independent QC', () => {
  test.describe.configure({mode: 'default', retries: 0});
  test.beforeEach(async ({}, info) => {
    expect(info.config.workers).toBe(1);
  });

  test('real four-field persistence preserves owner dependencies lifecycle intake and reloads', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      before = row(s),
      stable = invariants(s);
    await expect(p.getByTestId('task-draft-edit')).toBeVisible();
    await expect(field(p, 'Title')).toHaveValue(s.source.values.title);
    await shot(p, info, 'edit');
    const desired = await change(p, info);
    await review(p);
    for (const value of [
      s.source.values.title,
      desired.title,
      desired.description,
      desired.acceptanceCriteria,
    ])
      await expect(hook(p, 'review')).toContainText(value);
    await shot(p, info, 'review');
    const saved = await response(
      p,
      s.api,
      () => button(p, 'Lưu thay đổi').click(),
      'POST',
    );
    expect(saved.status()).toBe(201);
    const receipt = (await saved.json()).data;
    expect(receipt.before).toEqual(s.source.values);
    expect(receipt.after).toEqual(desired);
    await expect(hook(p, 'saved')).toBeVisible();
    await expect(
      p.getByText('Đã ghi nhận sửa task draft', {exact: true}),
    ).toBeVisible();
    await shot(p, info, 'saved');
    expect(invariants(s)).toBe(stable);
    const after = row(s);
    expect(after.task_edit_revision).toBe(before.task_edit_revision + 1);
    expect([
      after.title,
      after.description,
      after.acceptance_criteria,
      after.priority,
    ]).toEqual(Object.values(desired));
    await p.getByRole('link', {name: 'Mở task', exact: true}).click();
    await expect(p).toHaveURL(s.detail);
    await expect(p.locator('main')).toContainText(desired.title);
    await p.reload();
    await expect(p.locator('main')).toContainText(desired.title);
  });

  test('legacy nulls stay blank and require explicit completion before persistence', async ({
    page: p,
  }, info) => {
    const s = await open(p, info, 2),
      posts = observe(p, s);
    // Run on a fresh disposable fixture: a prior successful run must not masquerade as legacy coverage.
    expect(s.source.values).toMatchObject({
      description: null,
      acceptanceCriteria: null,
      priority: null,
    });
    for (const label of [
      'Description / input',
      'Acceptance criteria',
      'Priority',
    ])
      await expect(field(p, label)).toHaveValue('');
    await shot(p, info, 'legacy');
    await button(p, 'Xem lại thay đổi').click();
    await expect(hook(p, 'review')).toHaveCount(0);
    expect(posts).toHaveLength(0);
    await change(p, info);
    await review(p);
    await expect(hook(p, 'review')).toContainText('Chưa có dữ liệu');
    const r = await response(
      p,
      s.api,
      () => button(p, 'Lưu thay đổi').click(),
      'POST',
    );
    expect(r.status()).toBe(201);
    expect((await r.json()).data.before).toEqual(s.source.values);
    await expect(hook(p, 'saved')).toBeVisible();
  });

  test('all twelve blocked fixture states fail closed with zero writes', async ({
    page: p,
  }, info) => {
    await authenticate(p, info);
    let writes = 0;
    p.on('request', r => {
      if (r.method() === 'POST' && r.url().includes('/draft-settings'))
        writes++;
    });
    for (const t of [3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14]) {
      const s = scope(info, t),
        before = row(s);
      const r = await response(p, s.api, () => p.goto(s.url));
      expect(r.status()).toBe(200);
      const c = (await r.json()).data;
      expect(c.canEdit).toBe(false);
      expect(c.blockers.length).toBeGreaterThan(0);
      await expect(hook(p, 'blocked')).toBeVisible();
      await expect(field(p, 'Title')).toBeDisabled();
      if (t === 3) await shot(p, info, 'blocked');
      expect(row(s)).toEqual(before);
    }
    expect(writes).toBe(0);
  });

  test('no-op and required bounds reject review without POST', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s),
      before = row(s);
    await button(p, 'Xem lại thay đổi').click();
    await expect(hook(p, 'review')).toHaveCount(0);
    for (const [label, values] of [
      ['Title', ['', ' ', 'x'.repeat(256)]],
      ['Description / input', ['', 'x'.repeat(8001)]],
      ['Acceptance criteria', ['', 'x'.repeat(4001)]],
    ]) {
      const original = await field(p, label).inputValue();
      for (const value of values) {
        await field(p, label).fill(value);
        await button(p, 'Xem lại thay đổi').click();
        await expect(hook(p, 'review')).toHaveCount(0);
      }
      await field(p, label).fill(original);
    }
    expect(posts).toHaveLength(0);
    expect(row(s)).toEqual(before);
  });

  test('review acknowledgement resets on edit and focus without writes', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await change(p, info);
    await review(p);
    await button(p, 'Quay lại chỉnh sửa').click();
    await review(p);
    await focus(p);
    await expect(ack(p)).not.toBeChecked();
    await expect(button(p, 'Lưu thay đổi')).toBeDisabled();
    expect(posts).toHaveLength(0);
  });

  test('dirty native Back reload and reused task identity require discard', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await p.goto(s.detail);
    await p.getByRole('link', {name: 'Sửa task draft', exact: true}).click();
    await expect(field(p, 'Title')).toBeVisible();
    await field(p, 'Title').fill('Private dirty navigation draft');
    const dialog = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('confirm');
      await d.dismiss();
    });
    await p.evaluate(() => history.back());
    await dialog;
    await expect(p).toHaveURL(s.url);
    await expect(field(p, 'Title')).toHaveValue(
      'Private dirty navigation draft',
    );
    const reload = p.waitForEvent('dialog').then(async d => {
      expect(d.type()).toBe('beforeunload');
      await d.dismiss();
    });
    await p.evaluate(() => location.reload());
    await reload;
    const other = scope(info, 2);
    p.once('dialog', d => d.dismiss());
    await pushSpaHistory(p, other.url);
    await expect(p).toHaveURL(s.url);
    p.once('dialog', d => d.accept());
    await response(p, other.api, () => pushSpaHistory(p, other.url));
    await expect(p).toHaveURL(other.url);
    await expect(field(p, 'Title')).not.toHaveValue(
      'Private dirty navigation draft',
    );
    expect(posts).toHaveLength(0);
  });

  test('actual PG preflight change preserves draft and prevents POST', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    await field(p, 'Title').fill('Private stale preflight draft');
    await review(p);
    const old = sql(
      `SELECT task_intake_version FROM missions WHERE id='${s.mission}';`,
    );
    try {
      sql(
        `UPDATE missions SET task_intake_version=task_intake_version+1 WHERE id='${s.mission}';`,
      );
      await button(p, 'Lưu thay đổi').click();
      await expect(hook(p, 'error')).toBeVisible();
      await expect(field(p, 'Title')).toHaveValue(
        'Private stale preflight draft',
      );
      await shot(p, info, 'conflict');
      expect(posts).toHaveLength(0);
    } finally {
      sql(
        `UPDATE missions SET task_intake_version=${Number(old)} WHERE id='${s.mission}';`,
      );
    }
  });

  for (const mode of ['lost', 'malformed'])
    test(`committed ${mode} success freezes UNKNOWN and manual receipt recovers`, async ({
      page: p,
    }, info) => {
      const s = await open(p, info),
        stable = invariants(s);
      await change(p, info);
      await review(p);
      const f = await fault(p, s, mode);
      await button(p, 'Lưu thay đổi').click();
      await expect(hook(p, 'unknown')).toBeVisible();
      expect(f.attempts).toHaveLength(1);
      await expect(field(p, 'Title')).toHaveCount(0);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeEnabled();
      if (mode === 'lost') await shot(p, info, 'unknown');
      const key = f.attempts[0].key;
      if (mode === 'lost') {
        const receiptPattern = `**${s.api}/receipts/${key}`;
        await p.route(receiptPattern, async route => {
          const actual = await route.fetch();
          expect(actual.status()).toBe(200);
          expect((await actual.json()).data).toEqual({receipt: f.receipt()});
          await route.fulfill({response: actual, body: '{malformed'});
        });
        await response(p, `${s.api}/receipts/${key}`, () =>
          button(p, 'Kiểm tra biên nhận').click(),
        );
        await expect(hook(p, 'unknown')).toBeVisible();
        await expect(hook(p, 'saved')).toHaveCount(0);
        expect(f.attempts).toHaveLength(1);
        await p.unroute(receiptPattern);
      }
      const r = await response(p, `${s.api}/receipts/${key}`, () =>
        button(p, 'Kiểm tra biên nhận').click(),
      );
      expect((await r.json()).data).toEqual({receipt: f.receipt()});
      await expect(hook(p, 'saved')).toBeVisible();
      expect(f.attempts).toHaveLength(1);
      expect(invariants(s)).toBe(stable);
      if (mode === 'lost') await shot(p, info, 'recovered');
    });

  test('UNKNOWN explicit retry reuses exact immutable body and key once', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    await change(p, info);
    await review(p);
    const f = await fault(p, s, 'lost');
    await button(p, 'Lưu thay đổi').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    const committed = row(s);
    const r = await response(
      p,
      s.api,
      () => button(p, 'Gửi lại đúng yêu cầu này').click(),
      'POST',
    );
    expect(r.status()).toBe(200);
    await expect(hook(p, 'saved')).toBeVisible();
    expect(f.attempts).toHaveLength(2);
    expect(f.attempts[1]).toEqual(f.attempts[0]);
    expect(row(s)).toEqual(committed);
  });

  test('held committed late write cannot restore private UI after role loss', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    const desired = await change(p, info);
    await review(p);
    const posts = observe(p, s);
    const held = await hold(p, s.api, 'POST', 201, () => {
      expect(row(s).title).toBe(desired.title);
    });
    try {
      await button(p, 'Lưu thay đổi').click();
      await held.seen;
      membership(s, info, 'MEMBER');
      await focus(p);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, desired.title);
      await shot(p, info, 'denied');
      await held.release();
      await erased(p, desired.title);
      expect(posts).toHaveLength(1);
    } finally {
      try {
        await held.release();
      } finally {
        membership(s, info, 'ADMIN');
      }
    }
  });

  test('held real context read cannot restore private UI after actor swap', async ({
    page: p,
  }, info) => {
    const s = await open(p, info);
    const secret = s.source.values.title;
    const held = await hold(p, s.api, 'GET', 200);
    const sibling = await p.context().newPage();
    try {
      await focus(p);
      await held.seen;
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
      await authenticate(sibling, info, 'other');
      await response(p, '/api/v1/auth/session', () => focus(p));
      await erased(p, secret);
      await held.release();
      await erased(p, secret);
    } finally {
      try {
        await held.release();
      } finally {
        await sibling.close();
      }
    }
  });

  test('UNKNOWN role loss erases body and permits only explicit receipt after restoration', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      desired = await change(p, info);
    await review(p);
    const f = await fault(p, s, 'lost');
    await button(p, 'Lưu thay đổi').click();
    await expect(hook(p, 'unknown')).toBeVisible();
    try {
      membership(s, info, 'MEMBER');
      await focus(p);
      await expect(hook(p, 'denied')).toBeVisible();
      await erased(p, desired.title);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
      membership(s, info, 'ADMIN');
      const r = await response(
        p,
        `${s.api}/receipts/${f.attempts[0].key}`,
        () => button(p, 'Kiểm tra biên nhận').click(),
      );
      expect((await r.json()).data).toEqual({receipt: f.receipt()});
      await expect(hook(p, 'saved')).toBeVisible();
      expect(f.attempts).toHaveLength(1);
    } finally {
      membership(s, info, 'ADMIN');
    }
  });

  test('real GET malformed and unavailable responses clear returned private data', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      posts = observe(p, s);
    for (const mode of ['malformed', 'unavailable']) {
      const secret = `Private read draft ${mode}`;
      await field(p, 'Title').fill(secret);
      await review(p);
      await p.route(`**${s.api}`, async route => {
        if (route.request().method() !== 'GET') return route.continue();
        const actual = await route.fetch();
        expect(actual.status()).toBe(200);
        await route.fulfill(
          mode === 'malformed'
            ? {response: actual, body: '{malformed'}
            : {response: actual, status: 503, body: '{}'},
        );
      });
      await response(p, s.api, () => focus(p));
      await expect(hook(p, 'error')).toBeVisible();
      await expect(field(p, 'Title')).toHaveCount(0);
      await expect(hook(p, 'review')).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(secret);
      await expect(hook(p, 'denied')).toHaveCount(0);
      await p.unroute(`**${s.api}`);
      p.once('dialog', d => d.accept());
      await response(p, s.api, () => button(p, 'Tải lại dữ liệu').click());
      await expect(field(p, 'Title')).toHaveValue(s.source.values.title);
    }
    expect(posts).toHaveLength(0);
  });

  test('held committed late write is fenced after shared-session actor swap', async ({
    page: p,
  }, info) => {
    const s = await open(p, info),
      desired = await change(p, info);
    await review(p);
    const posts = observe(p, s);
    const held = await hold(p, s.api, 'POST', 201, () => {
      expect(row(s).title).toBe(desired.title);
    });
    const sibling = await p.context().newPage();
    try {
      await button(p, 'Lưu thay đổi').click();
      await held.seen;
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
      await authenticate(sibling, info, 'other');
      await response(p, '/api/v1/auth/session', () => focus(p));
      await erased(p, desired.title);
      await held.release();
      await erased(p, desired.title);
      expect(posts).toHaveLength(1);
      await expect(button(p, 'Gửi lại đúng yêu cầu này')).toBeDisabled();
    } finally {
      try {
        await held.release();
      } finally {
        await sibling.close();
      }
    }
  });
});
