// Exercise production validators and async privacy fences through the existing transpile pattern.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const {throwError} = require('rxjs');
const signal = value => {
  const read = () => value;
  read.set = v => {
    value = v;
  };
  return read;
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
let observer;
const cache = {};
const visibilityDocument = {visibilityState: 'visible'};
function load(name) {
  if (cache[name]) return cache[name];
  const mod = {exports: {}};
  const source = ts.transpileModule(
    fs.readFileSync(
      path.join(__dirname, '../src/app/features/agents', name + '.ts'),
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
  vm.runInNewContext(source, {
    document: visibilityDocument,
    module: mod,
    exports: mod.exports,
    Set,
    Number,
    Object,
    require: key => {
      if (key === '@angular/core')
        return {
          Injectable: () => () => {},
          Component: () => () => {},
          HostListener: () => () => {},
          signal,
        };
      if (key === '@angular/common/http') return {HttpErrorResponse};
      if (key.includes('auth.service')) return {AuthFailure};
      if (key === 'rxjs')
        return {
          ...require('rxjs'),
          Subscription: class {
            add() {}
            unsubscribe() {}
          },
          forkJoin: () => ({
            subscribe: o => {
              observer = o;
              return {};
            },
          }),
        };
      if (key.startsWith('./')) return load(key.slice(2));
      return {};
    },
  });
  cache[name] = mod.exports;
  return mod.exports;
}
const v = load('agent-permissions.service');
const {AgentPermissionsComponent} = load('agent-permissions.component');
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const org = id(1),
  agent = id(2),
  actor = id(3);
const dto = {
  organizationId: org,
  agentId: agent,
  name: 'Saved Agent',
  status: 'LEGACY',
  configurationRevision: 0,
  checkedAt: '2026-10-26T12:00:00.123456Z',
  teamState: 'UNASSIGNED',
  team: null,
  configurationPresent: true,
  sourceStatus: 'UNAVAILABLE',
  requests: ['MODEL', 'TOOLS', 'SKILLS'].map((kind, i) => ({
    kind,
    configured: [null, '', '  opaque\nraw  '][i],
    effective: null,
    grantedBy: null,
    scope: null,
  })),
};
assert.equal(v.validAgentPermissions(dto, org, agent), true);
for (const mutate of [
  x => (x.extra = 'private'),
  x => (x.organizationId = id(9)),
  x => (x.agentId = id(9)),
  x => (x.configurationRevision = -1),
  x => (x.configurationRevision = 2147483648),
  x => (x.configurationRevision = 1.5),
  x => (x.checkedAt = 'yesterday'),
  x => (x.checkedAt = '2026-02-30T12:00:00Z'),
  x => (x.checkedAt = '2026-10-26T12:00:00+01:00'),
  x => (x.sourceStatus = 'GRANTED'),
  x => (x.requests[0].effective = 'DENIED'),
  x => (x.requests[1].grantedBy = 'Admin'),
  x => (x.requests[2].scope = 'Org'),
  x => x.requests.reverse(),
  x => x.requests.pop(),
  x => (x.requests[0].prompt = 'secret'),
  x => (x.configurationPresent = false),
  x => (x.team = {id: id(4), name: 'Foreign', status: 'ACTIVE'}),
  x => (x.teamState = 'ASSIGNED'),
  x => (x.teamState = ['UNAVAILABLE']),
]) {
  const x = structuredClone(dto);
  mutate(x);
  assert.equal(v.validAgentPermissions(x, org, agent), false);
}
for (const status of ['DRAFT', 'ACTIVE', 'ARCHIVED', 'UNKNOWN', ''])
  assert.equal(v.validAgentPermissions({...dto, status}, org, agent), true);
for (const teamState of ['UNASSIGNED', 'UNAVAILABLE'])
  assert.equal(v.validAgentPermissions({...dto, teamState}, org, agent), true);
assert.equal(
  v.validAgentPermissions(
    {
      ...dto,
      teamState: 'ASSIGNED',
      team: {id: id(4), name: 'Safe', status: 'legacy'},
    },
    org,
    agent,
  ),
  true,
);
assert.equal(
  v.validAgentPermissions(
    {
      ...dto,
      configurationPresent: false,
      requests: dto.requests.map(r => ({...r, configured: null})),
    },
    org,
    agent,
  ),
  true,
);
function component() {
  const x = Object.create(AgentPermissionsComponent.prototype);
  Object.assign(x, {
    epoch: 0,
    requests: {unsubscribe() {}},
    destroy: {destroyed: false},
    id: org,
    agentId: agent,
    permissions: signal(dto),
    context: signal({id: org, role: 'ADMIN'}),
    loading: signal(false),
    denied: signal(false),
    error: signal(''),
    menuOpen: signal(false),
    auth: {
      user: () => ({id: actor}),
      session: async () => ({id: actor}),
    },
    api: {
      workspace: async () => ({
        data: {id: org, name: 'Saved workspace', role: 'ADMIN'},
      }),
      read: () => ({
        subscribe: o => {
          observer = o;
          return {};
        },
      }),
    },
  });
  return x;
}
(async () => {
  const x = component();
  await x.load();
  assert.equal(x.permissions(), null);
  observer.next({data: dto});
  assert.equal(x.permissions(), dto);
  assert.equal(x.configured(null), 'Chưa cấu hình');
  assert.equal(x.configured(''), 'Chưa nhập nội dung');
  assert.equal(x.configured('  x\n '), '  x\n ');
  for (const mode of ['actor', 'role', 'workspace', '401', '403', '404']) {
    const x = component();
    await x.load();
    const late = observer;
    let sessions = 0;
    x.auth.session = async () => {
      sessions++;
      assert.equal(x.permissions(), null);
      assert.equal(x.context(), null);
      return {id: mode === 'actor' ? id(9) : actor};
    };
    x.api.workspace = async () => {
      if (['401', '403', '404'].includes(mode))
        throw new HttpErrorResponse(Number(mode));
      return {
        data: {
          id: mode === 'workspace' ? id(9) : org,
          name: 'Saved workspace',
          role: mode === 'role' ? 'OUTSIDER' : 'MEMBER',
        },
      };
    };
    await x.revalidate();
    assert.equal(sessions, 1);
    assert.equal(x.denied(), !['role', 'workspace'].includes(mode));
    const expectedError = ['role', 'workspace'].includes(mode)
      ? 'Dữ liệu workspace không hợp lệ. Vui lòng thử lại.'
      : '';
    late.next({data: dto});
    late.error(new HttpErrorResponse(500));
    assert.equal(x.permissions(), null);
    assert.equal(x.context(), null);
    assert.equal(x.error(), expectedError);
  }
  for (const failure of [
    new HttpErrorResponse(403),
    new HttpErrorResponse(503),
    new AuthFailure('server'),
  ]) {
    const raw = component();
    let reads = 0;
    const service = Object.create(v.AgentPermissionsService.prototype);
    let workspaceGets = 0;
    service.http = {
      get: endpoint => {
        workspaceGets++;
        assert.equal(
          endpoint,
          `/api/v1/auth/workspaces/${encodeURIComponent(org)}`,
        );
        return throwError(() => failure);
      },
    };
    await assert.rejects(service.workspace(org), error => error === failure);
    raw.api.workspace = service.workspace.bind(service);
    raw.api.read = () => {
      reads++;
      throw new Error('must not read');
    };
    await raw.load();
    assert.equal(reads, 0);
    assert.equal(workspaceGets, 2);
    assert.equal(raw.permissions(), null);
    assert.equal(raw.context(), null);
    assert.equal(raw.loading(), false);
    assert.equal(raw.denied(), failure.status === 403);
    assert.equal(!!raw.error(), failure.status !== 403);
  }
  for (const role of ['ADMIN', 'MEMBER'])
    assert.equal(
      v.validPermissionsWorkspace({id: org, name: 'Workspace', role}, org),
      true,
    );
  for (const data of [
    null,
    {id: org, role: 'ADMIN'},
    {id: org, name: 'Workspace', role: 'ADMIN', extra: 'private'},
    {id: id(9), name: 'Workspace', role: 'ADMIN'},
    {id: org, name: 3, role: 'ADMIN'},
    {id: org, name: 'Workspace', role: 'OUTSIDER'},
  ]) {
    assert.equal(v.validPermissionsWorkspace(data, org), false);
    const malformed = component();
    let permissionsGets = 0;
    malformed.api.workspace = async () => ({data});
    malformed.api.read = () => {
      permissionsGets++;
      throw new Error('must not read malformed workspace');
    };
    await malformed.load();
    assert.equal(malformed.context(), null);
    assert.equal(malformed.permissions(), null);
    assert.equal(malformed.denied(), false);
    assert.equal(
      malformed.error(),
      'Dữ liệu workspace không hợp lệ. Vui lòng thử lại.',
    );
    assert.equal(malformed.loading(), false);
    assert.equal(permissionsGets, 0);
  }
  const reused = component();
  await reused.load();
  const old = observer;
  reused.agentId = id(8);
  await reused.load();
  const fresh = observer;
  old.next({data: dto});
  old.error(new HttpErrorResponse(403));
  assert.equal(reused.denied(), false);
  fresh.next({data: {...dto, agentId: id(8)}});
  assert.equal(reused.permissions().agentId, id(8));
  const bad = component();
  await bad.load();
  observer.next({data: {...dto, extra: true}});
  assert.equal(bad.context(), null);
  assert.ok(bad.error());
  for (const code of [401, 403, 404, 500]) {
    const x = component();
    await x.load();
    observer.error(new HttpErrorResponse(code));
    assert.equal(x.permissions(), null);
    assert.equal(x.context(), null);
    assert.equal(x.denied(), code !== 500);
  }
  const pending = component();
  let release;
  pending.auth.session = () => new Promise(r => (release = r));
  const first = pending.load();
  pending.auth.session = async () => ({id: actor});
  await pending.load();
  const current = observer;
  release({id: actor});
  await first;
  assert.equal(observer, current);
  const actorChange = component();
  await actorChange.load();
  const stale = observer;
  actorChange.auth.user = () => ({id: id(99)});
  stale.next({data: dto});
  stale.error(new HttpErrorResponse(500));
  assert.equal(actorChange.permissions(), null);
  const destroyed = component();
  await destroyed.load();
  destroyed.destroy.destroyed = true;
  observer.next({data: dto});
  assert.equal(destroyed.permissions(), null);
  // Visibility return revalidates even while the prior GET is pending.
  const visible = component();
  await visible.load();
  const hiddenReply = observer;
  let checked = 0;
  visible.auth.session = async () => {
    checked++;
    return {id: actor};
  };
  let visibilityLoad;
  const realLoad = visible.load.bind(visible);
  visible.load = () => (visibilityLoad = realLoad());
  visible.onVisibilityChange();
  await visibilityLoad;
  assert.equal(checked, 1);
  hiddenReply.error(new HttpErrorResponse(403));
  assert.equal(visible.denied(), false);
  const workspacePending = component();
  let rejectWorkspace, enteredWorkspace;
  const workspaceEntered = new Promise(resolve => {
    enteredWorkspace = resolve;
  });
  workspacePending.api.workspace = () =>
    new Promise((_, reject) => {
      rejectWorkspace = reject;
      enteredWorkspace();
    });
  const staleWorkspace = workspacePending.load();
  await workspaceEntered;
  workspacePending.api.workspace = async () => ({
    data: {id: org, name: 'Workspace', role: 'MEMBER'},
  });
  await workspacePending.load();
  rejectWorkspace(new HttpErrorResponse(403));
  await staleWorkspace;
  assert.equal(workspacePending.denied(), false);
  assert.equal(workspacePending.error(), '');
  console.log(
    'PASS: production exact DTO, opaque values, availability, missing configuration, raw workspace 403 denial versus 503/server errors, exact workspace DTO, live session/workspace, pending refresh, identity reuse, stale success/error and destruction fences',
  );
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
