// Execute the actual production validator/component code; never copy their logic.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const rx = require('rxjs');
const signal = value =>
  Object.assign(() => value, {
    set: v => {
      value = v;
    },
  });
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
const visibility = {visibilityState: 'visible'};
function load(file) {
  if (cache[file]) return cache[file];
  const mod = {exports: {}};
  const source = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
    },
  }).outputText;
  vm.runInNewContext(source, {
    module: mod,
    exports: mod.exports,
    document: visibility,
    Object,
    require: key => {
      if (key === '@angular/core')
        return {
          Injectable: () => () => {},
          Component: () => () => {},
          HostListener: () => () => {},
          signal,
        };
      if (key === '@angular/common/http')
        return {
          HttpErrorResponse,
          HttpParams: class {
            constructor() {
              this.values = {};
            }
            set(k, v) {
              this.values[k] = v;
              return this;
            }
          },
        };
      if (key.includes('auth.service')) return {AuthFailure};
      if (key === 'rxjs')
        return {...rx, forkJoin: sources => rx.forkJoin({...sources})};
      if (key.startsWith('.'))
        return load(path.resolve(path.dirname(file), key + '.ts'));
      return {};
    },
  });
  return (cache[file] = mod.exports);
}
const root = path.resolve(__dirname, '../src/app/features/trace');
const v = load(path.join(root, 'trace.service.ts'));
const {TraceExplorerComponent} = load(
  path.join(root, 'trace-explorer.component.ts'),
);
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const org = id(1),
  actor = id(2),
  eventId = id(3);
const event = {
  id: eventId,
  eventType: '<script>opaque_%\\</script>',
  actorType: 'legacy actor',
  createdAt: '2026-10-26T12:00:00.123456789Z',
  team: {id: id(4), name: 'Team'},
  mission: {id: id(5), title: 'Mission'},
  task: {id: id(6), title: 'Task'},
};
const filters = {q: '', missionId: '', taskId: '', page: 0};
const page = {
  organizationId: org,
  generatedAt: '2026-10-26T12:01:00Z',
  runtimeStatus: 'UNAVAILABLE',
  items: [event],
  page: 0,
  size: 20,
  total: 1,
};
const detail = {
  organizationId: org,
  generatedAt: page.generatedAt,
  runtimeStatus: 'UNAVAILABLE',
  event,
};
assert.ok(v.validTracePage(page, org, filters));
assert.ok(v.validTraceDetail(detail, org, eventId));
for (const mutate of [
  x => (x.payload = 'private'),
  x => delete x.items,
  x => (x.organizationId = id(99)),
  x => (x.runtimeStatus = 'AVAILABLE'),
  x => (x.generatedAt = '2026-02-30T12:00:00Z'),
  x => (x.size = 19),
  x => (x.page = 1),
  x => (x.total = 0),
  x => (x.total = 1.5),
  x => (x.items[0].actorId = id(99)),
  x => delete x.items[0].team,
  x => (x.items[0].mission = null),
  x => (x.items[0].task.extra = 'secret'),
  x => (x.items[0].team = null),
  x => (x.items[0].createdAt = '2026-10-26T12:00:00+00:00'),
  x => (x.items[0].id = eventId.toUpperCase().replace('4000', '6000')),
]) {
  const x = structuredClone(page);
  mutate(x);
  assert.equal(v.validTracePage(x, org, filters), false);
}
assert.equal(
  v.validTracePage(page, org, {...filters, missionId: id(99)}),
  false,
);
assert.equal(v.validTracePage(page, org, {...filters, taskId: id(99)}), false);
assert.ok(
  v.validTracePage({...page, items: [], page: 2}, org, {...filters, page: 2}),
);
assert.ok(v.validTraceEvent({...event, team: null, task: null}));
assert.equal(
  v.validTraceDetail(
    {...detail, event: {...event, payload: 'SECRET'}},
    org,
    eventId,
  ),
  false,
);
assert.equal(v.validTraceDetail(detail, org, id(99)), false);
const sameTime = {...event, id: id(9)};
assert.ok(
  v.validTracePage({...page, items: [event, sameTime], total: 2}, org, filters),
);
assert.equal(
  v.validTracePage({...page, items: [sameTime, event], total: 2}, org, filters),
  false,
);
assert.equal(
  v.validTracePage({...page, items: [event, event], total: 2}, org, filters),
  false,
);
assert.equal(
  v.validTracePage(
    {
      ...page,
      items: [
        event,
        {...sameTime, createdAt: '2026-10-26T12:00:00.123456790Z'},
      ],
      total: 2,
    },
    org,
    filters,
  ),
  false,
);
assert.equal(v.stripTraceQuery(' \u2000🙂\u3000 '), '🙂');
assert.equal(v.stripTraceQuery('\u00a0x\u00a0'), '\u00a0x\u00a0');
function component() {
  const x = Object.create(TraceExplorerComponent.prototype);
  const streams = [];
  Object.assign(x, {
    epoch: 0,
    requests: new rx.Subscription(),
    destroy: {destroyed: false},
    id: org,
    filters: {...filters},
    draft: {q: 'draft', missionId: '', taskId: ''},
    page: signal(page),
    selected: signal(event),
    context: signal({id: org}),
    validation: signal(''),
    loading: signal(false),
    denied: signal(false),
    error: signal(''),
    menuOpen: signal(false),
    auth: {user: () => ({id: actor}), session: async () => ({id: actor})},
    api: {
      workspace: async () => ({
        data: {id: org, name: 'Workspace', role: 'MEMBER'},
      }),
      list: () => {
        const s = new rx.Subject();
        streams.push(['list', s]);
        return s;
      },
      detail: () => {
        const s = new rx.Subject();
        streams.push(['detail', s]);
        return s;
      },
    },
  });
  x.streams = streams;
  return x;
}
function publish(stream, data) {
  stream.next({data});
  stream.complete();
}
function empty(x) {
  assert.equal(x.page(), null);
  assert.equal(x.selected(), null);
  assert.equal(x.context(), null);
}
(async () => {
  const x = component();
  x.auth.session = async () => {
    empty(x);
    return {id: actor};
  };
  x.api.workspace = async () => {
    empty(x);
    return {data: {id: org, name: 'Workspace', role: 'ADMIN'}};
  };
  await x.load(eventId);
  empty(x);
  assert.equal(x.streams.length, 2);
  publish(x.streams[0][1], page);
  empty(x);
  publish(x.streams[1][1], detail);
  assert.equal(x.selected().id, eventId);
  assert.equal(x.page().total, 1);
  for (const which of ['list', 'detail'])
    for (const mode of ['malformed', 'missing', 'empty', '403', '404', '503']) {
      const c = component();
      await c.load(eventId);
      const failed = c.streams.find(([k]) => k === which)[1];
      if (mode === 'empty') failed.complete();
      else if (['403', '404', '503'].includes(mode))
        failed.error(new HttpErrorResponse(Number(mode)));
      else
        publish(failed, mode === 'missing' ? undefined : {payload: 'secret'});
      empty(c);
      assert.equal(c.loading(), false);
      assert.equal(c.denied(), ['403', '404'].includes(mode));
      assert.equal(!!c.error(), !['403', '404'].includes(mode));
    }
  for (const mode of ['403', '503', 'malformed', 'actor']) {
    const c = component();
    c.api.workspace = async () => {
      empty(c);
      if (mode === '403' || mode === '503')
        throw new HttpErrorResponse(Number(mode));
      return {data: {id: org, role: 'ADMIN'}};
    };
    if (mode === 'actor') c.auth.session = async () => ({id: id(99)});
    await c.load(eventId);
    empty(c);
    assert.equal(c.streams.length, 0);
    assert.equal(c.denied(), ['403', 'actor'].includes(mode));
    assert.equal(!!c.error(), ['503', 'malformed'].includes(mode));
  }
  for (const mode of ['refresh', 'route', 'actor', 'destroy']) {
    const c = component();
    await c.load(eventId);
    const stale = [...c.streams];
    if (mode === 'refresh') await c.load();
    if (mode === 'route') {
      c.id = id(88);
      await c.load();
    }
    if (mode === 'actor') c.auth.user = () => ({id: id(99)});
    if (mode === 'destroy') {
      c.destroy.destroyed = true;
      c.clear();
    }
    for (const [k, s] of stale) publish(s, k === 'list' ? page : detail);
    empty(c);
    assert.equal(c.denied(), false);
  }
  for (const stage of ['session', 'workspace']) {
    const c = component();
    let release;
    const pending = new Promise(r => (release = r));
    if (stage === 'session') c.auth.session = () => pending;
    else c.api.workspace = () => pending;
    const old = c.load(eventId);
    await Promise.resolve();
    c.auth.session = async () => ({id: actor});
    c.api.workspace = async () => ({
      data: {id: org, name: 'Workspace', role: 'MEMBER'},
    });
    await c.load();
    const fresh = [...c.streams];
    release(
      stage === 'session'
        ? {id: actor}
        : {data: {id: org, name: 'Stale', role: 'ADMIN'}},
    );
    await old;
    assert.equal(c.streams.length, fresh.length);
    empty(c);
  }
  const c = component();
  let loads = 0;
  c.load = async () => {
    loads++;
  };
  c.draft.q = '🙂'.repeat(101);
  c.apply();
  assert.equal(loads, 0);
  assert.ok(c.validation());
  c.draft.q = '🙂'.repeat(100);
  c.apply();
  assert.equal(loads, 1);
  c.draft.missionId = ' ';
  c.apply();
  assert.equal(loads, 1);
  c.reset();
  assert.equal(loads, 2);
  assert.equal(c.filters.page, 0);
  const focus = component();
  await focus.revalidate();
  empty(focus);
  assert.equal(focus.draft.q, 'draft');
  const oldFocus = focus.streams[0][1];
  let returned;
  const actualLoad = focus.load.bind(focus);
  focus.load = (...args) => (returned = actualLoad(...args));
  visibility.visibilityState = 'hidden';
  focus.onVisibilityChange();
  assert.equal(focus.streams.length, 1);
  visibility.visibilityState = 'visible';
  focus.onVisibilityChange();
  await returned;
  oldFocus.error(new HttpErrorResponse(403));
  empty(focus);
  assert.equal(focus.denied(), false);
  assert.equal(focus.streams.length, 2);
  const absent = component();
  absent.auth.user = () => null;
  await absent.load();
  empty(absent);
  assert.equal(absent.denied(), true);
  assert.equal(absent.loading(), false);
  const paging = component();
  const requested = [];
  paging.load = async () => requested.push(paging.filters.page);
  paging.changePage(1);
  assert.equal(requested.length, 0);
  paging.page.set({...page, page: 2, total: 1, items: []});
  paging.changePage(-1);
  assert.equal(requested[0], 1);
  const menu = component();
  let shown = 0,
    closed = 0,
    focused = 0;
  menu.element = {
    nativeElement: {
      querySelector: selector =>
        selector === '#trace-menu'
          ? {showModal: () => shown++, close: () => closed++}
          : {focus: () => focused++},
    },
  };
  menu.toggleMenu();
  assert.equal(menu.menuOpen(), true);
  menu.closeMenu();
  assert.equal(menu.menuOpen(), false);
  assert.deepEqual([shown, closed, focused], [1, 1, 1]);
  // Exercise the raw production HTTP path; failures must never become null workspace successes.
  const service = Object.create(v.TraceService.prototype);
  for (const status of [403, 503]) {
    service.http = {
      get: () => rx.throwError(() => new HttpErrorResponse(status)),
    };
    await assert.rejects(service.workspace(org), e => e.status === status);
  }
  const calls = [];
  service.http = {
    get: (url, options) => {
      calls.push({url, options});
      return rx.of({data: page});
    },
  };
  service
    .list(org, {...filters, q: '%_\\', missionId: id(5), taskId: id(6)})
    .subscribe();
  service.detail(org, eventId).subscribe();
  assert.deepEqual(calls[0].options.params.values, {
    page: 0,
    q: '%_\\',
    missionId: id(5),
    taskId: id(6),
  });
  assert.ok(calls[1].url.endsWith('/' + eventId));
  assert.equal(calls[1].options, undefined);
  console.log(
    'PASS: production transpile exact wire validation, stable nanosecond ordering, opaque labels, filters, raw workspace errors, atomic selection, missing completions, cancellation and stale authorization/account/route/destruction fences',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
