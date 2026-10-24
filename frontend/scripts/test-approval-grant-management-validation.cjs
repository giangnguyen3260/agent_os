// Execute the production validators against privacy, correlation and precision boundaries.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const cache = {};
function load(name) {
  if (cache[name]) return cache[name];
  const mod = {exports: {}};
  const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/app/features/members/', name + '.ts'), 'utf8'), {compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true}}).outputText;
  vm.runInNewContext(source, {module: mod, exports: mod.exports, require: key => {
    if (key === '@angular/core') return {Injectable: () => v => v};
    if (key === '@angular/common/http' || key === 'rxjs') return {};
    if (key === '../teams/team.service') return {uuid: v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)};
    if (key === './member-access.service') return load('member-access.service');
    throw Error('Unexpected dependency ' + key);
  }});
  return cache[name] = mod.exports;
}
const {stamp, reasonText, validReason, validResult} = load('grant-management.service');
assert.equal(stamp('2026-02-30T00:00:00Z'), null);
assert.equal(stamp('2026-10-23T00:00:00.000000001Z') - stamp('2026-10-23T00:00:00Z'), 1n);
assert.equal(reasonText('\u001c\u2000test\u3000'), 'test');
assert.equal(reasonText('\u00a0test\u202f'), '\u00a0test\u202f');
assert.equal(validReason('😀'.repeat(1000)), true);
assert.equal(validReason('😀'.repeat(1001)), false);
assert.equal(validReason('text\t\n\rtext'), true);
assert.equal(validReason('text\u0000text'), false);
assert.equal(validReason('text\u007ftext'), false);
const id = n => '60800000-0000-4000-8000-' + String(n).padStart(12, '0');
const org=id(1), account=id(2), actor=id(3), key=id(4), grant=id(5);
const base={operation:'ISSUE',organizationId:org,accountId:account,actorId:actor,actorName:'Synthetic admin',idempotencyKey:key,grantId:grant,eventId:id(6),revision:1,recordedAt:'2026-10-23T00:00:00.123456Z',expiresAt:'2026-10-30T00:00:00.123456Z',revokedAt:null};
const valid = v => validResult(v,org,account,actor,key,'ISSUE',0);
assert.equal(valid(base), true);
for (const changed of [{actorId:id(9)},{accountId:id(9)},{organizationId:id(9)},{idempotencyKey:id(9)},{operation:'REVOKE'},{revision:2},{reason:'private'},{expiresAt:base.recordedAt},{expiresAt:'2026-10-23T00:30:00.123456Z'},{expiresAt:'2026-10-23T01:00:00.123457Z'},{expiresAt:'2026-10-30T00:00:00.123457Z'},{revokedAt:base.recordedAt}]) assert.equal(valid({...base,...changed}), false);
const revoked={...base,operation:'REVOKE',revokedAt:base.recordedAt,expiresAt:'2020-01-01T00:00:00Z'};
assert.equal(validResult(revoked,org,account,actor,key,'REVOKE',0,grant), true);
assert.equal(validResult(revoked,org,account,actor,key,'REVOKE',0,id(9)), false);
console.log('PASS: grant management exact receipt correlation, privacy, nanosecond/TTL boundaries and Java reason semantics');
