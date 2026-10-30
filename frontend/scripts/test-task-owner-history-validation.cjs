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
        key.includes('task-owner-validation') ||
        key === './task-owner-history-validation'
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
const root = path.resolve(__dirname, '../src/app/features/task-owner-history');
const {validHistory, pageNumber, validQuery} = load(
  path.join(root, 'task-owner-history-validation.ts'),
);
const {TaskOwnerHistoryComponent: C} = load(
  path.join(root, 'task-owner-history.component.ts'),
);
const id = n => '52000000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {
  organizationId: id(1),
  teamId: id(2),
  missionId: id(3),
  taskId: id(4),
};
const values = {
  state: 'ASSIGNED',
  agent: {id: id(10), name: 'Recorded', level: 'L2', status: 'ACTIVE'},
};
const item = n => ({
  eventId: id(100 + n),
  revision: n * 2,
  committedAt: n % 2 ? '2026-10-28T12:00:00Z' : '2026-10-27T12:00:00Z',
  actorName: null,
  before: {state: 'UNAVAILABLE', agent: null},
  after: structuredClone(values),
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
  {actorName: 'a\u2028b'},
  {before: {state: 'ASSIGNED', agent: null}},
  {after: {state: 'UNAVAILABLE', agent: null}},
  {after: {...values, agent: {...values.agent, status: 'SUSPENDED'}}},
  {after: {...values, agent: {...values.agent, name: 'a\u2029b'}}},
  {after: {...values, agent: {...values.agent, rawId: id(8)}}},
]) {
  const p = page();
  p.items[0] = {...p.items[0], ...patch};
  assert(!validHistory(p, scope, 0), JSON.stringify(patch));
}
for (const patch of [
  {teamName: 'a\u2028b'},
  {missionTitle: ''},
  {taskTitle: 'a\u2029b'},
  {rawBefore: id(7)},
])
  assert(!validHistory({...page(), ...patch}, scope, 0));
const legacy = page();
legacy.items[0].before = {
  ...values,
  agent: {...values.agent, id: id(11), status: 'LEGACY'},
};
assert(validHistory(legacy, scope, 0));
for (const key of Object.keys(page())) {
  const dto = page();
  delete dto[key];
  assert(!validHistory(dto, scope, 0), key);
}
for (const key of Object.keys(item(1))) {
  const dto = page();
  delete dto.items[0][key];
  assert(!validHistory(dto, scope, 0), key);
}
for (const [entries, expected] of [
  [[], true],
  [[['page', '0']], true],
  [[['page', '100000']], true],
  [[['page', '01']], false],
  [
    [
      ['page', '1'],
      ['page', '2'],
    ],
    false,
  ],
  [[['other', '0']], false],
]) {
  const q = {
    keys: [...new Set(entries.map(e => e[0]))],
    get: k => entries.find(e => e[0] === k)?.[1] ?? null,
    getAll: k => entries.filter(e => e[0] === k).map(e => e[1]),
  };
  assert.equal(validQuery(q), expected);
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
  assert(calls[0].endsWith('/draft-owner/history?page=0'));
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
      if (stage === 'session') c.auth.session = held;
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
    c.describe({state: 'UNASSIGNED', agent: null}),
    'Không gán Agent',
  );
  console.log(
    'Task owner history production DTO, pagination, authority and stale-response harness passed',
  );
}
run().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
