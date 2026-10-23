// Execute production wire validators; no Angular renderer or real API replacement.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = ts.transpileModule(fs.readFileSync(path.join(__dirname, '../src/app/features/members/member-access.service.ts'), 'utf8'), {
  compilerOptions: {target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.CommonJS, experimentalDecorators: true},
}).outputText;
const moduleValue = {exports: {}};
const adapters = {
  '@angular/common/http': {},
  '@angular/core': {Injectable: () => v => v},
  rxjs: {},
  '../teams/team.service': {uuid: v => typeof v === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(v)},
};
vm.runInNewContext(source, {module: moduleValue, exports: moduleValue.exports, require: name => {
  assert.ok(Object.hasOwn(adapters, name)); return adapters[name];
}});
const {validGrants, validMembers, stripMemberQuery} = moduleValue.exports;
const org = '50505050-5050-4050-8050-505050505050';
const actor = '50800000-0000-4000-8000-000000000001';
const member = {accountId: actor, displayName: 'Synthetic admin', email: 'synthetic@example.test', role: 'ADMIN', enabled: true, isSelf: true};
const grant = {id: '50700000-0000-4000-8000-000000000001', organizationId: org, accountId: actor, teamId: org, teamName: 'Synthetic team', actionType: 'PUBLISH_PREVIEW', riskLevel: 'HIGH', expiresAt: '2026-10-23T00:00:00.123457Z', revokedAt: null, status: 'UNEXPIRED'};
const base = {organizationId: org, generatedAt: '2026-10-23T00:00:00.123456Z', page: 0, size: 20, total: 1};
const detail = {...base, member, items: [grant]};
assert.equal(validGrants(detail, org, actor, actor, 0, 'ALL'), true, 'submillisecond future expiry is unexpired');
assert.equal(validGrants({...detail, items: [{...grant, expiresAt: base.generatedAt, status: 'EXPIRED'}]}, org, actor, actor, 0, 'EXPIRED'), true, 'exact equality expired');
assert.equal(validGrants({...detail, items: [{...grant, revokedAt: base.generatedAt, expiresAt: '2020-01-01T00:00:00Z', status: 'REVOKED'}]}, org, actor, actor, 0, 'REVOKED'), true, 'revoked precedence');
for (const invalid of [null, [], {...detail, organizationId: actor}, {...detail, total: 2}, {...detail, member: {...member, isSelf: false}}, {...detail, items: [{...grant, revokedAt: undefined}]}, {...detail, items: [{...grant, status: 'EXPIRED'}]}, {...detail, generatedAt: 'not a date'}, {...detail, items: [{...grant, organizationId: actor}]}, {...detail, items: [grant, grant], total: 2}, {...detail, secret: 'unexpected'}]) {
  assert.equal(validGrants(invalid, org, actor, actor, 0, 'ALL'), false);
}
for (const generatedAt of ['2026-02-30T00:00:00Z', '2026-13-01T00:00:00Z']) assert.equal(validGrants({...detail, generatedAt}, org, actor, actor, 0, 'ALL'), false, 'invalid calendar date rejected');
const list = {...base, items: [{...member, grantCounts: {unexpired: 1, expired: 0, revoked: 0}}]};
assert.equal(validMembers(list, org, actor, 0, 'ADMIN'), true);
for (const invalid of [{...list, items: [{...list.items[0], grantCounts: {unexpired: -1, expired: 0, revoked: 0}}]}, {...list, items: [{...list.items[0], passwordHash: 'secret'}]}, {...list, items: [{...list.items[0], role: 'OWNER'}]}]) assert.equal(validMembers(invalid, org, actor, 0, ''), false);
assert.equal(stripMemberQuery('\u001c\u2000abc\u3000'), 'abc');
assert.equal(stripMemberQuery('\u00a0abc\u202f'), '\u00a0abc\u202f', 'Java nonbreaking whitespace preserved');
assert.equal([...stripMemberQuery('  ' + '😀'.repeat(200) + '  ')].length, 200);
console.log('PASS: member access wire/context/privacy/lifecycle precision and Java Unicode normalization');
