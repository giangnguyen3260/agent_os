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
    TextDecoder,
    ArrayBuffer,
    document: {visibilityState: 'visible'},
    require: key => {
      if (
        key.includes('task-dependencies-validation') ||
        key === './task-dependency-history-validation'
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
const root = path.resolve(
  __dirname,
  '../src/app/features/task-dependency-history',
);
const {validHistory, pageNumber, validQuery} = load(
  path.join(root, 'task-dependency-history-validation.ts'),
);
const {TaskDependencyHistoryComponent: C} = load(
  path.join(root, 'task-dependency-history.component.ts'),
);
const id = n => '58000000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {
  organizationId: id(1),
  teamId: id(2),
  missionId: id(3),
  taskId: id(4),
};
const recorded = {id: id(10), title: 'Recorded', status: 'COMPLETED'};
const item = n => ({
  eventId: id(100 + n),
  revision: n * 2,
  committedAt: '2026-10-31T00:00:00Z',
  actorName: null,
  before: [],
  after: [{...recorded}],
});
const page = (n = 0) => ({
  ...scope,
  teamName: 'Team',
  missionTitle: 'Mission',
  taskTitle: '',
  page: n,
  pageSize: 20,
  total: 25,
  items: Array.from({length: n === 0 ? 20 : n === 1 ? 5 : 0}, (_, i) =>
    item(25 - n * 20 - i),
  ),
});
for (const n of [0, 1, 2]) assert(validHistory(page(n), scope, n));
for (const patch of [
  {extra: 1},
  {total: '25'},
  {total: -1},
  {total: 1.5},
  {pageSize: 21},
  {items: []},
  {items: [...page().items].reverse()},
])
  assert(!validHistory({...page(), ...patch}, scope, 0));
for (const patch of [
  {actorId: id(8)},
  {actorName: ''},
  {actorName: 'a\u2028b'},
  {before: [recorded]},
  {after: [{...recorded, id: scope.taskId}]},
  {after: [recorded, recorded]},
  {after: [{...recorded, status: 'FUTURE'}]},
  {after: [{...recorded, title: '\ud800'}]},
  {revision: 1.5},
  {committedAt: '2026-02-30T00:00:00Z'},
]) {
  const p = page();
  p.items[0] = {...p.items[0], ...patch};
  assert(!validHistory(p, scope, 0), JSON.stringify(patch));
}
for (const status of [
  'DRAFT',
  'READY',
  'RUNNING',
  'WAITING',
  'BLOCKED',
  'IN_REVIEW',
  'COMPLETED',
  'FAILED',
  'CANCELLED',
]) {
  const p = page();
  p.items[0].after[0].status = status;
  assert(validHistory(p, scope, 0));
}
for (const raw of ['00', '-1', '1e0', '1.0', '100001', ''])
  assert.equal(pageNumber(raw), -1);
assert.equal(pageNumber('100000'), 100000);
assert(!validQuery({keys: ['page'], get: () => '0', getAll: () => ['0', '0']}));
assert(!validQuery({keys: ['extra'], get: () => null, getAll: () => ['0']}));
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
      session: async () => actor,
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
  assert(calls[0].endsWith('/draft-dependencies/history?page=0'));
  let finish;
  c.api.session = () =>
    new Promise(r => {
      finish = r;
    });
  const pending = c.load();
  assert.equal(c.history(), null);
  assert.equal(c.workspaceName(), '');
  finish({id: id(8), email: 'admin@example.test', displayName: 'Admin'});
  await pending;
  for (const role of ['MEMBER']) {
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
  c.api.session = async () => ({
    id: id(9),
    email: 'other@example.test',
    displayName: 'Other',
  });
  await c.load();
  assert(c.denied());
  assert.equal(c.history(), null);
  for (const loss of [
    'actor',
    'route',
    'page',
    'auth',
    'destroy',
    'revalidate',
  ]) {
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
    if (loss === 'page') c.page = 1;
    if (loss === 'destroy') {
      c.destroy.destroyed = true;
      c.clear();
    }
    if (loss === 'revalidate') c.clear();
    resolve(page());
    await read;
    assert.equal(c.history(), null, loss);
  }
  for (const fence of ['actor', 'scope', 'page', 'epoch']) {
    c = component();
    let reject;
    c.api.get = () =>
      new Promise((_, r) => {
        reject = r;
      });
    const pending = c.load();
    await tick();
    if (fence === 'actor') c.setActor({id: id(9)});
    if (fence === 'scope') c.taskId = id(99);
    if (fence === 'page') c.page = 1;
    if (fence === 'epoch') c.clear();
    reject(new HttpErrorResponse(403));
    await pending;
    assert.equal(c.denied(), false, fence);
    assert.equal(c.error(), '', fence);
    assert.equal(c.history(), null, fence);
  }
  for (const workspace of [
    {id: id(99), name: 'Foreign', role: 'ADMIN'},
    {id: scope.organizationId, name: 'Org', role: 'ADMIN', extra: 1},
    {id: scope.organizationId, name: 'Org', role: null},
  ]) {
    c = component();
    c.api.workspace = async () => workspace;
    await c.load();
    assert(c.error());
    assert.equal(c.workspaceName(), '');
  }
  c = component();
  c.page = 2;
  c.api.get = async () => page(2);
  await c.load();
  assert.equal(c.history().total, 25);
  assert.equal(c.history().items.length, 0);
  let reset;
  c.router.navigate = async (_, o) => {
    reset = o.queryParams.page;
    return true;
  };
  c.resetPage();
  assert.equal(reset, 0);
  assert.equal(c.history(), null);
  for (const stage of ['session', 'workspace']) {
    for (const fail of [false, true]) {
      c = component();
      let resolve, reject;
      const held = () =>
        new Promise((a, b) => {
          resolve = a;
          reject = b;
        });
      if (stage === 'session') c.api.session = held;
      else c.api.workspace = held;
      const read = c.load();
      await tick();
      c.clear();
      c.taskId = id(99);
      if (fail) reject(new HttpErrorResponse(403));
      else
        resolve(
          stage === 'session'
            ? {id: id(8), email: 'admin@example.test', displayName: 'Admin'}
            : {id: scope.organizationId, name: 'Org', role: 'ADMIN'},
        );
      await read;
      assert.equal(c.history(), null);
      assert.equal(c.workspaceName(), '');
      assert.equal(c.denied(), false);
      assert.equal(c.error(), '');
    }
  }
  c = component();
  let count = 0;
  c.cancel.next = () => {
    count++;
  };
  c.onFocus();
  assert.equal(c.history(), null);
  assert.equal(c.workspaceName(), '');
  await tick();
  assert.equal(c.history().total, 25);
  assert(count > 0);
  for (const invalidPage of [-1, 100001, 1.5, NaN]) {
    c = component();
    c.page = invalidPage;
    let requests = 0;
    c.api.get = async () => {
      requests++;
      return page();
    };
    await c.load();
    assert.equal(requests, 0);
    assert(c.error());
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
  assert.equal(
    c.title({id: id(10), title: '', status: 'DRAFT'}),
    'Không có tiêu đề',
  );
  console.log(
    'Task dependency history production DTO, pagination, authority and stale-response harness passed',
  );
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
