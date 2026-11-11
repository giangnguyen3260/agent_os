// Transpile and execute production validators, never test copies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {test} = require('node:test');
const ts = require('typescript');
const directory = path.resolve(
  __dirname,
  '../src/app/features/provider-connections',
);
const cache = new Map();
function load(file) {
  if (cache.has(file)) return cache.get(file);
  const mod = {exports: {}};
  cache.set(file, mod.exports);
  const code = ts.transpileModule(fs.readFileSync(file, 'utf8'), {
    compilerOptions: {
      target: ts.ScriptTarget.ES2022,
      module: ts.ModuleKind.CommonJS,
    },
  }).outputText;
  vm.runInNewContext(
    code,
    {
      module: mod,
      exports: mod.exports,
      Date,
      Reflect,
      BigInt,
      require: name => {
        assert.ok(name.startsWith('.'));
        return load(path.resolve(path.dirname(file), name + '.ts'));
      },
    },
    {filename: file},
  );
  return mod.exports;
}
const {verifyRenamed, receiptFoundRename, verifyDisabled, receiptFoundDisable} =
  load(path.join(directory, 'provider-rename-wire.ts'));
const {verifyCreated} = load(path.join(directory, 'provider-create-wire.ts'));
const org = '11111111-1111-4111-8111-111111111111';
const actor = '22222222-2222-4222-8222-222222222222';
const target = '33333333-3333-4333-8333-333333333333';
const foreign = '44444444-4444-4444-8444-444444444444';
const submitted = {
  connectionId: target,
  expectedRevision: '7',
  name: 'Reviewed new name',
};
function fixture(provider = 'OPENAI', state = 'ENABLED', workspaceId = null) {
  const before = {
    organizationId: org,
    id: target,
    provider,
    credentialMode: {
      OPENAI: 'OPENAI_API_KEY',
      ANTHROPIC: 'ANTHROPIC_API_KEY',
      GOOGLE_GEMINI: 'GEMINI_API_KEY',
    }[provider],
    name: 'Old name',
    options: provider === 'ANTHROPIC' ? {workspaceId} : {},
    state,
    revision: '7',
    credentialRevision: '3',
    createdAt: '2026-11-10T01:02:03.000001Z',
    updatedAt: '2026-11-10T01:02:03.000002Z',
  };
  return {
    data: {
      organizationId: org,
      commandId: foreign,
      operation: 'RENAME',
      connectionId: target,
      actorId: actor,
      committedAt: '2026-11-10T01:02:03.000004Z',
      before,
      after: {
        ...structuredClone(before),
        name: submitted.name,
        revision: '8',
        updatedAt: '2026-11-10T01:02:03.000003Z',
      },
      checkId: null,
    },
    meta: {requestId: foreign},
  };
}
function verify(value, command = submitted) {
  verifyRenamed(value, org, actor, command);
}
function reject(value, command = submitted) {
  assert.throws(
    () => verify(value, command),
    e => /^Invalid Rename (receipt|response)$/.test(e.message),
  );
}
function mutate(change) {
  const value = fixture();
  change(value);
  reject(value);
}
function lookup(value) {
  return {...value, data: {receipt: value.data}};
}

test('all provider profiles, exact options and both states', () => {
  for (const provider of ['OPENAI', 'ANTHROPIC', 'GOOGLE_GEMINI'])
    for (const state of ['ENABLED', 'DISABLED']) {
      for (const workspace of provider === 'ANTHROPIC'
        ? [null, 'wrkspc_Reviewed1']
        : [null]) {
        const value = fixture(provider, state, workspace);
        verify(value);
        assert.equal(
          receiptFoundRename(lookup(value), org, actor, target, submitted),
          true,
        );
      }
    }
});
test('BigInt adjacency at MAX and beyond number precision', () => {
  for (const revision of ['9007199254740993', '9223372036854775806']) {
    const value = fixture();
    value.data.before.revision = revision;
    value.data.before.credentialRevision = revision;
    value.data.after.credentialRevision = revision;
    value.data.after.revision = (BigInt(revision) + 1n).toString();
    verify(value, {...submitted, expectedRevision: revision});
    value.data.after.revision = revision;
    reject(value, {...submitted, expectedRevision: revision});
  }
});
test('invalid, numeric, noncanonical, oversized revisions and credential changes', () => {
  for (const bad of [
    '0',
    '-1',
    '+7',
    '07',
    '7.0',
    '7e0',
    ' 7',
    '9223372036854775808',
    '',
    7,
    null,
  ]) {
    for (const side of ['before', 'after'])
      for (const field of ['revision', 'credentialRevision'])
        mutate(v => {
          v.data[side][field] = bad;
        });
  }
  mutate(v => {
    v.data.after.revision = '9';
  });
  mutate(v => {
    v.data.before.revision = '9223372036854775807';
    v.data.after.revision = '1';
  });
  mutate(v => {
    v.data.before.credentialRevision = '8';
    v.data.after.credentialRevision = '8';
  });
  mutate(v => {
    v.data.after.credentialRevision = '4';
  });
});
test('org actor target and command binding', () => {
  for (const field of ['organizationId', 'actorId', 'connectionId'])
    mutate(v => {
      v.data[field] = foreign;
    });
  for (const side of ['before', 'after'])
    for (const field of ['organizationId', 'id'])
      mutate(v => {
        v.data[side][field] = foreign;
      });
  for (const bad of [
    null,
    'ABCDEFAB-CDEF-4ABC-8ABC-ABCDEFABCDEF',
    '00000000-0000-0000-0000-000000000000',
    '44444444-4444-6444-8444-444444444444',
  ])
    mutate(v => {
      v.data.commandId = bad;
    });
  assert.throws(() => verifyRenamed(fixture(), foreign, actor, submitted));
  assert.throws(() => verifyRenamed(fixture(), org, foreign, submitted));
  for (const command of [
    {...submitted, connectionId: foreign},
    {...submitted, expectedRevision: '6'},
    {...submitted, expectedRevision: '0'},
    {...submitted, expectedRevision: 7},
    {...submitted, name: 'Another name'},
    {...submitted, name: ' Reviewed new name '},
  ])
    reject(fixture(), command);
  assert.throws(() =>
    receiptFoundRename(lookup(fixture()), org, actor, foreign, submitted),
  );
});
test('immutable provider mode state options and creation time', () => {
  mutate(v => {
    v.data.after.state = 'DISABLED';
  });
  mutate(v => {
    v.data.after.provider = 'GOOGLE_GEMINI';
    v.data.after.credentialMode = 'GEMINI_API_KEY';
  });
  mutate(v => {
    v.data.after.credentialMode = 'ANTHROPIC_API_KEY';
  });
  mutate(v => {
    v.data.after.createdAt = '2026-11-10T01:02:03.000000Z';
  });
  const value = fixture('ANTHROPIC', 'DISABLED', 'wrkspc_Reviewed1');
  value.data.after.options.workspaceId = 'wrkspc_Changed';
  reject(value);
});
test('real calendars and UTC six-digit precision required', () => {
  for (const bad of [
    '0000-01-01T00:00:00.000000Z',
    '+10000-01-01T00:00:00.000000Z',
    '2026-02-29T00:00:00.000000Z',
    '2026-11-31T00:00:00.000000Z',
    '2026-11-10T24:00:00.000000Z',
    '2026-11-10T01:02:03.00000Z',
    '2026-11-10T01:02:03.0000000Z',
    '2026-11-10T01:02:03.000000+00:00',
    null,
  ]) {
    mutate(v => {
      v.data.committedAt = bad;
    });
    for (const side of ['before', 'after'])
      for (const field of ['createdAt', 'updatedAt'])
        mutate(v => {
          v.data[side][field] = bad;
        });
  }
});
test('microsecond chronology and valid year boundaries', () => {
  mutate(v => {
    v.data.before.updatedAt = '2026-11-10T01:02:03.000000Z';
  });
  mutate(v => {
    v.data.after.updatedAt = '2026-11-10T01:02:03.000001Z';
  });
  mutate(v => {
    v.data.committedAt = '2026-11-10T01:02:03.000002Z';
  });
  for (const time of [
    '0001-01-01T00:00:00.000000Z',
    '9999-12-31T23:59:59.999999Z',
  ]) {
    const value = fixture();
    value.data.committedAt = time;
    for (const side of ['before', 'after'])
      for (const field of ['createdAt', 'updatedAt'])
        value.data[side][field] = time;
    verify(value);
  }
});
test('normalized Unicode names and fixed error text', () => {
  const unchanged = fixture();
  unchanged.data.after.name = unchanged.data.before.name;
  reject(unchanged, {...submitted, name: unchanged.data.before.name});
  for (const name of [
    '',
    ' Padded ',
    'Old name',
    'x'.repeat(101),
    'bad\u0000name',
    'bad\u2028name',
    '\ud800',
  ])
    mutate(v => {
      v.data.after.name = name;
    });
  mutate(v => {
    v.data.before.name = ' Old name ';
  });
  const value = fixture();
  value.data.after.name = '😀'.repeat(100);
  verify(value, {...submitted, name: value.data.after.name});
  value.data.after.name = 'private-secret-marker ';
  assert.throws(
    () => verify(value),
    e => !e.message.includes('private-secret-marker'),
  );
});
test('options and supported profile shape', () => {
  for (const options of [null, [], {workspaceId: null}, {apiKey: 'synthetic'}])
    mutate(v => {
      v.data.after.options = options;
    });
  for (const options of [
    {},
    {workspaceId: 'bad'},
    {workspaceId: null, extra: true},
  ]) {
    const value = fixture('ANTHROPIC');
    value.data.after.options = options;
    reject(value);
  }
  for (const provider of [null, 'UNSUPPORTED'])
    mutate(v => {
      v.data.after.provider = provider;
    });
  for (const state of [null, 'DELETED', 'enabled'])
    mutate(v => {
      v.data.after.state = state;
    });
});
test('exact allowlists reject extra, missing and internal fields at every level', () => {
  for (const level of ['envelope', 'meta', 'receipt', 'before', 'after']) {
    const select = v =>
      level === 'envelope'
        ? v
        : level === 'meta'
          ? v.meta
          : level === 'receipt'
            ? v.data
            : v.data[level];
    for (const field of Object.keys(select(fixture()))) {
      const value = fixture();
      delete select(value)[field];
      reject(value);
    }
    const extra = fixture();
    select(extra).externalLocator = 'private-vault-locator';
    reject(extra);
    const symbol = fixture();
    select(symbol)[Symbol('extra')] = true;
    reject(symbol);
  }
  for (const side of ['before', 'after'])
    for (const bad of [null, [], {}])
      mutate(v => {
        v.data[side] = bad;
      });
  mutate(v => {
    v.data.checkId = foreign;
  });
});
test('explicit null stays unresolved; malformed or unrelated lookup fails closed', () => {
  const absent = {data: {receipt: null}, meta: {requestId: foreign}};
  assert.equal(
    receiptFoundRename(absent, org, actor, target, submitted),
    false,
  );
  for (const value of [
    {data: {}, meta: absent.meta},
    {...absent, data: {receipt: null, extra: true}},
    {data: {receipt: fixture().data}},
    {...absent, meta: {requestId: 'invalid'}},
  ])
    assert.throws(() =>
      receiptFoundRename(value, org, actor, target, submitted),
    );
  const mismatch = lookup(fixture());
  mismatch.data.receipt.after.name = 'Different receipt';
  assert.throws(() =>
    receiptFoundRename(mismatch, org, actor, target, submitted),
  );
});
test('Rename and Create validators remain operation-specific', () => {
  for (const operation of [
    'CREATE',
    'ROTATE',
    'DISABLE',
    'ENABLE',
    'ACCESS_CHECK',
    'MODEL_DISCOVERY',
  ])
    mutate(v => {
      v.data.operation = operation;
    });
  assert.throws(() =>
    verifyCreated(fixture(), org, actor, {
      provider: 'OPENAI',
      name: submitted.name,
      workspaceId: null,
    }),
  );
});

const reviewedDisable = {...submitted, name: 'Old name'};
function disableFixture(provider = 'OPENAI', workspace = null) {
  const value = fixture(provider, 'ENABLED', workspace);
  value.data.operation = 'DISABLE';
  value.data.after.state = 'DISABLED';
  value.data.after.name = value.data.before.name;
  return value;
}

test('Disable confirms the reviewed transition for each provider and exact int64 revisions', () => {
  for (const [provider, workspace] of [
    ['OPENAI', null],
    ['GOOGLE_GEMINI', null],
    ['ANTHROPIC', null],
    ['ANTHROPIC', 'wrkspc_Reviewed1'],
  ]) {
    const value = disableFixture(provider, workspace);
    verifyDisabled(value, org, actor, reviewedDisable);
    assert.equal(
      receiptFoundDisable(lookup(value), org, actor, target, reviewedDisable),
      true,
    );
  }
  const max = disableFixture();
  max.data.before.revision = '9223372036854775806';
  max.data.after.revision = '9223372036854775807';
  verifyDisabled(max, org, actor, {
    ...reviewedDisable,
    expectedRevision: max.data.before.revision,
  });
});

test('Disable rejects unchanged or reversed state and any name change', () => {
  for (const [before, after] of [
    ['ENABLED', 'ENABLED'],
    ['DISABLED', 'DISABLED'],
    ['DISABLED', 'ENABLED'],
  ]) {
    const value = disableFixture();
    value.data.before.state = before;
    value.data.after.state = after;
    assert.throws(
      () => verifyDisabled(value, org, actor, reviewedDisable),
      /Invalid Disable receipt/,
    );
  }
  for (const side of ['before', 'after']) {
    const value = disableFixture();
    value.data[side].name = 'Changed name';
    assert.throws(
      () => verifyDisabled(value, org, actor, reviewedDisable),
      /Invalid Disable receipt/,
    );
  }
  const both = disableFixture();
  both.data.before.name = 'Other valid name';
  both.data.after.name = 'Other valid name';
  assert.throws(
    () => verifyDisabled(both, org, actor, reviewedDisable),
    /Invalid Disable receipt/,
  );
});

test('Disable keeps operation, scope, immutable fields, adjacency and microsecond chronology strict', () => {
  for (const mutate of [
    value => {
      value.data.operation = 'ENABLE';
    },
    value => {
      value.data.operation = 'RENAME';
    },
    value => {
      value.data.actorId = foreign;
    },
    value => {
      value.data.organizationId = foreign;
    },
    value => {
      value.data.connectionId = foreign;
    },
    value => {
      value.data.before.id = foreign;
    },
    value => {
      value.data.after.organizationId = foreign;
    },
    value => {
      value.data.after.credentialRevision = '4';
    },
    value => {
      value.data.after.revision = '9';
    },
    value => {
      value.data.after.revision = 8;
    },
    value => {
      value.data.after.updatedAt = '2026-11-10T01:02:03.000001Z';
    },
    value => {
      value.data.committedAt = '2026-11-10T01:02:03.000002Z';
    },
    value => {
      value.data.after.options = {secret: 'synthetic'};
    },
    value => {
      value.data.before.externalLocator = 'synthetic';
    },
  ]) {
    const value = disableFixture();
    mutate(value);
    assert.throws(
      () => verifyDisabled(value, org, actor, reviewedDisable),
      /Invalid Disable receipt/,
    );
  }
  assert.throws(() =>
    verifyDisabled(disableFixture(), org, actor, {
      ...reviewedDisable,
      expectedRevision: '6',
    }),
  );
  assert.throws(
    () => verifyRenamed(disableFixture(), org, actor, reviewedDisable),
    /Invalid Rename receipt/,
  );
});

test('Disable lookup distinguishes explicit absence and binds every found receipt to the review', () => {
  const absent = {data: {receipt: null}, meta: {requestId: foreign}};
  assert.equal(
    receiptFoundDisable(absent, org, actor, target, reviewedDisable),
    false,
  );
  assert.throws(() =>
    receiptFoundDisable(
      {...absent, data: {}},
      org,
      actor,
      target,
      reviewedDisable,
    ),
  );
  assert.throws(() =>
    receiptFoundDisable(
      lookup(disableFixture()),
      org,
      actor,
      foreign,
      reviewedDisable,
    ),
  );
  assert.throws(() =>
    receiptFoundDisable(
      lookup(disableFixture()),
      org,
      foreign,
      target,
      reviewedDisable,
    ),
  );
  assert.throws(() =>
    receiptFoundDisable(lookup(fixture()), org, actor, target, reviewedDisable),
  );
  assert.throws(() =>
    receiptFoundDisable(lookup(disableFixture()), org, actor, target, {
      ...reviewedDisable,
      name: 'Other valid name',
    }),
  );
});
