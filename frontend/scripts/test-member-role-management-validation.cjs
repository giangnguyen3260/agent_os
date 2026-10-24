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
    if (key === './grant-management.service') return load('grant-management.service');
    if (key === './member-access.service') return load('member-access.service');
    throw Error('Unexpected dependency ' + key);
  }});
  return cache[name] = mod.exports;
}
const {validContext, validResult, validHistory, reasonText, validReason} = load('member-role-management.service');
const id=n=>'80800000-0000-4000-8000-'+String(n).padStart(12,'0');
const org=id(1), account=id(2), actor=id(3), key=id(4), time='2026-10-24T12:00:00.123456Z';
const c={organizationId:org,accountId:account,checkedAt:time,revision:0,member:{accountId:account,displayName:'Synthetic target',enabled:true,currentRole:'MEMBER',isSelf:false},selectedRole:null,enabledAdminCount:1,canChange:false,blockers:[]};
const vc=(v,selected=null,a=actor)=>validContext(v,org,account,a,selected);
assert.equal(vc(c),true);
for(const x of [{enabledAdminCount:0},{enabledAdminCount:-1},{enabledAdminCount:'1'},{canChange:true},{selectedRole:'ADMIN'},{revision:-1},{revision:2147483648},{revision:'0'},{checkedAt:'2026-02-30T00:00:00Z'},{email:'private'},{blockers:['NO_CHANGE']}]) assert.equal(vc({...c,...x}),false,JSON.stringify(x));
for(const x of [{isSelf:true},{isSelf:0},{enabled:'true'},{currentRole:'Owner'},{email:'private'},{accountId:actor}]) assert.equal(vc({...c,member:{...c.member,...x}}),false);
assert.equal(vc({...c,selectedRole:'ADMIN',canChange:true},'ADMIN'),true);
assert.equal(vc({...c,selectedRole:'MEMBER',blockers:['NO_CHANGE']},'MEMBER'),true);
const self={...c,member:{...c.member,isSelf:true,enabled:false,currentRole:'ADMIN'},selectedRole:'MEMBER',blockers:['SELF_CHANGE','TARGET_DISABLED','LAST_ENABLED_ADMIN']};
assert.equal(vc(self,'MEMBER',account),true);
assert.equal(vc({...self,blockers:['TARGET_DISABLED','SELF_CHANGE','LAST_ENABLED_ADMIN']},'MEMBER',account),false);
assert.equal(vc({...self,selectedRole:'ADMIN',blockers:['SELF_CHANGE','TARGET_DISABLED','NO_CHANGE']},'ADMIN',account),true);
assert.equal(vc({...c,member:{...c.member,currentRole:'ADMIN'},selectedRole:'MEMBER',blockers:['LAST_ENABLED_ADMIN']},'MEMBER'),true);
assert.equal(vc({...c,enabledAdminCount:2,member:{...c.member,currentRole:'ADMIN'},selectedRole:'MEMBER',canChange:true},'MEMBER'),true);
const p={org,account,actor,key,operation:'CHANGE_ROLE',revision:0,beforeRole:'MEMBER',afterRole:'ADMIN'};
const r={operation:'CHANGE_ROLE',organizationId:org,accountId:account,actorId:actor,actorName:'Synthetic actor',idempotencyKey:key,eventId:id(5),revision:1,recordedAt:time,beforeRole:'MEMBER',afterRole:'ADMIN'};
assert.equal(validResult(r,p),true);
for(const x of [{operation:'ISSUE'},{organizationId:actor},{accountId:actor},{actorId:account},{idempotencyKey:id(9)},{beforeRole:'ADMIN'},{afterRole:'MEMBER'},{revision:0},{revision:2},{revision:'1'},{reason:'private'},{targetName:'private'},{recordedAt:'2026-10-24T12:00:00.123Z'},{recordedAt:'2026-10-24T12:00:00.1234567Z'},{recordedAt:'2026-02-30T12:00:00.123456Z'}]) assert.equal(validResult({...r,...x},p),false,JSON.stringify(x));
for(const k of Object.keys(r)){const v={...r};delete v[k];assert.equal(validResult(v,p),false,k);}
assert.equal(validResult({...r,revision:2147483647},{...p,revision:2147483646}),true);
assert.equal(validResult({...r,revision:2147483648},{...p,revision:2147483647}),false);
const item={id:id(6),actorId:actor,actorName:'Synthetic actor',beforeRole:'MEMBER',afterRole:'ADMIN',reason:'Synthetic reason',recordedAt:time,revision:1};
const h={organizationId:org,accountId:account,checkedAt:time,items:[item],page:0,size:20,total:1};
assert.equal(validHistory(h,org,account,0),true);
assert.equal(validHistory({...h,items:[],total:0},org,account,0),true);
for(const x of [{organizationId:actor},{accountId:actor},{page:1},{size:10},{total:-1},{total:2},{member:{email:'private'}}]) assert.equal(validHistory({...h,...x},org,account,0),false);
for(const x of [{afterRole:'MEMBER'},{beforeRole:'OWNER'},{revision:0},{reason:' x '},{recordedAt:'2026-10-24T12:00:00.123Z'},{email:'private'}]) assert.equal(validHistory({...h,items:[{...item,...x}]},org,account,0),false);
const older={...item,id:id(5),recordedAt:'2026-10-24T12:00:00.123455Z',revision:2};
assert.equal(validHistory({...h,items:[item,older],total:2},org,account,0),true);
assert.equal(validHistory({...h,items:[older,item],total:2},org,account,0),false);
assert.equal(validHistory({...h,items:[item,{...older,recordedAt:time}],total:2},org,account,0),true);
assert.equal(validHistory({...h,items:[{...older,recordedAt:time},item],total:2},org,account,0),false);
assert.equal(validHistory({...h,items:[item,item],total:2},org,account,0),false);
assert.equal(validReason('😀'.repeat(1000)),true);assert.equal(validReason('😀'.repeat(1001)),false);
for (const value of ['\ud800', '\udfff', 'before\ud800after', 'before\udfffafter']) {
  assert.equal(validReason(value), false);
  assert.equal(validHistory({...h, items:[{...item, reason:value}]}, org, account, 0), false);
}
assert.equal(validReason('\ud83d\ude00'), true);
assert.equal(reasonText('\u001c\u2000é\r\n😀\u3000'),'é\r\n😀');
assert.equal(reasonText('\u00a0x\u202f'),'\u00a0x\u202f');
assert.equal(reasonText('e\u0301'),'e\u0301');assert.notEqual(reasonText('e\u0301'),reasonText('é'));
for(const n of [0,1,8,11,12,14,31,127]) assert.equal(validReason('x'+String.fromCharCode(n)+'y'),false);
assert.equal(validReason('x\t\r\ny'),true);assert.equal(validReason(' \t\n'),false);
console.log('PASS: production role validators — exact schemas/correlation, ADMIN count, ordered blockers, revisions/overflow, microsecond timestamps, retained history/order, Java strip and Unicode bounds');
