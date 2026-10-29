const assert = require('node:assert/strict');
const fs = require('node:fs');
const ts = require('typescript');
const vm = require('node:vm');
const root = require('node:path').join(
  __dirname,
  '../src/app/features/task-owner/',
);
function load(file, mocks = {}) {
  const module = {exports: {}};
  const code = ts.transpileModule(
    fs.readFileSync(root + file + '.ts', 'utf8'),
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
    TextEncoder,
    Date,
    Set,
    Reflect,
    Object,
    JSON,
    Number,
    structuredClone,
    queueMicrotask,
  });
  return module.exports;
}
const v = load('task-owner-validation');
const id = '12345678-1234-4234-8234-123456789abc';
const scope = {organizationId: id, teamId: id, missionId: id, taskId: id};
const a = {id, name: 'Agent', level: 'WORKER', status: 'ACTIVE'};
const c = {...a, fingerprint: 'a'.repeat(64)};
const before = {state: 'UNAVAILABLE', agent: null},
  after = {state: 'UNASSIGNED', agent: null};
assert(v.owner(before));
assert(v.newOwnerSnapshot(after));
assert(!v.newOwnerSnapshot(before));
assert(v.agent(a));
assert(!v.agent(c));
assert(v.candidate(c));
assert(!v.candidate(a));
for (const bad of ['\u0085', '\u009f', '\ud800'])
  assert(!v.agent({...a, name: bad}));
const body = {
  expectedRevision: 0,
  expectedFingerprint: 'b'.repeat(64),
  ownerAgentId: null,
  ownerFingerprint: null,
};
assert(v.validBody(body));
assert(!v.validBody({...body, extra: true}));
const receipt = {
  ...scope,
  eventId: id,
  revision: 1,
  committedAt: '2026-10-28T12:00:00Z',
  before,
  after,
};
assert(v.validReceipt(receipt, scope, body, before));
assert(!v.validReceipt({...receipt, before: after}, scope));
assert(!v.validReceipt({...receipt, after: before}, scope));
const context = {
  ...scope,
  checkedAt: receipt.committedAt,
  revision: 0,
  fingerprint: body.expectedFingerprint,
  teamName: 'Team',
  missionTitle: 'Mission',
  taskTitle: '',
  taskStatus: 'DRAFT',
  owner: before,
  canEdit: true,
  blockers: [],
};
assert(v.validContext(context, scope));
assert(!v.validContext({...context, extra: 0}, scope));
assert(
  v.validPage(
    {...scope, page: 0, pageSize: 20, total: 1, query: '%', items: [c]},
    scope,
    0,
    '%',
  ),
);
assert(
  !v.validPage(
    {...scope, page: 0, pageSize: 20, total: 2, query: '', items: [c, c]},
    scope,
    0,
    '',
  ),
);
class HttpErrorResponse extends Error {
  constructor(status) {
    super(`HTTP ${status}`);
    this.status = status;
  }
}
const noop = () => () => {};
const component = load('task-owner.component', {
  '@angular/core': {Component: noop, HostListener: noop},
  './task-owner-validation': v,
  '@angular/common/http': {HttpErrorResponse},
  '../../core/auth/auth.service': {AuthFailure: class extends Error {}},
}).TaskOwnerComponent.prototype;
const signal = x => {
  const f = () => x;
  f.set = y => (x = y);
  return f;
};
const state = {
  explicitSelection: false,
  original: JSON.stringify({ownerAgentId: null}),
  draft: {ownerAgentId: null},
  context: () => context,
};
assert(!component.dirty.call(state));
state.explicitSelection = true;
assert(component.dirty.call(state));
state.context = () => ({...context, owner: after});
assert(!component.dirty.call(state));
(async () => {
  const s = {
    selected: c,
    api: {candidate: async () => ({...c, fingerprint: 'c'.repeat(64)})},
    scope: () => scope,
    cancel: {},
    active: () => true,
    explicitSelection: true,
    acknowledged: true,
    phase: signal('review'),
    error: signal(''),
  };
  assert.equal(await component.checkCandidate.call(s, 1), false);
  assert.equal(s.selected, null);
  assert.equal(s.explicitSelection, false);
  assert.equal(s.acknowledged, false);
  assert.equal(s.phase(), 'edit');
  console.log('Owner production validators and state harness passed');
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});

assert.equal(
  component.describeOwner(before),
  'Agent đã được cấu hình nhưng không còn khả dụng trong team này',
);
assert.equal(component.describeOwner(after), 'Không gán Agent');
assert(
  component.describeOwner
    .call(component, {state: 'ASSIGNED', agent: {...a, status: 'ARCHIVED'}})
    .includes('Agent · WORKER · Đã lưu trữ'),
);
for (const b of context.blockers.concat([
  'TASK_NOT_DRAFT',
  'TEAM_ARCHIVED',
  'MISSION_NOT_EDITABLE',
  'INTAKE_PAUSED',
  'REVISION_EXHAUSTED',
  'UNKNOWN_STATE',
]))
  assert(!component.blockerLabel(b).includes(b));

(async () => {
  const pending = [];
  let authorized = 0;
  const s = {
    editable: () => true,
    query: '',
    epoch: 1,
    searchEpoch: 0,
    chooserLoading: signal(false),
    chooserError: signal(''),
    candidates: signal({old: true}),
    scope: () => scope,
    active: e => e === 1,
    cancel: {},
    authorize: async () => {
      authorized++;
      return true;
    },
    api: {
      candidates: () =>
        new Promise((resolve, reject) => pending.push({resolve, reject})),
    },
  };
  const first = component.search.call(s, 0);
  assert.equal(s.candidates(), null);
  assert.equal(s.chooserLoading(), true);
  await new Promise(resolve => setImmediate(resolve));
  const second = component.search.call(s, 0);
  await new Promise(resolve => setImmediate(resolve));
  const page = {
    ...scope,
    page: 0,
    pageSize: 20,
    total: 1,
    query: '',
    items: [c],
  };
  pending[1].resolve(page);
  await second;
  pending[0].reject(Error('old outage'));
  await first;
  assert.equal(s.candidates(), page);
  assert.equal(s.chooserError(), '');
  const late = component.search.call(s, 0);
  await new Promise(resolve => setImmediate(resolve));
  s.element = {nativeElement: {querySelector: () => null}};
  component.chooserClosed.call(s);
  pending[2].resolve(page);
  await late;
  assert.equal(s.candidates(), null);
  assert.equal(s.chooserLoading(), false);
  const failed = component.search.call(s, 0);
  await new Promise(resolve => setImmediate(resolve));
  pending[3].reject(Error('outage'));
  await failed;
  assert(s.chooserError());
  const retry = component.search.call(s, 0);
  await new Promise(resolve => setImmediate(resolve));
  pending[4].resolve(page);
  await retry;
  assert.equal(s.chooserError(), '');
  assert.equal(s.candidates(), page);
  assert.equal(authorized, 5);
  console.log(
    'Owner picker authorization, stale error, close fence and GET retry passed',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});

(async () => {
  const s = {
    receipt: signal(null),
    phase: signal('review'),
    stale: signal(true),
    error: signal('old'),
    explicitSelection: true,
    selected: c,
    source: {revision: 0},
    draft: {ownerAgentId: id},
    acknowledged: true,
    empty: component.empty,
    focusHeading: () => {},
    context: () => context,
    original: '',
  };
  component.accept.call(s, receipt);
  assert.equal(component.dirty.call(s), false);
  assert.equal(s.source, null);
  assert.equal(s.stale(), false);
  assert.equal(s.receipt(), receipt);
  assert.equal(s.phase(), 'saved');
  assert.equal(
    component.canReplay.call({
      frozen: {},
      verified: () => false,
      denied: () => false,
    }),
    true,
  );
  for (const workspace of [
    async () => {
      throw Error('503');
    },
    async () => ({id, role: 'ADMIN'}),
  ]) {
    let cleared = 0;
    const a = {
      actor: id,
      active: () => true,
      auth: {session: async () => ({id})},
      api: {workspace},
      id,
      cancel: {},
      error: signal(''),
      loseAuthority: () => {
        cleared++;
      },
    };
    await assert.rejects(component.authorize.call(a, 1));
    assert.equal(cleared, 1);
    assert(a.error());
  }
  console.log(
    'Owner saved retirement and failed/malformed authority checks passed',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});

(async () => {
  const calls = [];
  const s = {
    actor: id,
    searchEpoch: 1,
    active: () => true,
    auth: {
      session: async () => {
        calls.push('session');
        return {id};
      },
    },
    api: {
      workspace: async () => {
        calls.push('workspace');
        return {id, name: 'Workspace', role: 'ADMIN'};
      },
    },
    id,
    cancel: {},
    denied: signal(true),
    verified: signal(false),
    loseAuthority: () => {
      throw Error('unexpected authority loss');
    },
  };
  assert.equal(await component.authorize.call(s, 1, 1), true);
  assert.equal(await component.authorize.call(s, 1, 1), true);
  assert.deepEqual(calls, ['session', 'workspace', 'session', 'workspace']);
  s.searchEpoch = 2;
  assert.equal(await component.authorize.call(s, 1, 1), false);
  assert.equal(calls.filter(x => x === 'workspace').length, 2);
  console.log(
    'Owner fresh session/workspace ADMIN checks and search epoch fence passed',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});

{
  const s = {
    actor: id,
    locator: {...scope, actor: id, key: id},
    epoch: 1,
    cancel: {next() {}},
    context: signal(context),
    receipt: signal(receipt),
    query: 'private',
    page: 1,
    closeChooser() {},
    closeMenu() {},
    candidates: signal({items: [c]}),
    searchEpoch: 1,
    chooserLoading: signal(true),
    chooserError: signal('private'),
    selected: c,
    explicitSelection: true,
    source: {},
    frozen: {body},
    draft: {ownerAgentId: id},
    original: 'private',
    acknowledged: true,
    verified: signal(true),
    loading: signal(true),
    busy: signal(true),
    error: signal('private'),
    stale: signal(true),
    denied: signal(false),
    phase: signal('review'),
    empty: component.empty,
    clearPrivate: component.clearPrivate,
  };
  component.loseAuthority.call(s);
  for (const key of ['selected', 'source', 'frozen'])
    assert.equal(s[key], null);
  assert.equal(s.context(), null);
  assert.equal(s.receipt(), null);
  assert.equal(s.candidates(), null);
  assert.equal(s.locator.actor, id);
  assert.equal(s.phase(), 'unknown');
  assert.equal(s.verified(), false);
  s.actor = 'another-actor';
  component.loseAuthority.call(s);
  assert.equal(s.locator, null);
  console.log(
    'Owner private state clearing and same-actor opaque locator retention passed',
  );
}

function recoveryState() {
  const s = Object.create(component);
  Object.assign(s, {
    actor: id,
    id,
    epoch: 1,
    searchEpoch: 0,
    locator: {...scope, actor: id, key: id},
    frozen: {body: Object.freeze({...body}), before},
    cancel: {next() {}},
    active(e) {
      return e === this.epoch;
    },
    scope: () => scope,
    closeChooser() {},
    closeMenu() {},
    focusHeading() {},
    context: signal(context),
    receipt: signal(null),
    candidates: signal({items: [c]}),
    chooserLoading: signal(false),
    chooserError: signal(''),
    selected: c,
    explicitSelection: true,
    source: {},
    draft: {ownerAgentId: id},
    original: '',
    acknowledged: false,
    verified: signal(true),
    denied: signal(false),
    loading: signal(false),
    busy: signal(false),
    error: signal(''),
    stale: signal(false),
    phase: signal('unknown'),
    auth: {session: async () => ({id})},
  });
  return s;
}
(async () => {
  for (const failure of ['503', 'malformed']) {
    for (const recovery of ['retry', 'lookup']) {
      const s = recoveryState();
      const frozen = s.frozen;
      const locator = s.locator;
      const calls = [];
      let saves = 0;
      s.auth.session = async () => {
        calls.push('session');
        return {id};
      };
      s.api = {
        csrf: async () => {
          calls.push('csrf');
          return {};
        },
        workspace: async () => {
          calls.push('workspace');
          return {id, name: 'Workspace', role: 'ADMIN'};
        },
        save: async (p, b, key) => {
          saves++;
          assert.equal(p, locator);
          assert.equal(b, frozen.body);
          assert.equal(key, locator.key);
          if (saves === 1) {
            if (failure === '503') throw new HttpErrorResponse(503);
            return {malformed: true};
          }
          assert.deepEqual(calls, ['csrf', 'session', 'workspace']);
          return receipt;
        },
        receipt: async (p, key) => {
          assert.equal(p, locator);
          assert.equal(key, locator.key);
          assert.deepEqual(calls, ['session', 'workspace']);
          return {receipt};
        },
      };
      await s.postFrozen(1, {});
      assert.equal(s.phase(), 'unknown');
      assert.equal(s.verified(), false);
      assert.equal(s.frozen, frozen);
      assert.equal(s.canReplay(), true);
      await s.recover(recovery === 'retry');
      assert.equal(s.phase(), 'saved');
      assert.equal(s.receipt(), receipt);
      assert.equal(saves, recovery === 'retry' ? 2 : 1);
    }
  }
  for (const failure of ['session503', 'workspace503', 'malformedWorkspace']) {
    for (const replay of [false, true]) {
      const s = recoveryState();
      const locator = s.locator;
      let writes = 0;
      let lookups = 0;
      s.auth.session = async () => {
        if (failure === 'session503') throw new HttpErrorResponse(503);
        return {id};
      };
      s.api = {
        csrf: async () => ({}),
        workspace: async () => {
          if (failure === 'workspace503') throw new HttpErrorResponse(503);
          return {id, role: 'ADMIN'};
        },
        save: async () => {
          writes++;
        },
        receipt: async () => {
          lookups++;
        },
      };
      await s.recover(replay);
      assert.equal(s.frozen, null);
      assert.equal(s.denied(), true);
      assert.equal(s.canReplay(), false);
      assert.equal(s.context(), null);
      assert.equal(s.selected, null);
      assert.equal(s.candidates(), null);
      assert.equal(s.locator, locator);
      assert.equal(s.busy(), false);
      await s.recover(true);
      assert.equal(writes, 0);
      assert.equal(lookups, 0);
    }
  }
  console.log(
    'Owner 503/malformed reply direct exact retry + lookup and authority-failure body erasure passed',
  );
})().catch(e => {
  console.error(e);
  process.exitCode = 1;
});
