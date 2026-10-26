// Exercise production validators and async privacy fences through the existing transpile pattern.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
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
function load(name) {
  if (cache[name]) return cache[name];
  const mod = {exports: {}};
  const source = ts.transpileModule(
    fs.readFileSync(
      path.join(__dirname, '../src/app/features/organization', name + '.ts'),
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
const v = load('organization-map.service');
const {OrganizationMapComponent} = load('organization-map.component');
const id = n => `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
const org = id(1),
  teamId = id(2),
  actor = id(3);
const team = {
  id: teamId,
  name: 'Saved',
  purpose: null,
  status: 'legacy',
  managerAgentId: null,
  managerName: null,
  managerUnavailable: false,
  agentCount: 1,
  intake: 'recorded legacy',
};
const agent = {
  id: id(4),
  name: 'Saved Agent',
  role: null,
  level: 'legacy',
  status: null,
  reportsToAgentId: null,
  reportsToName: null,
  reportingUnavailable: false,
};
const overview = {
  organizationId: org,
  name: 'Organization',
  teamTotal: 1,
  agentTotal: 1,
  unassignedTotal: 0,
  teams: {items: [team], page: 0, size: 20, total: 1},
};
const agents = {
  organizationId: org,
  teamId,
  team,
  items: [agent],
  page: 0,
  size: 20,
  total: 1,
};
const copy = x => structuredClone(x);
assert.equal(v.validMapOverview(overview, org, 0), true);
assert.equal(v.validMapAgents(agents, org, teamId, 0), true);
assert.equal(
  v.validMapAgents({...agents, teamId: null, team: null}, org, null, 0),
  true,
);
for (const mutate of [
  x => {
    x.foreignIdentifier = id(9);
  },
  x => {
    x.organizationId = id(9);
  },
  x => {
    x.teams.items[0].prompt = 'secret';
  },
  x => {
    x.teams.size = 21;
  },
  x => {
    x.teamTotal = 2;
  },
  x => {
    x.agentTotal = -1;
  },
  x => {
    x.unassignedTotal = 2;
  },
  x => {
    x.teams.items[0].managerName = 'Leaked';
  },
  x => {
    x.teams.items[0].managerAgentId = id(9);
  },
  x => {
    x.teams.items[0].managerUnavailable = 'true';
  },
  x => {
    x.teams.items.push(copy(team));
    x.teams.total = 2;
    x.teamTotal = 2;
  },
]) {
  const x = copy(overview);
  mutate(x);
  assert.equal(v.validMapOverview(x, org, 0), false);
}
assert.equal(
  v.validMapOverview({...overview, teamTotal: 2}, org, 0, true),
  true,
);
for (const mutate of [
  x => {
    x.teamId = id(9);
  },
  x => {
    x.team.id = id(9);
  },
  x => {
    x.items[0].reportsToAgentId = agent.id;
    x.items[0].reportsToName = 'Self';
  },
  x => {
    x.items[0].reportsToName = 'Leaked';
  },
  x => {
    x.items[0].reportsToAgentId = id(9);
    x.items[0].reportsToName = 'Foreign';
    x.items[0].reportingUnavailable = true;
  },
  x => {
    x.items[0].configuration = {};
  },
  x => {
    x.total = Infinity;
  },
  x => {
    x.items[0].id = id(4).toUpperCase() + 'X';
  },
]) {
  const x = copy(agents);
  mutate(x);
  assert.equal(v.validMapAgents(x, org, teamId, 0), false);
}
const redacted = {...agent, reportingUnavailable: true};
assert.equal(v.validMapAgent(redacted), true);
const cycleA = {...agent, reportsToAgentId: id(5), reportsToName: 'B'};
const cycleB = {
  ...agent,
  id: id(5),
  reportsToAgentId: agent.id,
  reportsToName: 'A',
};
assert.equal(
  v.validMapAgents(
    {...agents, items: [cycleA, cycleB], total: 2},
    org,
    teamId,
    0,
  ),
  true,
);
assert.equal(v.mapUuid('00000000-0000-0000-8000-000000000001'), false);
assert.equal(
  v.validMapAgents(
    {...agents, items: [], total: 1, page: 100000},
    org,
    teamId,
    100000,
  ),
  true,
);
assert.equal(
  v.validMapAgents({...agents, items: [], page: 100001}, org, teamId, 100001),
  false,
);
function component() {
  const x = Object.create(OrganizationMapComponent.prototype);
  Object.assign(x, {
    epoch: 0,
    requests: {unsubscribe() {}},
    destroy: {destroyed: false},
    id: org,
    teamId,
    teamPage: 0,
    agentPage: 0,
    query: '',
    status: '',
    agentQuery: '',
    invalidRoute: false,
    statuses: v.mapStatuses,
    overview: signal(overview),
    inspector: signal(agents),
    context: signal({id: org, role: 'ADMIN'}),
    loading: signal(false),
    denied: signal(false),
    error: signal(''),
    menuOpen: signal(false),
    auth: {
      user: () => ({id: actor}),
      session: async () => ({id: actor}),
      getWorkspace: async () => ({id: org, role: 'ADMIN'}),
    },
    api: {overview: () => ({}), agents: () => ({})},
  });
  return x;
}
// Preserve raw Unicode search text; Java.strip and UTF-16 length validation belong to the server.
for (const raw of [
  '  saved  ',
  '\u00a0saved\u00a0',
  ' '.repeat(201) + 'saved' + ' '.repeat(201),
]) {
  const x = component();
  let sent;
  x.navigate = changes => {
    sent = changes;
  };
  x.search = raw;
  x.selectedStatus = '';
  x.applyTeams();
  assert.equal(sent.query, raw);
  x.agentSearch = raw;
  x.applyAgents();
  assert.equal(sent.agentQuery, raw);
}
(async () => {
  const normal = component();
  await normal.load();
  observer.next({overview: {data: overview}, agents: {data: agents}});
  assert.equal(normal.inspector(), agents);
  const malformed = component();
  await malformed.load();
  observer.next({
    overview: {data: {...overview, secret: 'x'}},
    agents: {data: agents},
  });
  assert.equal(malformed.inspector(), null);
  assert.equal(malformed.context(), null);
  assert.ok(malformed.error());
  for (const mode of ['account', 'role', 'workspace', '403']) {
    const x = component();
    await x.load();
    const late = observer;
    assert.equal(x.loading(), true);
    let sessions = 0,
      workspaces = 0;
    x.auth.session = async () => {
      sessions++;
      return {id: mode === 'account' ? id(99) : actor};
    };
    x.auth.getWorkspace = async () => {
      workspaces++;
      if (mode === '403') throw new HttpErrorResponse(403);
      return {
        id: mode === 'workspace' ? id(99) : org,
        role: mode === 'role' ? 'OUTSIDER' : 'MEMBER',
      };
    };
    await x.revalidate();
    assert.equal(sessions, 1);
    if (mode !== 'account') assert.equal(workspaces, 1);
    assert.equal(x.context(), null);
    assert.equal(x.inspector(), null);
    assert.equal(x.denied(), true);
    late.next({overview: {data: overview}, agents: {data: agents}});
    assert.equal(x.overview(), null);
    assert.equal(x.inspector(), null);
  }
  const failed = component();
  await failed.load();
  observer.error(new HttpErrorResponse(404));
  assert.equal(failed.overview(), null);
  assert.equal(failed.denied(), true);
  console.log(
    'PASS: production exact DTO validators, scope, redaction, legacy/null values, cycles, page bounds, malformed response clearing, pending focus session/workspace checks and late reply fences',
  );
})().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
