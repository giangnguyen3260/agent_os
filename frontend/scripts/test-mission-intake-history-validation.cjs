// Exercise production wire validation and component authorization/privacy fences.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const {Subject} = require('rxjs');
const root = path.join(__dirname, '../src/app/features');
const cache = new Map();
const document = {visibilityState: 'visible'};
class HttpErrorResponse extends Error {
  constructor(status) {
    super('HTTP');
    this.status = status;
  }
}
class AuthFailure extends Error {}
function load(file) {
  file = path.resolve(file);
  if (cache.has(file)) return cache.get(file);
  const mod = {exports: {}};
  const code = ts.transpileModule(fs.readFileSync(file + '.ts', 'utf8'), {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
      experimentalDecorators: true,
    },
  }).outputText;
  vm.runInNewContext(code, {
    module: mod,
    exports: mod.exports,
    document,
    require: key => {
      if (key === '../../core/auth/auth.service') return {AuthFailure};
      if (key.startsWith('.'))
        return load(path.resolve(path.dirname(file), key));
      if (key === '@angular/core')
        return {
          Component: () => x => x,
          Injectable: () => x => x,
          HostListener: () => () => {},
        };
      if (key === '@angular/common/http') return {HttpErrorResponse};
      return {};
    },
  });
  cache.set(file, mod.exports);
  return mod.exports;
}
const v = load(
  path.join(root, 'mission-intake-history/mission-intake-history-validation'),
);
const C = load(
  path.join(root, 'mission-intake-history/mission-intake-history.component'),
).MissionIntakeHistoryComponent;
const Menu = load(
  path.join(root, 'mission-intake/safe-native-menu'),
).SafeNativeMenu;
const id = n => '80800000-0000-4000-8000-' + String(n).padStart(12, '0');
const scope = {organizationId: id(1), teamId: id(2), missionId: id(3)};
const item = {
  eventId: id(9),
  versionBefore: '9007199254740992',
  versionAfter: '9007199254740993',
  pausedBefore: false,
  pausedAfter: true,
  committedAt: '2026-10-28T12:00:00.123456789Z',
  actorName: null,
};
const page = {
  ...scope,
  teamName: 'Actual Team',
  missionTitle: 'Actual Mission',
  page: 0,
  pageSize: 20,
  total: 1,
  items: [item],
};
let checks = 0;
function valid(p, expected, n = 0) {
  assert.equal(v.validHistory(p, scope, n), expected);
  checks++;
}
valid(page, true);
// Every ISO C1 control must fail each visible history name field.
for (let code = 0x80; code <= 0x9f; code++) {
  const name = 'Visible' + String.fromCharCode(code) + 'Name';
  assert.equal(v.safeHistoryName(name), false);
  valid({...page, teamName: name}, false);
  valid({...page, missionTitle: name}, false);
  valid({...page, items: [{...item, actorName: name}]}, false);
}
assert.equal(v.safeHistoryName('Team 🌲'), true);
valid(
  {
    ...page,
    teamName: 'Team 🌲',
    missionTitle: 'Mission 🚀',
    items: [{...item, actorName: 'Người 🌳'}],
  },
  true,
);
// Scope names use the same 255-code-point bound as backend historyName.
for (const field of ['teamName', 'missionTitle']) {
  valid({...page, [field]: 'x'.repeat(255)}, true);
  valid({...page, [field]: 'x'.repeat(256)}, false);
  valid({...page, [field]: '🌲'.repeat(255)}, true);
  valid({...page, [field]: '🌲'.repeat(256)}, false);
}
for (const patch of [
  {extra: true},
  {teamId: id(4)},
  {page: 1},
  {pageSize: 19},
  {total: -1},
  {total: 1.5},
  {total: Number.MAX_SAFE_INTEGER + 1},
  {total: '1'},
  {items: []},
  {teamName: '\ud800'},
  {missionTitle: ' '},
  {actorId: id(7)},
])
  valid({...page, ...patch}, false);
for (const patch of [
  {actorName: ''},
  {actorName: '\nSecret'},
  {actorName: '\ud800'},
  {actorName: 'x'.repeat(256)},
  {actorId: id(7)},
  {versionBefore: 9007199254740992},
  {versionAfter: '9007199254740994'},
  {versionBefore: '9223372036854775807', versionAfter: '9223372036854775808'},
  {versionBefore: '00'},
  {pausedAfter: false},
  {committedAt: '2026-02-30T00:00:00Z'},
  {eventId: 'AAAAAAAA-0000-4000-8000-000000000009'},
])
  valid({...page, items: [{...item, ...patch}]}, false);
valid({...page, items: [{...item, actorName: 'Người hiện tại'}]}, true);
valid({...page, total: 0, items: []}, true);
valid({...page, page: 2, items: []}, true, 2);
valid(
  {
    ...page,
    total: 2,
    items: [
      item,
      {
        ...item,
        eventId: id(8),
        versionBefore: '42',
        versionAfter: '43',
        committedAt: '2026-10-28T12:00:00.123456788Z',
      },
    ],
  },
  true,
);
valid({...page, total: 2, items: [item, {...item, eventId: id(8)}]}, true);
valid({...page, total: 2, items: [item, {...item, eventId: id(10)}]}, false);
valid(
  {
    ...page,
    total: 2,
    items: [
      item,
      {...item, eventId: id(8), committedAt: '2026-10-28T12:00:00.123456790Z'},
    ],
  },
  false,
);
valid({...page, total: 2, items: [item, item]}, false);
valid(
  {
    ...page,
    total: 2,
    items: [
      {...item, committedAt: '2026-10-28T12:00:00Z'},
      {...item, eventId: id(8), committedAt: '2026-10-28T12:00:00.000000000Z'},
    ],
  },
  true,
);
const twenty = Array.from({length: 20}, (_, i) => ({
  ...item,
  eventId: id(100 - i),
}));
valid({...page, total: 21, items: twenty}, true);
valid({...page, page: 1, total: 21}, true, 1);
for (const raw of ['00', '-1', '1.0', '1e2', ' 1', '100001', '1\n'])
  assert.equal(v.pageNumber(raw), -1);
assert.equal(v.pageNumber('100000'), 100000);
const signal = initial => {
  let value = initial;
  const s = () => value;
  s.set = x => {
    value = x;
  };
  return s;
};
function component() {
  const c = Object.create(C.prototype);
  let actor = id(4);
  Object.assign(c, {
    id: scope.organizationId,
    teamId: scope.teamId,
    missionId: scope.missionId,
    page: 0,
    epoch: 0,
    actor,
    invalid: false,
    cancel: new Subject(),
    destroy: {destroyed: false},
    element: {nativeElement: {querySelector: () => null}},
    auth: {
      user: () => ({id: actor}),
      session: async () => ({
        id: actor,
        email: 'private@example.test',
        displayName: 'Admin',
      }),
    },
    api: {
      workspace: async () => ({
        id: scope.organizationId,
        name: 'Actual Workspace',
        role: 'ADMIN',
      }),
      history: async () => structuredClone(page),
    },
    history: signal(null),
    workspaceName: signal(''),
    loading: signal(false),
    denied: signal(false),
    error: signal(''),
    menuOpen: signal(false),
    switchActor: () => {
      actor = id(5);
    },
  });
  return c;
}
async function main() {
  let c = component();
  await c.load();
  assert(c.history());
  assert.equal(c.loading(), false);
  for (let code = 0x80; code <= 0x9f; code++) {
    c = component();
    let reads = 0;
    c.api.workspace = async () => ({
      id: scope.organizationId,
      name: 'Workspace' + String.fromCharCode(code),
      role: 'ADMIN',
    });
    c.api.history = async () => {
      reads++;
      return page;
    };
    await c.load();
    assert(c.error());
    assert.equal(reads, 0);
    assert.equal(c.history(), null);
    assert.equal(c.workspaceName(), '');
  }
  for (const name of [' ', 'x'.repeat(256)]) {
    c = component();
    c.api.workspace = async () => ({
      id: scope.organizationId,
      name,
      role: 'ADMIN',
    });
    await c.load();
    assert(c.error());
    assert.equal(c.workspaceName(), '');
  }
  c = component();
  c.api.workspace = async () => ({
    id: scope.organizationId,
    name: '🌲'.repeat(255),
    role: 'ADMIN',
  });
  await c.load();
  assert(c.history());
  assert.equal(c.workspaceName(), '🌲'.repeat(255));
  for (const role of ['MEMBER', 'admin', null]) {
    c = component();
    let reads = 0;
    c.api.workspace = async () => ({
      id: scope.organizationId,
      name: 'Private',
      role,
    });
    c.api.history = async () => {
      reads++;
      return page;
    };
    await c.load();
    assert(c.denied());
    assert.equal(reads, 0);
    assert.equal(c.history(), null);
    assert.equal(c.workspaceName(), '');
  }
  c = component();
  c.auth.session = async () => ({id: id(99), email: 'x', displayName: 'Wrong'});
  await c.load();
  assert(c.denied());
  assert.equal(c.history(), null);
  c = component();
  c.api.history = async () => ({...page, email: 'leak'});
  await c.load();
  assert(c.error());
  assert.equal(c.history(), null);
  for (const status of [401, 403, 404]) {
    c = component();
    c.api.history = async () => {
      throw new HttpErrorResponse(status);
    };
    await c.load();
    assert(c.denied());
    assert.equal(c.history(), null);
  }
  for (const action of ['hide', 'scope', 'account', 'destroy', 'refresh']) {
    c = component();
    let finish;
    c.api.history = () =>
      new Promise(resolve => {
        finish = resolve;
      });
    const pending = c.load();
    await new Promise(resolve => setImmediate(resolve));
    assert(finish);
    if (action === 'hide') {
      document.visibilityState = 'hidden';
      c.onVisibility();
    }
    if (action === 'scope') {
      c.clear();
      c.missionId = id(8);
    }
    if (action === 'account') c.switchActor();
    if (action === 'destroy') {
      c.clear();
      c.destroy.destroyed = true;
    }
    if (action === 'refresh') c.clear();
    finish(page);
    await pending;
    assert.equal(c.history(), null);
    assert.equal(c.workspaceName(), '');
    document.visibilityState = 'visible';
  }
  c = component();
  let reject;
  c.api.history = () =>
    new Promise((_, fail) => {
      reject = fail;
    });
  const pending = c.load();
  await new Promise(resolve => setImmediate(resolve));
  c.clear();
  reject(new Error('private'));
  await pending;
  assert.equal(c.error(), '');
  let closes = 0,
    opens = 0,
    focuses = 0;
  const dialog = {
    open: false,
    showModal() {
      this.open = true;
      opens++;
    },
    close() {
      assert(this.open);
      this.open = false;
      closes++;
    },
  };
  const menu = new Menu(() => ({
    querySelector: selector =>
      selector === 'dialog'
        ? dialog
        : {
            focus() {
              focuses++;
            },
          },
  }));
  menu.close();
  menu.open();
  menu.open();
  menu.close();
  menu.close();
  assert.equal(opens, 1);
  assert.equal(closes, 1);
  assert.equal(focuses, 3);
  console.log(
    `PASS: ${checks} wire cases; canonical pages; ADMIN/session, privacy/late response fences; native menu guard.`,
  );
}
main().catch(error => {
  console.error(error);
  process.exitCode = 1;
});
