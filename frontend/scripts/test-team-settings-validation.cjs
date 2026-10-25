// Run the real production validators and state transitions, not copies of their logic.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const cache = {};
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
let prompts = 0;
function load(name) {
  if (cache[name]) return cache[name];
  const mod = {exports: {}};
  const source = ts.transpileModule(
    fs.readFileSync(
      path.join(__dirname, '../src/app/features/teams', name + '.ts'),
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
    TextEncoder,
    Date,
    Set,
    Map,
    JSON,
    Number,
    Reflect,
    structuredClone,
    queueMicrotask,
    crypto: require('node:crypto').webcrypto,
    window: {
      confirm: () => {
        prompts++;
        return true;
      },
    },
    require: key => {
      if (key === '@angular/core')
        return {
          Component: () => v => v,
          Injectable: () => v => v,
          HostListener: () => () => {},
          signal,
        };
      if (key === '@angular/common/http') return {HttpErrorResponse};
      if (key === '../../core/auth/auth.service') return {AuthFailure};
      if (key.startsWith('./team-settings')) return load(key.slice(2));
      if (
        [
          '@angular/common',
          '@angular/core/rxjs-interop',
          '@angular/forms',
          '@angular/router',
          'rxjs',
        ].includes(key)
      )
        return {};
      throw Error('Unexpected dependency ' + key);
    },
  });
  return (cache[name] = mod.exports);
}
const v = load('team-settings-validation');
const id = n => '80800000-0000-4000-8000-' + String(n).padStart(12, '0');
const org = id(1),
  team = id(2),
  actor = id(3),
  key = id(4),
  time = '2026-10-25T12:30:00.123456Z';
const state = {
  name: 'Team',
  purpose: 'Purpose',
  description: null,
  status: 'DRAFT',
  managerAgentId: id(6),
  agentIds: [id(7)],
  intake: 'MANUAL',
  concurrency: 4,
  monthlyBudget: 100,
  currency: 'USD',
};
const member = (n, level = 'MID', status = 'DRAFT') => ({
  id: id(n),
  name: 'Agent ' + n,
  level,
  status,
  canRemove: status === 'DRAFT',
  removalBlockers: status === 'DRAFT' ? [] : ['AGENT_NOT_DRAFT'],
});
const context = {
  organizationId: org,
  teamId: team,
  checkedAt: time,
  revision: 0,
  fingerprint: 'a'.repeat(64),
  team: state,
  members: [member(6, 'MANAGER'), member(7)],
  memberTotal: 2,
  canEdit: true,
  canChangeMembership: true,
  blockers: [],
};
const body = {
  expectedRevision: 0,
  expectedFingerprint: context.fingerprint,
  name: 'Updated',
  purpose: 'Purpose',
  description: null,
  managerAgentId: id(6),
  agentIds: [id(7)],
  intake: 'MANUAL',
  concurrency: 4,
  monthlyBudget: 100,
  acknowledgement: true,
};
const locator = {org, team, actor, key};
const receipt = {
  operation: 'UPDATE_TEAM_SETTINGS',
  organizationId: org,
  teamId: team,
  idempotencyKey: key,
  revision: 1,
  eventId: id(5),
  actorId: actor,
  actorName: 'Actor',
  recordedAt: time,
  before: state,
  after: v.desiredState(body, 'DRAFT'),
};
const vc = x => v.validSettingsContext(x, org, team);
assert.equal(vc(context), true);
for (const patch of [
  {organizationId: actor},
  {teamId: actor},
  {revision: '0'},
  {revision: -1},
  {revision: 2147483648},
  {revision: 1.1},
  {fingerprint: 'A'.repeat(64)},
  {checkedAt: '2026-02-30T00:00:00.000000Z'},
  {canEdit: false},
  {canChangeMembership: false},
  {members: []},
  {memberTotal: 3},
  {secret: 'private'},
  {blockers: ['UNSUPPORTED_CURRENCY']},
])
  assert.equal(vc({...context, ...patch}), false, JSON.stringify(patch));
for (const k of Object.keys(context)) {
  const x = {...context};
  delete x[k];
  assert.equal(vc(x), false, k);
}
assert.equal(vc({...context, revision: 2147483647}), true);
for (const patch of [
  {name: '\ud800'},
  {name: 'x\ny'},
  {purpose: 1},
  {agentIds: [id(7), id(7)]},
  {agentIds: [id(6)]},
  {monthlyBudget: '100'},
  {monthlyBudget: 0.001},
  {concurrency: 1.1},
  {currency: 'usd'},
  {runtimeSlots: 1},
])
  assert.equal(
    vc({...context, team: {...state, ...patch}}),
    false,
    JSON.stringify(patch),
  );
assert.equal(
  vc({
    ...context,
    team: {
      ...state,
      purpose: null,
      description: null,
      intake: null,
      concurrency: null,
      monthlyBudget: null,
      currency: null,
    },
  }),
  true,
);
const active = {
  ...context,
  team: {...state, status: 'ACTIVE'},
  members: context.members.map(m => ({
    ...m,
    canRemove: false,
    removalBlockers: ['TEAM_NOT_DRAFT'],
  })),
  canChangeMembership: false,
};
assert.equal(vc(active), true);
const legacy = {
  ...context,
  members: [member(6, 'JUNIOR', 'SUSPENDED'), member(7)],
};
assert.equal(vc(legacy), true);
assert.equal(
  vc({
    ...legacy,
    members: [
      {
        ...member(6, 'LEGACY', 'LEGACY'),
        canRemove: false,
        removalBlockers: ['AGENT_UNSUPPORTED'],
      },
      member(7),
    ],
  }),
  true,
);
const blocked = {
  ...context,
  team: {...state, status: 'ARCHIVED', currency: 'EUR'},
  members: active.members,
  canEdit: false,
  canChangeMembership: false,
  blockers: ['TEAM_ARCHIVED', 'UNSUPPORTED_CURRENCY'],
};
assert.equal(vc(blocked), true);
assert.equal(
  vc({...blocked, blockers: [...blocked.blockers].reverse()}),
  false,
);
const large = {
  ...context,
  team: {
    ...state,
    managerAgentId: null,
    agentIds: Array.from({length: 201}, (_, i) => id(i + 10)),
  },
  members: Array.from({length: 201}, (_, i) => member(i + 10)),
  memberTotal: 240,
  canEdit: false,
  canChangeMembership: false,
  blockers: ['TEAM_SIZE_UNSUPPORTED'],
};
assert.equal(vc(large), true);
assert.equal(vc({...large, canEdit: true}), false);
for (const patch of [
  {expectedRevision: 2147483648},
  {expectedRevision: '0'},
  {expectedFingerprint: 'b'},
  {agentIds: [id(7), id(7)]},
  {managerAgentId: id(7)},
  {name: ' x '},
  {purpose: ''},
  {description: 'x\t'},
  {intake: null},
  {concurrency: '4'},
  {monthlyBudget: '100'},
  {monthlyBudget: 100.001},
  {acknowledgement: false},
  {acknowledgement: 'true'},
  {currency: 'USD'},
])
  assert.equal(
    v.validSettingsBody({...body, ...patch}),
    false,
    JSON.stringify(patch),
  );
assert.equal(v.validSettingsBody(body), true);
assert.equal(v.validSettingsBody({...body, description: '  exact  '}), true);
assert.equal(
  v.validSettingsBody({
    ...body,
    agentIds: Array.from({length: 200}, (_, i) => id(i + 10)),
  }),
  false,
);
for (const x of [0, 0.01, 0.29, 1, 1.1, 1000000])
  assert.equal(v.budget(x), true);
for (const x of [-1, 1000000.01, NaN, Infinity, '1', null, 0.001])
  assert.equal(v.budget(x), false);
assert.equal(v.safeText('😀'.repeat(255), 255, true), true);
assert.equal(v.safeText('😀'.repeat(256), 255, true), false);
for (let i = 0; i < 32; i++)
  assert.equal(v.safeText('x' + String.fromCharCode(i), 255), false);
for (const s of ['\ud800', '\udfff', 'a\ud800b', 'a\udfffb', '\x7f'])
  assert.equal(v.safeText(s, 4000), false);
assert.equal(v.strip('\u2000 x \u3000'), 'x');
assert.equal(v.strip('\u00a0x\u202f'), '\u00a0x\u202f');
for (const s of [
  '8080000A-0000-4000-8000-000000000001',
  '80800000-0000-6000-8000-000000000001',
  '80800000-0000-4000-0000-000000000001',
])
  assert.equal(v.settingsUuid(s), false);
for (const s of [
  '2026-02-30T12:30:00.123456Z',
  '2026-10-25T24:00:00.000000Z',
  '2026-10-25T12:30:00.123Z',
  '2026-10-25T12:30:00.1234567Z',
])
  assert.equal(v.timestamp(s), false);
const candidate = {
  id: id(9),
  name: 'Candidate',
  level: 'MANAGER',
  status: 'ACTIVE',
};
const page = {
  organizationId: org,
  teamId: team,
  checkedAt: time,
  items: [candidate],
  page: 1,
  size: 20,
  total: 21,
};
assert.equal(v.validSettingsCandidates(page, org, team, 1), true);
for (const patch of [
  {organizationId: actor},
  {page: 0},
  {size: 21},
  {total: 22},
  {items: [{...candidate, status: 'SUSPENDED'}]},
  {items: [{...candidate, private: 'secret'}]},
  {items: [candidate, candidate]},
])
  assert.equal(
    v.validSettingsCandidates({...page, ...patch}, org, team, 1),
    false,
  );
assert.equal(
  v.validSettingsCandidates(page, org, team, 1, [candidate.id]),
  false,
);
assert.equal(v.validSettingsReceipt(receipt, locator, body, state), true);
assert.equal(v.validSettingsReceipt(receipt, locator), true);
for (const patch of [
  {actorId: team},
  {organizationId: actor},
  {teamId: actor},
  {idempotencyKey: id(9)},
  {revision: 0},
  {revision: 2},
  {revision: 2147483648},
  {eventId: 'invalid'},
  {recordedAt: '2026-02-30T00:00:00.000000Z'},
  {extra: 'private'},
  {before: {...state, name: 'Other'}},
  {after: {...receipt.after, concurrency: 9}},
])
  assert.equal(
    v.validSettingsReceipt({...receipt, ...patch}, locator, body, state),
    false,
    JSON.stringify(patch),
  );
assert.equal(
  v.validSettingsReceipt(
    {...receipt, revision: 2147483647},
    locator,
    {...body, expectedRevision: 2147483646},
    state,
  ),
  true,
);
assert.equal(
  v.validSettingsReceipt({...receipt, after: state}, locator),
  false,
);
assert.equal(
  v.validSettingsReceipt(
    {
      ...receipt,
      before: {...state, status: 'ARCHIVED'},
      after: {...receipt.after, status: 'ARCHIVED'},
    },
    locator,
  ),
  false,
);
const Component = load('team-settings.component').TeamSettingsComponent;
function component(c = context) {
  const x = Object.create(Component.prototype);
  Object.assign(x, {
    context: signal(c),
    workspace: signal(null),
    candidates: signal(null),
    receipt: signal(null),
    loading: signal(false),
    candidateLoading: signal(false),
    busy: signal(false),
    denied: signal(false),
    verified: signal(true),
    error: signal(''),
    candidateError: signal(''),
    stale: signal(false),
    phase: signal('edit'),
    tab: signal('info'),
    additions: signal([]),
    acknowledged: false,
    epoch: 0,
    candidateEpoch: 0,
    actor,
    id: org,
    teamId: team,
    destroy: {destroyed: false},
    router: {getCurrentNavigation: () => ({id: 1})},
    auth: {
      user: () => ({id: actor}),
      session: async () => ({id: actor}),
      getWorkspace: async () => ({id: org, role: 'ADMIN'}),
    },
    draft: {
      name: 'Updated',
      purpose: 'Purpose',
      description: null,
      managerAgentId: id(6),
      selected: [id(6), id(7)],
      concurrency: '4',
      monthlyBudget: '100',
    },
    focusHeading: () => {},
    closeMenu: () => {},
  });
  return x;
}
(async () => {
  const x = component(legacy);
  assert.ok(x.body(), 'unchanged ineligible manager survives');
  x.additions.set([
    {id: id(9), name: 'New', level: 'MANAGER', status: 'ACTIVE'},
  ]);
  x.draft.selected.push(id(9));
  x.draft.managerAgentId = id(9);
  assert.ok(x.body());
  assert.equal(x.draft.selected.includes(id(6)), true);
  x.additions.set([
    {id: id(9), name: 'New', level: 'MANAGER', status: 'SUSPENDED'},
  ]);
  assert.equal(x.body(), null);
  const a = component(active);
  a.draft.selected.push(id(9));
  assert.equal(a.body(), null);
  const n = component();
  n.draft.name = 'Team';
  n.review();
  assert.equal(n.phase(), 'edit');
  n.draft.name = 'Updated';
  n.review();
  assert.equal(n.phase(), 'review');
  assert.equal(n.acknowledged, false);
  let posts = 0;
  const s = component();
  s.phase.set('review');
  s.acknowledged = true;
  s.api = {
    csrf: async () => ({}),
    context: async () => ({...context, fingerprint: 'b'.repeat(64)}),
    save: async () => {
      posts++;
    },
  };
  await s.save();
  assert.equal(posts, 0);
  assert.equal(s.stale(), true);
  assert.equal(s.draft.name, 'Updated');
  const u = component();
  u.phase.set('review');
  u.acknowledged = true;
  let originalBody, originalKey;
  u.api = {
    csrf: async () => ({}),
    context: async () => context,
    save: async (o, t, b, k) => {
      posts++;
      originalBody ??= JSON.stringify(b);
      originalKey ??= k;
      assert.equal(JSON.stringify(b), originalBody);
      assert.equal(k, originalKey);
      throw Error('lost reply');
    },
    receipt: async () => null,
  };
  await u.save();
  assert.equal(u.phase(), 'unknown');
  assert.ok(u.frozen);
  const count = posts;
  await u.recover();
  assert.equal(posts, count);
  assert.equal(u.phase(), 'unknown');
  await u.recover(true);
  assert.equal(posts, count + 1);
  const loc = u.locator;
  u.loseAuthority();
  assert.equal(u.frozen, null);
  assert.equal(u.context(), null);
  assert.equal(u.draft.name, '');
  assert.equal(u.locator, loc);
  assert.equal(u.canReplay(), false);
  assert.equal(u.active(u.epoch - 1), false);
  u.api.receipt = async () => ({...receipt, idempotencyKey: loc.key});
  await u.recover();
  assert.equal(u.phase(), 'result');
  // Commit at the server, hold its reply, observe account/role loss while busy,
  // then release the old receipt. Its actor's private state must never reappear.
  for (const mode of ['account', 'role']) {
    const held = component();
    held.phase.set('review');
    held.acknowledged = true;
    let release, arrived, committedKey;
    const observed = new Promise(resolve => {
      arrived = resolve;
    });
    const response = new Promise(resolve => {
      release = resolve;
    });
    held.api = {
      csrf: async () => ({}),
      context: async () => context,
      save: async (o, t, b, k) => {
        committedKey = k;
        arrived();
        return response;
      },
    };
    const writing = held.save();
    await observed;
    assert.equal(held.busy(), true);
    let sessions = 0,
      workspaces = 0;
    held.auth.session = async () => {
      sessions++;
      return {id: mode === 'account' ? id(99) : actor};
    };
    held.auth.getWorkspace = async () => {
      workspaces++;
      return {id: org, role: 'MEMBER'};
    };
    await held.revalidate();
    assert.equal(sessions, 1, 'busy focus must refresh real session');
    if (mode === 'role')
      assert.equal(
        workspaces,
        1,
        'busy role observation must refresh workspace',
      );
    assert.equal(held.context(), null);
    assert.equal(held.frozen, null);
    assert.equal(held.draft.name, '');
    assert.equal(held.acknowledged, false);
    assert.equal(held.locator.key, committedKey);
    assert.equal(held.canReplay(), false);
    release({...receipt, idempotencyKey: committedKey});
    await writing;
    assert.equal(
      held.receipt(),
      null,
      'late committed reply cannot restore private result',
    );
    assert.equal(held.phase(), 'unknown');
    assert.equal(held.context(), null);
  }
  const g = component();
  g.phase.set('unknown');
  g.canLeave();
  g.canLeave();
  assert.equal(prompts, 1, 'exactly one confirmation per accepted navigation');
  g.router.getCurrentNavigation = () => ({id: 2});
  g.canLeave();
  assert.equal(prompts, 2);
  console.log(
    'PASS: Team settings production schemas, identity/revision/Unicode/money bounds, legacy manager preservation, ACTIVE membership, no-op/stale preflight, frozen UNKNOWN replay, opaque recovery, busy account/role loss with held committed replies, navigation dedup',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
