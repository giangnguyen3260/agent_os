// Execute the production TypeScript validators and state machine, not test copies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const signal = initial => {
  let v = initial;
  const s = () => v;
  s.set = x => {
    v = x;
  };
  return s;
};
class HttpErrorResponse extends Error {
  constructor(status) {
    super('HTTP');
    this.status = status;
  }
}
class AuthFailure extends Error {
  constructor(kind) {
    super(kind);
    this.kind = kind;
  }
}
const cache = {};
let prompts = 0;
let injection;
function load(name) {
  if (cache[name]) return cache[name];
  const mod = {exports: {}};
  const code = ts.transpileModule(
    fs.readFileSync(
      path.join(__dirname, '../src/app/features/task-edit', name + '.ts'),
      'utf8',
    ),
    {
      compilerOptions: {
        target: ts.ScriptTarget.ES2022,
        module: ts.ModuleKind.CommonJS,
        experimentalDecorators: true,
      },
    },
  ).outputText;
  vm.runInNewContext(code, {
    module: mod,
    exports: mod.exports,
    TextEncoder,
    Date,
    Reflect,
    JSON,
    Number,
    Set,
    Map,
    structuredClone,
    crypto: require('node:crypto').webcrypto,
    queueMicrotask,
    window: {
      confirm: () => {
        prompts++;
        return true;
      },
    },
    require: key => {
      if (key.startsWith('./task-')) return load(key.slice(2));
      if (key === '@angular/core')
        return {
          Component: () => x => x,
          Injectable: () => x => x,
          HostListener: () => () => {},
          signal,
          inject: () => injection,
        };
      if (key === '@angular/common/http') return {HttpErrorResponse};
      if (key === '../../core/auth/auth.service') return {AuthFailure};
      return {};
    },
  });
  return (cache[name] = mod.exports);
}
const v = load('task-edit-validation');
const C = load('task-draft-edit.component').TaskDraftEditComponent;
const id = n => '80800000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {
  organizationId: id(1),
  teamId: id(2),
  missionId: id(3),
  taskId: id(4),
};
const old = {
  title: 'Private title',
  description: 'Private description',
  acceptanceCriteria: 'Private criteria',
  priority: 'NORMAL',
};
const context = {
  ...scope,
  checkedAt: '2026-10-27T00:00:00Z',
  revision: 0,
  fingerprint: 'a'.repeat(64),
  teamName: 'Team',
  missionTitle: 'Mission',
  taskStatus: 'DRAFT',
  values: old,
  canEdit: true,
  blockers: [],
};
const body = {
  expectedRevision: 0,
  expectedFingerprint: context.fingerprint,
  ...old,
  title: 'Changed title',
};
const receipt = {
  ...scope,
  eventId: id(7),
  revision: 1,
  committedAt: '2026-10-27T00:00:00.123456789Z',
  before: old,
  after: {...old, title: body.title},
};
assert(v.validContext(context, scope));
assert(v.validBody(body));
assert(v.validReceipt(receipt, scope, body, old));
for (const value of [
  null,
  [],
  {},
  {...context, unexpected: 1},
  {...context, revision: '0'},
  {...context, revision: 0.5},
  {...context, canEdit: false},
  {...context, taskStatus: 'RUNNING'},
  {...context, blockers: ['UNKNOWN_STATE', 'TEAM_ARCHIVED']},
  {...context, checkedAt: '2026-02-30T00:00:00Z'},
  {...context, taskId: id(8)},
])
  assert(!v.validContext(value, scope));
for (const patch of [
  {priority: null},
  {priority: ''},
  {priority: 'normal'},
  {title: '\u0000'},
  {title: '\tTitle'},
  {title: '\ud800'},
  {description: 'a\u007fb'},
  {acceptanceCriteria: ''},
  {expectedRevision: '0'},
  {expectedRevision: 2147483648},
  {expectedFingerprint: 'A'.repeat(64)},
  {extra: true},
])
  assert(!v.validBody({...body, ...patch}));
assert(
  v.validBody({...body, title: '😀'.repeat(255), description: 'a\n\t\rb'}),
);
assert(!v.validBody({...body, title: '😀'.repeat(256)}));
assert(
  v.validContext(
    {
      ...context,
      values: {
        ...old,
        description: null,
        acceptanceCriteria: null,
        priority: null,
      },
    },
    scope,
  ),
);
for (const patch of [
  {taskId: id(9)},
  {eventId: 'INVALID'},
  {revision: 2},
  {after: {...old, title: 'different'}},
  {before: {...old, description: 'different'}},
  {extra: 1},
])
  assert(!v.validReceipt({...receipt, ...patch}, scope, body, old));
function component() {
  const c = Object.create(C.prototype);
  Object.assign(c, {
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    taskId: scope.taskId,
    epoch: 0,
    actor: id(5),
    original: JSON.stringify(old),
    source: {revision: 0, fingerprint: context.fingerprint},
    frozen: null,
    locator: null,
    allowedNavigation: 0,
    cancel: {next() {}},
    destroy: {destroyed: false},
    auth: {user: () => ({id: id(5)}), session: async () => ({id: id(5)})},
    router: {getCurrentNavigation: () => ({id: 9})},
    element: {nativeElement: {querySelector: () => null}},
    context: signal(structuredClone(context)),
    receipt: signal(null),
    loading: signal(false),
    busy: signal(false),
    verified: signal(true),
    denied: signal(false),
    stale: signal(false),
    error: signal(''),
    phase: signal('edit'),
    draft: {...old, title: body.title},
    acknowledged: false,
    api: {
      workspace: async () => ({
        id: scope.organizationId,
        name: 'Org',
        role: 'ADMIN',
      }),
      csrf: async () => ({}),
      context: async () => structuredClone(context),
    },
  });
  return c;
}
async function run() {
  let c = component(),
    posts = [];
  c.api.save = async (...args) => {
    posts.push(args);
    throw new HttpErrorResponse(503);
  };
  c.review();
  assert.equal(c.phase(), 'review');
  assert.equal(posts.length, 0);
  await c.save();
  assert.equal(posts.length, 0);
  c.acknowledged = true;
  await c.save();
  assert.equal(c.phase(), 'unknown');
  assert.equal(posts.length, 1);
  assert(Object.isFrozen(posts[0][1]));
  const frozen = JSON.stringify(posts[0][1]),
    key = posts[0][2];
  c.draft.title = 'Mutated after send';
  await c.save();
  assert.equal(posts.length, 1);
  await c.recover(true);
  assert.equal(posts.length, 2);
  assert.equal(JSON.stringify(posts[1][1]), frozen);
  assert.equal(posts[1][2], key);
  c.api.receipt = async () => ({receipt: null});
  await c.recover();
  assert.equal(c.phase(), 'unknown');
  c.api.receipt = async () => ({receipt: {...receipt, taskId: id(10)}});
  await c.recover();
  assert.equal(c.phase(), 'unknown');
  c.api.receipt = async () => ({receipt});
  await c.recover();
  assert.equal(c.phase(), 'saved');
  assert.equal(c.receipt().after.title, body.title);
  c = component();
  c.review();
  c.acknowledged = true;
  let calls = 0;
  c.api.save = async () => {
    calls++;
    return receipt;
  };
  c.api.context = async () => ({...context, fingerprint: 'b'.repeat(64)});
  await c.save();
  assert.equal(calls, 0);
  assert(c.stale());
  assert.equal(c.draft.title, body.title);
  assert.equal(c.acknowledged, false);
  c = component();
  c.draft = {...old};
  c.review();
  assert.equal(c.phase(), 'edit');
  c.draft.title = '\tInvalid';
  assert.equal(c.body(), null);
  c = component();
  c.review();
  c.acknowledged = true;
  let resolve;
  c.api.save = () =>
    new Promise(r => {
      resolve = r;
    });
  const pending = c.save();
  for (let i = 0; i < 50 && !resolve; i++) await Promise.resolve();
  assert(resolve);
  const locator = c.locator;
  c.loseAuthority();
  assert.equal(c.context(), null);
  assert.equal(c.receipt(), null);
  assert.equal(c.frozen, null);
  assert.equal(c.draft.title, '');
  assert.equal(c.locator, locator);
  resolve(receipt);
  await pending;
  assert.equal(c.receipt(), null);
  assert.equal(c.phase(), 'unknown');
  c = component();
  let finish;
  c.api.workspace = () =>
    new Promise(r => {
      finish = r;
    });
  const read = c.revalidate();
  assert.equal(c.context(), null);
  assert.equal(c.receipt(), null);
  for (let i = 0; i < 50 && !finish; i++) await Promise.resolve();
  assert(finish);
  finish({id: scope.organizationId, name: 'Org', role: 'MEMBER'});
  await read;
  assert(c.denied());
  assert.equal(c.draft.title, '');
  c = component();
  c.canLeave();
  c.canLeave();
  assert.equal(prompts, 1);
  c.phase.set('unknown');
  const event = {
    preventDefault() {
      this.prevented = true;
    },
  };
  c.beforeUnload(event);
  assert(event.prevented);
  const guards = load('task-edit-navigation.guard');
  let leaves = 0;
  injection = {
    active: {
      ...c,
      canLeave: () => {
        leaves++;
        return false;
      },
    },
  };
  const route = {paramMap: {get: k => injection.active[k]}};
  assert.equal(guards.taskEditNavigationGuard(route), true);
  assert.equal(
    guards.taskEditNavigationGuard({
      paramMap: {get: k => (k === 'taskId' ? id(20) : injection.active[k])},
    }),
    false,
  );
  assert.equal(leaves, 1);
  assert.equal(guards.taskEditLeaveGuard({canLeave: () => false}), false);
  console.log(
    'Task edit production wire/privacy/idempotency/state harness passed',
  );
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
