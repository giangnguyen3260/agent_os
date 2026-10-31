const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const root = path.join(__dirname, '../src/app/features/task-dependencies');
function load(file, mocks = {}) {
  const module = {exports: {}};
  const code = ts.transpileModule(
    fs.readFileSync(path.join(root, file + '.ts'), 'utf8'),
    {
      compilerOptions: {
        module: ts.ModuleKind.CommonJS,
        target: ts.ScriptTarget.ES2022,
        experimentalDecorators: true,
      },
    },
  ).outputText;
  vm.runInNewContext(code, {
    module,
    exports: module.exports,
    require: n => mocks[n] || {},
    ArrayBuffer,
    TextEncoder,
    TextDecoder,
    Date,
    Set,
    Reflect,
    Object,
    JSON,
    Number,
    structuredClone,
    queueMicrotask,
    crypto: require('node:crypto').webcrypto,
  });
  return module.exports;
}
const v = load('task-dependencies-validation');
const id = n => `57000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const scope = {
  organizationId: id(1),
  teamId: id(2),
  missionId: id(3),
  taskId: id(4),
};
const task = n => ({id: id(n), title: `Task ${n}`, status: 'DRAFT'});
const target = n => ({...task(n), fingerprint: 'a'.repeat(64)});
const context = {
  ...scope,
  checkedAt: '2026-10-30T12:00:00.123456789Z',
  revision: 0,
  fingerprint: 'b'.repeat(64),
  teamName: 'Team',
  missionTitle: 'Mission',
  taskTitle: '',
  taskStatus: 'DRAFT',
  dependencies: [task(5)],
  canEdit: true,
  blockers: [],
};
const body = {
  expectedRevision: 0,
  expectedFingerprint: context.fingerprint,
  dependencies: [{taskId: id(6), fingerprint: 'a'.repeat(64)}],
};
const receipt = {
  ...scope,
  eventId: id(8),
  revision: 1,
  committedAt: context.checkedAt,
  before: [task(5)],
  after: [task(6)],
};
const page = {
  ...scope,
  page: 0,
  pageSize: 20,
  total: 1,
  query: '%_!',
  items: [target(6)],
};
assert(v.validContext(context, scope));
assert(v.validBody(body, scope.taskId));
assert(v.validReceipt(receipt, scope, body, context.dependencies));
assert(v.validPage(page, scope, 0, '%_!'));
for (const [fn, obj, args] of [
  [v.validContext, context, [scope]],
  [v.validBody, body, [scope.taskId]],
  [v.validReceipt, receipt, [scope]],
  [v.validPage, page, [scope, 0, '%_!']],
  [v.candidate, target(6), []],
  [v.task, task(6), []],
]) {
  for (const k of Object.keys(obj)) {
    const missing = {...obj};
    delete missing[k];
    assert(!fn(missing, ...args), `missing ${k}`);
    assert(!fn({...obj, [k]: null}, ...args), `null ${k}`);
  }
  assert(!fn({...obj, extra: 0}, ...args));
}
for (const title of ['\ud800', '\udfff', '\u0000', '\u0085', '\u007f'])
  assert(!v.task({...task(6), title}));
assert(v.task({...task(6), title: '😀'.repeat(255)}));
assert(!v.task({...task(6), title: '😀'.repeat(256)}));
assert(v.task({...task(6), title: ''}));
for (const status of v.statuses) {
  assert(v.candidate({...target(6), status}));
  assert(v.validReceipt({...receipt, after: [{...task(6), status}]}, scope));
}
assert(!v.candidate({...target(6), status: 'NEW'}));
assert(
  !v.validContext(
    {...context, dependencies: [{...task(5), status: 'NEW'}]},
    scope,
  ),
);
assert(
  v.validContext(
    {
      ...context,
      dependencies: [{...task(5), status: 'NEW'}],
      canEdit: false,
      blockers: ['UNKNOWN_STATE'],
    },
    scope,
  ),
);
for (const dependencies of [
  [task(5), task(5)],
  [task(6), task(5)],
  [task(4)],
  Array.from({length: 21}, (_, i) => task(i + 10)),
])
  assert(!v.validContext({...context, dependencies}, scope));
assert(!v.validContext({...context, canEdit: false}, scope));
assert(!v.validContext({...context, revision: 2147483647}, scope));
assert(
  !v.validContext(
    {...context, blockers: ['UNKNOWN_STATE', 'TEAM_ARCHIVED'], canEdit: false},
    scope,
  ),
);
assert(!v.validReceipt({...receipt, after: receipt.before}, scope));
assert(!v.validReceipt({...receipt, revision: 2}, scope, body));
assert(
  !v.validReceipt(
    {...receipt, before: [{...task(5), title: 'Wrong'}]},
    scope,
    body,
    context.dependencies,
  ),
);
assert(!v.validReceipt({...receipt, after: [task(7)]}, scope, body));
assert(!v.validPage({...page, total: 2}, scope, 0, '%_!'));
assert(
  !v.validPage(
    {...page, items: [{...target(6), id: scope.taskId}]},
    scope,
    0,
    '%_!',
  ),
);
const unicodePage = {
  ...page,
  total: 2,
  items: [
    {...target(5), title: '\ue000'},
    {...target(6), title: '😀'},
  ],
};
assert(v.validPage(unicodePage, scope, 0, '%_!'));
assert(
  !v.validPage(
    {...unicodePage, items: [...unicodePage.items].reverse()},
    scope,
    0,
    '%_!',
  ),
);
assert(!v.timestamp('2026-02-30T00:00:00Z'));
assert(!v.timestamp('2026-01-01T00:00:00+00:00'));
const bytes = s => new TextEncoder().encode(s).buffer;
assert.equal(
  v.decodeData(bytes(JSON.stringify({data: context, meta: {}}))).revision,
  0,
);
for (const wire of [
  '{"data":{"revision":1e0},"meta":{}}',
  '{"data":{"page":0.0},"meta":{}}',
  '{"data":{"revision":-0},"meta":{}}',
  '{"data":{"id":1,"id":2},"meta":{}}',
  '{"data":null,"meta":{}} garbage',
])
  assert.throws(() => v.decodeData(bytes(wire)));
assert.throws(() => v.decodeData(Uint8Array.from([0xff]).buffer));
class HttpErrorResponse extends Error {
  constructor(status, code) {
    super('HTTP');
    this.status = status;
    this.error = {error: {code}};
  }
}
class AuthFailure extends Error {
  constructor(kind) {
    super(kind);
    this.kind = kind;
  }
}
const session = () => ({
  id: id(9),
  email: 'admin@example.test',
  displayName: 'Admin',
});
const decorator = () => () => {};
const proto = load('task-dependencies.component', {
  '@angular/core': {Component: decorator, HostListener: decorator},
  '@angular/common/http': {HttpErrorResponse},
  './task-dependencies-validation': v,
  '../../core/auth/auth.service': {AuthFailure},
}).TaskDependenciesComponent.prototype;
const signal = initial => {
  let value = initial;
  const f = () => value;
  f.set = v => {
    value = v;
  };
  f.update = fn => {
    value = fn(value);
  };
  return f;
};
function state() {
  return Object.assign(Object.create(proto), {
    actor: id(9),
    owner: id(9),
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    taskId: scope.taskId,
    destroy: {destroyed: false},
    epoch: 1,
    searchEpoch: 0,
    source: structuredClone(context),
    context: signal(structuredClone(context)),
    selection: signal([target(6)]),
    receipt: signal(null),
    candidates: signal(null),
    phase: signal('review'),
    busy: signal(false),
    verified: signal(true),
    error: signal(''),
    chooserLoading: signal(false),
    chooserError: signal(''),
    acknowledged: true,
    scope: () => scope,
    active(e) {
      return e === this.epoch;
    },
    cancel: {next() {}},
    closeChooser() {},
    closeMenu() {},
    focusHeading() {},
    auth: {user: () => ({id: id(9)}), session: async () => session()},
    api: {
      session: async () => session(),
      workspace: async () => ({
        id: scope.organizationId,
        name: 'Workspace',
        role: 'ADMIN',
      }),
      context: async () => context,
      candidate: async (_, id) => ({...target(6), id}),
      csrf: async () => ({}),
    },
  });
}
(async () => {
  let s = state(),
    posts = 0;
  s.api.save = async () => {
    posts++;
    return receipt;
  };
  s.api.context = async () => ({...context, fingerprint: 'c'.repeat(64)});
  await s.save();
  assert.equal(posts, 0);
  assert.equal(s.phase(), 'conflict');
  assert.equal(s.acknowledged, false);
  assert.equal(s.selection()[0].id, id(6));
  s = state();
  s.api.save = async () => {
    posts++;
    return receipt;
  };
  s.api.candidate = async () => ({...target(6), fingerprint: 'd'.repeat(64)});
  await s.save();
  assert.equal(posts, 0);
  assert.equal(s.phase(), 'conflict');
  s = state();
  let calls = [];
  s.api.save = async (_, b, key) => {
    calls.push({b, key});
    return {...receipt, extra: true};
  };
  await s.save();
  assert.equal(s.phase(), 'unknown');
  assert(s.canReplay());
  const frozen = s.frozen,
    key = s.locator.key;
  s.api.context = () => {
    throw Error('Recovery must not read mutable context');
  };
  s.api.candidate = () => {
    throw Error('Recovery must not hydrate');
  };
  s.api.receipt = async () => ({receipt: null});
  await s.recover();
  assert.equal(s.phase(), 'unknown');
  assert.equal(s.frozen, frozen);
  s.api.save = async (_, b, k) => {
    calls.push({b, key: k});
    return receipt;
  };
  await s.recover(true);
  assert.equal(s.phase(), 'saved');
  assert.equal(calls[0].b, calls[1].b);
  assert.equal(calls[1].key, key);
  assert.equal(s.frozen, null);
  s = state();
  s.api.save = async () => {
    throw new HttpErrorResponse(503);
  };
  await s.save();
  assert.equal(s.phase(), 'unknown');
  const locator = s.locator;
  s.loseAuthority();
  assert.equal(s.context(), null);
  assert.equal(s.selection().length, 0);
  assert.equal(s.frozen, null);
  assert.equal(s.locator, locator);
  assert.equal(s.canReplay(), false);
  s.actor = id(99);
  s.loseAuthority();
  assert.equal(s.locator, null);
  s = state();
  let inFlight = 0,
    max = 0;
  const ids = Array.from({length: 20}, (_, i) => id(i + 10));
  s.api.candidate = async (_, id) => {
    inFlight++;
    max = Math.max(max, inFlight);
    await new Promise(r => setImmediate(r));
    inFlight--;
    return {...target(6), id};
  };
  const hydrated = await s.hydrate(ids, 1);
  assert.equal(max, 4);
  assert.equal(hydrated.length, 20);
  assert.deepEqual(
    Array.from(hydrated, t => t.id),
    ids,
  );
  s = state();
  s.selection.set([]);
  s.phase.set('edit');
  s.source = {...context, dependencies: []};
  assert.equal(s.dirty(), false);
  await s.review();
  assert.equal(s.phase(), 'edit');
  s.selection.set([target(6)]);
  s.acknowledged = true;
  s.remove(id(6));
  assert.equal(s.selection().length, 0);
  assert.equal(s.acknowledged, false);
  s = state();
  s.phase.set('edit');
  s.selection.set([]);
  s.source = null;
  s.route = {snapshot: {queryParamMap: {keys: []}}};
  s.api.candidate = async () => {
    throw Error('Unavailable');
  };
  await s.load();
  assert.equal(s.phase(), 'conflict');
  assert.equal(s.selection()[0].id, id(5));
  assert.equal(s.body(), null);
  s = state();
  let resolve;
  s.api.context = () =>
    new Promise(r => {
      resolve = r;
    });
  const checking = s.review();
  await new Promise(r => setImmediate(r)); // review was already review, so no request
  await checking;
  s.phase.set('edit');
  const pending = s.review();
  await new Promise(r => setImmediate(r));
  assert.equal(s.context(), null);
  assert.equal(s.selection().length, 0);
  s.epoch++;
  resolve(context);
  await pending;
  assert.equal(s.context(), null);
  s = state();
  s.source = {...context, dependencies: []};
  s.selection.set([]);
  s.api.candidate = () => {
    throw Error('Clear must not hydrate');
  };
  assert(await s.preflight(1, s.source, []));
  s = state();
  s.api.workspace = async () => ({
    id: scope.organizationId,
    name: 'Workspace',
    role: 'MEMBER',
  });
  await s.authorize(1);
  assert.equal(s.context(), null);
  assert.equal(s.phase(), 'denied');
  // Unverified authority reads must never masquerade as a revocation.
  for (const failure of [
    new HttpErrorResponse(503),
    new HttpErrorResponse(0),
    new AuthFailure('network'),
    new AuthFailure('server'),
    Error('Malformed response'),
  ]) {
    for (const endpoint of ['session', 'workspace']) {
      s = state();
      let posts = 0;
      s.api.save = async () => {
        posts++;
        return receipt;
      };
      s.api[endpoint] = async () => {
        throw failure;
      };
      await s.save();
      assert.equal(posts, 0);
      assert.equal(s.phase(), 'error');
      assert.equal(s.error(), v.errorLabels.TASK_DEPENDENCIES_UNAVAILABLE);
      assert.equal(s.verified(), false);
      assert.equal(s.context(), null);
      assert.equal(s.selection().length, 0);
    }
  }
  for (const endpoint of ['session', 'workspace']) {
    s = state();
    let posts = 0;
    s.api.save = async () => {
      posts++;
      throw new HttpErrorResponse(503);
    };
    await s.save();
    const pending = s.frozen,
      locator = s.locator;
    const authority = s.api,
      original = authority[endpoint];
    authority[endpoint] = async () => {
      throw new HttpErrorResponse(503);
    };
    await s.revalidate();
    assert.equal(s.phase(), 'unknown');
    assert.equal(s.frozen, pending);
    assert.equal(s.locator, locator);
    assert.equal(s.context(), null);
    assert.equal(s.selection().length, 0);
    assert.equal(s.verified(), false);
    assert.equal(s.error(), v.errorLabels.TASK_DEPENDENCIES_UNAVAILABLE);
    await s.recover(true);
    assert.equal(posts, 1);
    assert.equal(s.frozen, pending);
    authority[endpoint] = original;
    s.api.save = async (_, b, k) => {
      posts++;
      assert.equal(b, pending.body);
      assert.equal(k, locator.key);
      return receipt;
    };
    await s.recover(true);
    assert.equal(posts, 2);
    assert.equal(s.phase(), 'saved');
  }
  for (const malformed of [
    {id: scope.organizationId, name: 'Workspace'},
    {id: scope.organizationId, name: 'Workspace', role: 42},
  ]) {
    s = state();
    s.api.workspace = async () => malformed;
    assert.equal(await s.authorize(1), false);
    assert.equal(s.phase(), 'error');
    assert.equal(s.error(), v.errorLabels.TASK_DEPENDENCIES_UNAVAILABLE);
  }
  s = state();
  s.api.session = async () => ({id: id(9)});
  await s.authorize(1);
  assert.equal(s.phase(), 'error');
  for (const failure of [
    new HttpErrorResponse(401),
    new HttpErrorResponse(403),
    new HttpErrorResponse(404),
    new AuthFailure('unauthorized'),
    new AuthFailure('not-found'),
  ]) {
    s = state();
    s.frozen = {body, before: context.dependencies};
    s.locator = {...scope, actor: id(9), key: id(8)};
    s.api.workspace = async () => {
      throw failure;
    };
    await s.authorize(1);
    assert.equal(s.frozen, null);
    assert.equal(s.error(), v.errorLabels.FORBIDDEN);
  }
  // Even failed old reads must not overwrite a newer identity/scope generation.
  s = state();
  let rejectRead;
  s.api.workspace = () =>
    new Promise((_, reject) => {
      rejectRead = reject;
    });
  const oldRead = s.authorize(1);
  await new Promise(r => setImmediate(r));
  s.epoch++;
  s.phase.set('edit');
  rejectRead(new HttpErrorResponse(503));
  await oldRead;
  assert.equal(s.phase(), 'edit');
  // Exercise production HttpClient adapters AND the real component constructor effect.
  // This intentionally uses the real AuthService, not a constant auth.user stub.
  const rx = require('rxjs');
  const effects = [];
  const reactiveSignal = initial => {
    const f = signal(initial),
      set = f.set;
    f.set = value => {
      set(value);
      for (const effect of effects) effect();
    };
    f.asReadonly = () => f;
    return f;
  };
  let sessionPayload = session(),
    sessionFailure = null,
    adapterPosts = 0;
  const requests = [];
  const http = {
    get(url, options) {
      requests.push({url, options});
      if (url === '/api/v1/auth/session') {
        if (sessionFailure) return rx.throwError(() => sessionFailure);
        return rx.of(
          options?.responseType === 'arraybuffer'
            ? bytes(JSON.stringify({data: sessionPayload, meta: {}}))
            : {data: sessionPayload, meta: {}},
        );
      }
      assert.equal(url, `/api/v1/auth/workspaces/${scope.organizationId}`);
      return rx.of(
        bytes(
          JSON.stringify({
            data: {id: scope.organizationId, name: 'Workspace', role: 'ADMIN'},
            meta: {},
          }),
        ),
      );
    },
    post() {
      adapterPosts++;
      return rx.of({
        status: 200,
        body: bytes(JSON.stringify({data: receipt, meta: {}})),
      });
    },
  };
  const sharedAuthModule = load('../../core/auth/auth.service', {
    '@angular/core': {
      Injectable: decorator,
      inject: () => http,
      signal: reactiveSignal,
    },
    '@angular/common/http': {HttpErrorResponse},
    rxjs: rx,
  });
  const realAuth = new sharedAuthModule.AuthService();
  realAuth.currentUser.set(session());
  const serviceModule = load('task-dependencies.service', {
    '@angular/core': {Injectable: decorator, inject: () => http},
    './task-dependencies-validation': v,
    rxjs: rx,
  });
  const realApi = new serviceModule.TaskDependenciesService();
  const routeParams = new rx.Subject();
  const lifetime = {destroyed: false, onDestroy() {}};
  const injections = [
    realAuth,
    realApi,
    {paramMap: routeParams, snapshot: {queryParamMap: {keys: []}}},
    {getCurrentNavigation: () => null},
    lifetime,
    {nativeElement: {querySelector: () => null}},
    {active: null},
  ];
  const realComponentModule = load('task-dependencies.component', {
    '@angular/core': {
      Component: decorator,
      HostListener: decorator,
      inject: () => injections.shift(),
      signal: reactiveSignal,
      effect: fn => {
        effects.push(fn);
        fn();
      },
    },
    '@angular/common/http': {HttpErrorResponse},
    '@angular/core/rxjs-interop': {takeUntilDestroyed: () => source => source},
    '../../core/auth/auth.service': sharedAuthModule,
    './task-dependencies.service': serviceModule,
    './task-dependencies-validation': v,
    rxjs: rx,
  });
  const live = new realComponentModule.TaskDependenciesComponent();
  Object.assign(live, {
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    taskId: scope.taskId,
  });
  const pendingBody = {
    body: structuredClone(body),
    before: structuredClone(context.dependencies),
  };
  const pendingLocator = {...scope, actor: id(9), key: id(8)};
  function pendingLive() {
    live.phase.set('unknown');
    live.frozen = pendingBody;
    live.locator = pendingLocator;
  }
  pendingLive();
  for (const malformed of [
    {email: 'admin@example.test', displayName: 'Admin'},
    {...session(), id: 'invalid'},
    {...session(), id: id(99), extra: true},
  ]) {
    sessionPayload = malformed;
    await live.recover(true);
    assert.equal(live.phase(), 'unknown');
    assert.equal(live.frozen, pendingBody);
    assert.equal(live.locator, pendingLocator);
    assert.equal(realAuth.user().id, id(9));
    assert.equal(live.actor, id(9));
    assert.equal(live.error(), v.errorLabels.TASK_DEPENDENCIES_UNAVAILABLE);
    assert.equal(live.verified(), false);
    assert.equal(adapterPosts, 0);
  }
  sessionPayload = session();
  sessionFailure = new HttpErrorResponse(503);
  await live.revalidate();
  assert.equal(live.frozen, pendingBody);
  assert.equal(adapterPosts, 0);
  sessionFailure = new HttpErrorResponse(403);
  await live.recover(true);
  assert.equal(live.frozen, null);
  assert.equal(live.error(), v.errorLabels.FORBIDDEN);
  assert.equal(adapterPosts, 0);
  sessionFailure = null;
  pendingLive();
  sessionPayload = {...session(), id: id(99)};
  await live.recover(true);
  assert.equal(live.frozen, null);
  assert.equal(live.locator, null);
  assert.equal(adapterPosts, 0);
  sessionPayload = session();
  pendingLive();
  realAuth.currentUser.set({...session(), id: id(99)});
  assert.equal(live.actor, id(99));
  assert.equal(live.frozen, null);
  assert.equal(live.locator, null);
  assert(requests.every(r => r.options.responseType === 'arraybuffer'));
  // Reuse the real constructor subscription while an old POST or receipt read
  // is in flight. Resolve/reject it after the new route has finished loading.
  realAuth.currentUser.set(session());
  for (const oldPhase of ['saving', 'unknown']) {
    for (const lateFailure of [false, true]) {
      Object.assign(live, {
        id: scope.organizationId,
        teamId: scope.teamId,
        missionId: scope.missionId,
        taskId: scope.taskId,
        source: structuredClone(context),
        frozen: pendingBody,
        locator: pendingLocator,
      });
      live.phase.set(oldPhase);
      live.context.set(structuredClone(context));
      live.selection.set([target(6)]);
      live.receipt.set(structuredClone(receipt));
      live.candidates.set(structuredClone(page));
      live.query = 'old private search';
      live.acknowledged = true;
      live.verified.set(true);
      live.busy.set(oldPhase === 'saving');
      let finishOld, failOld, releaseContext;
      let routePosts = 0;
      const heldOld = new Promise((resolve, reject) => {
        finishOld = resolve;
        failOld = reject;
      });
      realApi.save = async (s, b, key) => {
        routePosts++;
        assert(v.sameScope(s, scope));
        assert.equal(b, pendingBody.body);
        assert.equal(key, pendingLocator.key);
        return heldOld;
      };
      let receiptReads = 0;
      realApi.receipt = async (s, key) => {
        receiptReads++;
        assert(v.sameScope(s, scope));
        assert.equal(key, pendingLocator.key);
        return heldOld;
      };
      const oldOperation =
        oldPhase === 'saving'
          ? live.postFrozen(live.epoch, {})
          : live.recover();
      await new Promise(r => setImmediate(r));
      assert.equal(routePosts, oldPhase === 'saving' ? 1 : 0);
      assert.equal(receiptReads, oldPhase === 'unknown' ? 1 : 0);
      const nextScope = {
        ...scope,
        taskId: id(35 + (oldPhase === 'unknown' ? 2 : 0) + Number(lateFailure)),
      };
      const nextContext = {
        ...structuredClone(context),
        ...nextScope,
        taskTitle: 'New route task',
        dependencies: [],
      };
      const contextScopes = [];
      realApi.context = async s => {
        contextScopes.push({...s});
        assert(v.sameScope(s, nextScope));
        if (contextScopes.length === 1)
          return new Promise(resolve => {
            releaseContext = resolve;
          });
        return nextContext;
      };
      const oldEpoch = live.epoch;
      routeParams.next({
        get: k => (k === 'id' ? nextScope.organizationId : nextScope[k]),
      });
      assert.equal(
        live.phase(),
        'loading',
        `${oldPhase} route must start loading`,
      );
      assert(live.epoch > oldEpoch);
      assert(v.sameScope(live.scope(), nextScope));
      assert.equal(live.context(), null);
      assert.equal(live.source, null);
      assert.equal(live.receipt(), null);
      assert.equal(live.candidates(), null);
      assert.equal(live.selection().length, 0);
      assert.equal(live.query, '');
      assert.equal(live.frozen, null);
      assert.equal(live.locator, null);
      assert.equal(live.verified(), false);
      assert.equal(live.acknowledged, false);
      await new Promise(r => setImmediate(r));
      assert.equal(
        typeof releaseContext,
        'function',
        'new scope must request context',
      );
      releaseContext(nextContext);
      await new Promise(r => setImmediate(r));
      assert.equal(live.phase(), 'edit');
      assert.equal(live.context(), nextContext);
      assert.equal(live.source, nextContext);
      assert.equal(live.editable(), true);
      assert.equal(contextScopes.length, 2);
      if (lateFailure) failOld(new HttpErrorResponse(503));
      else finishOld(oldPhase === 'saving' ? receipt : {receipt});
      await oldOperation;
      assert.equal(
        live.phase(),
        'edit',
        'late old response must not change new phase',
      );
      assert.equal(live.context(), nextContext);
      assert.equal(live.source, nextContext);
      assert.equal(live.receipt(), null);
      assert.equal(live.frozen, null);
      assert.equal(live.locator, null);
      assert.equal(live.selection().length, 0);
      assert.equal(live.error(), '');
      assert.equal(live.busy(), false);
      assert.equal(live.editable(), true);
      await live.recover(true);
      await live.save();
      assert.equal(routePosts, oldPhase === 'saving' ? 1 : 0);
      assert.equal(adapterPosts, 0);
    }
  }
  console.log(
    'Task dependencies production wire validators and component state checks passed',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
