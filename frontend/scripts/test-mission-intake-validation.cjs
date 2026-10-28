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
      path.join(__dirname, '../src/app/features/mission-intake', name + '.ts'),
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
      if (key.startsWith('./mission-') || key === './safe-native-menu')
        return load(key.slice(2));
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
const v = load('mission-intake-validation');
const C = load('mission-intake.component').MissionIntakeComponent;
const id = n => '80800000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {organizationId: id(1), teamId: id(2), missionId: id(3)};
const context = {
  ...scope,
  checkedAt: '2026-10-27T00:00:00Z',
  teamName: 'Team',
  missionTitle: 'Mission',
  teamStatus: 'ACTIVE',
  missionStatus: 'RUNNING',
  intakePaused: false,
  intakeVersion: '9007199254740992',
  fingerprint: 'a'.repeat(64),
  canChange: true,
  blockers: [],
};
const body = {
  expectedVersion: context.intakeVersion,
  expectedFingerprint: context.fingerprint,
  paused: true,
};
const receipt = {
  ...scope,
  eventId: id(7),
  versionBefore: body.expectedVersion,
  versionAfter: '9007199254740993',
  pausedBefore: false,
  pausedAfter: true,
  committedAt: context.checkedAt,
};
assert(v.validContext(context, scope));
assert(v.validBody(body));
assert(v.validReceipt(receipt, scope, body, false));
for (const value of ['0', '9007199254740993', v.MAX_VERSION])
  assert(v.version(value));
for (const value of [
  0,
  1n,
  '00',
  '-1',
  '1.0',
  '9223372036854775808',
  ' 0',
  '1e2',
])
  assert(!v.version(value));
for (const patch of [
  {extra: true},
  {intakeVersion: 0},
  {canChange: false},
  {teamStatus: 'OTHER'},
  {missionStatus: 'OTHER'},
  {intakeVersion: v.MAX_VERSION},
  {blockers: ['UNKNOWN_STATE', 'TEAM_ARCHIVED']},
  {teamId: id(9)},
  {checkedAt: '2026-02-30T00:00:00Z'},
])
  assert(!v.validContext({...context, ...patch}, scope));
assert(
  v.validContext(
    {
      ...context,
      intakeVersion: v.MAX_VERSION,
      canChange: false,
      blockers: ['REVISION_EXHAUSTED'],
    },
    scope,
  ),
);
assert(
  v.validContext(
    {
      ...context,
      teamStatus: 'OTHER',
      canChange: false,
      blockers: ['UNKNOWN_STATE'],
    },
    scope,
  ),
);
for (const patch of [
  {paused: 'true'},
  {expectedVersion: 0},
  {expectedVersion: '00'},
  {expectedFingerprint: 'A'.repeat(64)},
  {extra: 1},
])
  assert(!v.validBody({...body, ...patch}));
for (const patch of [
  {versionAfter: '9007199254740994'},
  {pausedAfter: false},
  {versionBefore: 9007199254740992},
  {eventId: 'bad'},
  {missionId: id(9)},
  {extra: 1},
])
  assert(!v.validReceipt({...receipt, ...patch}, scope, body, false));
function component() {
  const c = Object.create(C.prototype);
  Object.assign(c, {
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    route: {snapshot: {queryParamMap: {keys: []}}},
    epoch: 0,
    actor: id(5),
    original: JSON.stringify({paused: false}),
    source: {revision: context.intakeVersion, fingerprint: context.fingerprint},
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
    draft: {paused: true},
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
  await c.review();
  assert.equal(c.phase(), 'review');
  await c.save();
  assert.equal(posts.length, 0);
  c.acknowledged = true;
  await c.save();
  assert.equal(c.phase(), 'unknown');
  assert.equal(posts.length, 1);
  assert(Object.isFrozen(posts[0][1]));
  const frozen = JSON.stringify(posts[0][1]),
    key = posts[0][2];
  c.draft.paused = false;
  await c.save();
  assert.equal(posts.length, 1);
  await c.recover(true);
  assert.equal(posts.length, 2);
  assert.equal(JSON.stringify(posts[1][1]), frozen);
  assert.equal(posts[1][2], key);
  c.api.receipt = async () => ({receipt: null});
  await c.recover();
  assert.equal(c.phase(), 'unknown');
  c.api.receipt = async () => ({receipt});
  await c.recover();
  assert.equal(c.phase(), 'saved');
  c = component();
  await c.review();
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
  c = component();
  c.draft.paused = false;
  await c.review();
  assert.equal(c.phase(), 'edit');
  c = component();
  await c.review();
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
  assert.equal(c.frozen, null);
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
  for (let i = 0; i < 50 && !finish; i++) await Promise.resolve();
  finish({id: scope.organizationId, name: 'Org', role: 'MEMBER'});
  await read;
  assert(c.denied());
  assert.equal(c.original, '');
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
  const guards = load('mission-intake-navigation.guard');
  injection = {active: {...c, canLeave: () => false}};
  assert(
    guards.missionIntakeNavigationGuard({
      paramMap: {get: k => injection.active[k]},
    }),
  );
  assert.equal(
    guards.missionIntakeNavigationGuard({
      paramMap: {get: k => (k === 'missionId' ? id(8) : injection.active[k])},
    }),
    false,
  );
  console.log(
    'Mission intake production int64/DTO/privacy/idempotency/state harness passed',
  );
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
