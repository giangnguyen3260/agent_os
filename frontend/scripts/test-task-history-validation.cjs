// Run production validators and component methods with controlled async replies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const cache = new Map();
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
const signal = initial => {
  let value = initial;
  const s = () => value;
  s.set = v => {
    value = v;
  };
  return s;
};
function load(file) {
  if (cache.has(file)) return cache.get(file);
  const mod = {exports: {}};
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
    },
  }).outputText;
  vm.runInNewContext(code, {
    module: mod,
    exports: mod.exports,
    TextEncoder,
    document: {visibilityState: 'visible'},
    require: key => {
      if (
        key.includes('task-edit-validation') ||
        key === './task-history-validation'
      )
        return load(path.resolve(path.dirname(file), key + '.ts'));
      if (key === '@angular/core')
        return {Component: () => x => x, HostListener: () => () => {}, signal};
      if (key === '@angular/common/http') return {HttpErrorResponse};
      if (key.endsWith('/auth.service')) return {AuthFailure};
      return {};
    },
  });
  cache.set(file, mod.exports);
  return mod.exports;
}
const root = path.resolve(__dirname, '../src/app/features/task-history');
const {validHistory, pageNumber} = load(
  path.join(root, 'task-history-validation.ts'),
);
const {TaskHistoryComponent: C} = load(
  path.join(root, 'task-history.component.ts'),
);
const id = n => '52000000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {
  organizationId: id(1),
  teamId: id(2),
  missionId: id(3),
  taskId: id(4),
};
const values = {
  title: 'Saved title',
  description: 'Input\nline',
  acceptanceCriteria: 'Criteria',
  priority: 'NORMAL',
};
const item = n => ({
  eventId: id(100 + n),
  revision: n,
  committedAt: '2026-10-27T12:00:00.123456789Z',
  actorName: 'Current name',
  before: {
    ...values,
    description: null,
    acceptanceCriteria: null,
    priority: null,
  },
  after: {...values},
});
const page = (n = 0) => ({
  ...scope,
  page: n,
  pageSize: 20,
  total: 25,
  items: Array.from({length: n === 0 ? 20 : n === 1 ? 5 : 0}, (_, i) =>
    item(25 - n * 20 - i),
  ),
});
assert(validHistory(page(), scope, 0));
assert(validHistory(page(1), scope, 1));
assert(validHistory(page(2), scope, 2));
assert(validHistory({...page(), total: 0, items: []}, scope, 0));
assert.equal(
  new Set([...page().items, ...page(1).items].map(i => i.eventId)).size,
  25,
);
for (const raw of [
  '00',
  '-1',
  '1.0',
  '1e2',
  '+1',
  ' 1',
  '100001',
  'Infinity',
  '',
])
  assert.equal(pageNumber(raw), -1);
assert.equal(pageNumber('100000'), 100000);
for (const patch of [
  {extra: 1},
  {page: '0'},
  {pageSize: 21},
  {total: -1},
  {total: 1.5},
  {total: Number.MAX_SAFE_INTEGER + 1},
  {taskId: id(9)},
  {items: []},
  {items: [...page().items].reverse()},
  {items: Array(20).fill(item(2))},
])
  assert(!validHistory({...page(), ...patch}, scope, 0));
for (const patch of [
  {eventId: 'bad'},
  {revision: 0},
  {revision: 1.2},
  {committedAt: '2026-02-30T00:00:00Z'},
  {actorName: ''},
  {actorName: '\ud800'},
  {actorName: 'a\u0000b'},
  {actorId: id(8)},
  {before: {...values, title: null}},
  {before: {...values, description: '\u0000'}},
  {after: {...values, priority: null}},
  {after: {...values, title: 'a'.repeat(256)}},
  {after: {...values, extra: 'private'}},
]) {
  const p = page();
  p.items[0] = {...p.items[0], ...patch};
  assert(!validHistory(p, scope, 0), JSON.stringify(patch));
}
function component() {
  const c = Object.create(C.prototype);
  const actor = {id: id(8), email: 'admin@example.test', displayName: 'Admin'};
  let current = actor;
  Object.assign(c, {
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    taskId: scope.taskId,
    page: 0,
    epoch: 0,
    invalid: false,
    actor: actor.id,
    cancel: {next() {}},
    destroy: {destroyed: false},
    history: signal(page()),
    workspaceName: signal('Private workspace'),
    loading: signal(false),
    denied: signal(false),
    error: signal(''),
    auth: {user: () => current, session: async () => actor},
    api: {
      workspace: async () => ({
        id: scope.organizationId,
        name: 'Workspace',
        role: 'ADMIN',
      }),
      get: async () => page(),
    },
    router: {navigate: async () => true},
  });
  c.setActor = v => {
    current = v;
  };
  return c;
}
const tick = async () => {
  for (let i = 0; i < 20; i++) await Promise.resolve();
};
async function run() {
  let c = component(),
    calls = [];
  c.api.get = async url => {
    calls.push(url);
    return page();
  };
  await c.load();
  assert.equal(c.history().total, 25);
  assert(calls[0].endsWith('/draft-settings/history?page=0'));
  let finish;
  c.auth.session = () =>
    new Promise(r => {
      finish = r;
    });
  const pending = c.load();
  assert.equal(c.history(), null);
  assert.equal(c.workspaceName(), '');
  finish({id: id(8), email: 'admin@example.test', displayName: 'Admin'});
  await pending;
  for (const role of ['MEMBER', null]) {
    c = component();
    c.api.workspace = async () => ({
      id: scope.organizationId,
      name: 'Org',
      role,
    });
    c.api.get = () => {
      throw Error('Must not fetch');
    };
    await c.load();
    assert(c.denied());
    assert.equal(c.history(), null);
  }
  for (const status of [401, 403, 404, 500]) {
    c = component();
    c.api.get = async () => {
      throw new HttpErrorResponse(status);
    };
    await c.load();
    assert.equal(c.history(), null);
    assert.equal(c.loading(), false);
    assert.equal(c.denied(), status !== 500);
  }
  c = component();
  c.auth.session = async () => ({
    id: id(9),
    email: 'other@example.test',
    displayName: 'Other',
  });
  await c.load();
  assert(c.denied());
  assert.equal(c.history(), null);
  for (const loss of ['actor', 'route', 'auth', 'destroy', 'revalidate']) {
    c = component();
    let resolve;
    c.api.get = () =>
      new Promise(r => {
        resolve = r;
      });
    const read = c.load();
    await tick();
    assert(resolve);
    if (loss === 'actor') c.setActor({id: id(9)});
    if (loss === 'auth') c.setActor(null);
    if (loss === 'route') {
      c.clear();
      c.taskId = id(99);
    }
    if (loss === 'destroy') {
      c.destroy.destroyed = true;
      c.clear();
    }
    if (loss === 'revalidate') c.clear();
    resolve(page());
    await read;
    assert.equal(c.history(), null, loss);
  }
  c = component();
  c.invalid = true;
  await c.load();
  assert(c.error());
  assert.equal(c.history(), null);
  c = component();
  c.api.get = async () => ({...page(), total: '25'});
  await c.load();
  assert(c.error());
  assert.equal(c.history(), null);
  c = component();
  let navigated;
  c.router.navigate = async (...args) => {
    navigated = args;
    return true;
  };
  c.changePage(1);
  assert.equal(c.history(), null);
  assert.equal(navigated[1].queryParams.page, 1);
  c = component();
  c.onVisibility();
  await tick();
  assert.equal(c.history().total, 25);
  assert.equal(c.show(null), 'Chưa có dữ liệu');
  assert.equal(c.show('<script>'), '<script>');
  console.log(
    'Task history production DTO, pagination, authority and stale-response harness passed',
  );
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
