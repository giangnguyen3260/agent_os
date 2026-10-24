const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const ts = require('typescript');
const source = ts.transpileModule(fs.readFileSync(path.join(__dirname,'../src/app/features/invitations/invitation-validation.ts'),'utf8'),{compilerOptions:{target:ts.ScriptTarget.ES2022,module:ts.ModuleKind.CommonJS}}).outputText;
const mod = {exports:{}};
vm.runInNewContext(source,{module:mod,exports:mod.exports,TextEncoder});
const v = mod.exports;
const org = '50505050-5050-4050-8050-505050505050';
const item = {id:org,email:'test@example.test',role:'MEMBER',status:'PENDING',revision:1,createdAt:'2026-10-24T00:00:00.123456Z',expiresAt:'2026-10-27T00:00:00.123456Z',acceptedAt:null,revokedAt:null,deliveryStatus:'QUEUED',lastRequestedAt:'2026-10-24T00:00:00.123456Z'};
const page = {organizationId:org,checkedAt:item.createdAt,items:[item],page:0,size:20,total:1};
assert.equal(v.validPage(page,org,0,'ALL'),true);
for(const x of [null,[],{...page,token:'secret'},{...page,total:2},{...page,page:'0'},{...page,size:21},{...page,checkedAt:'2026-02-30T00:00:00Z'},{...page,items:[{...item,revision:0}]},{...page,items:[{...item,revision:2147483648}]},{...page,items:[{...item,email:'A@example.test'}]},{...page,items:[{...item,status:'EXPIRED'}]},{...page,items:[{...item,acceptedAt:item.createdAt}]},{...page,items:[{...item,role:'ADMIN'}]},{...page,items:[{...item,token:'secret'}]},{...page,items:[item,item],total:2}]) assert.equal(v.validPage(x,org,0,'ALL'),false);
assert.equal(v.validPage({...page,checkedAt:item.expiresAt,items:[{...item,status:'EXPIRED'}]},org,0,'EXPIRED'),true);
assert.equal(v.instant('2026-10-24T00:00:00.000001Z')-v.instant('2026-10-24T00:00:00Z'),1n);
assert.equal(v.instant('2026-10-24T00:00:00.1234567Z'),null);
assert.equal(v.validContext({organizationId:org,checkedAt:item.createdAt,invitation:item,canResend:false,canRevoke:true},org,org),true);
assert.equal(v.validContext({organizationId:org,checkedAt:'2026-10-24T00:01:00.123456Z',invitation:item,canResend:true,canRevoke:true},org,org),true);
assert.equal(v.validContext({organizationId:org,checkedAt:'2026-10-24T00:01:00.123455Z',invitation:item,canResend:true,canRevoke:true},org,org),false);
assert.equal(v.uuid('abcdefab-5050-4050-8050-505050505050'.toUpperCase()),false);assert.equal(v.uuid('50505050-5050-0050-8050-505050505050'),false);
assert.equal(v.normalizeEmail('\u2000A@EXAMPLE.TEST\u3000'),'a@example.test');
for(const value of ['a@x','a@@example.test','a b@example.test','a@-example.test','a@example..test','a@exämple.test','a@example.test\r\n']) assert.equal(v.email(value),false);
assert.equal(v.email('a'.repeat(64)+'@'+['b'.repeat(63),'c'.repeat(63),'d'.repeat(61)].join('.')),true);
assert.equal(v.email('a'.repeat(64)+'@'+['b'.repeat(63),'c'.repeat(63),'d'.repeat(62)].join('.')),false);
assert.equal(v.email('a'.repeat(65)+'@example.test'),false);
for (const local of ['.a','a.','a..b','a,b','a;b','a:b','a<b','a>b','a"b','a\\b','a[b','a]b']) assert.equal(v.email(local+'@example.test'),false);
assert.equal(v.email("a!#$%&'*+/=?^_`{|}~-@example.test"),true);
assert.equal(v.email('a@'+'b'.repeat(64)+'.test'),false);
assert.equal(v.displayName('😀'.repeat(255)),true);assert.equal(v.displayName('😀'.repeat(256)),false);
for(const value of ['',' a','a\u0000','\ud800','\udc00']) assert.equal(v.displayName(value),false);
assert.equal(v.password('a'.repeat(14)),true);assert.equal(v.password('a'.repeat(13)),false);
assert.equal(v.password('😀'.repeat(18)),true);assert.equal(v.password('😀'.repeat(19)),false);
assert.equal(v.password('a'.repeat(72)),true);assert.equal(v.password('a'.repeat(73)),false);assert.equal(v.password('a'.repeat(14)+'\ud800'),false);
const c = {organizationId:org,actorId:org,key:org,operation:'CREATE_INVITATION',email:item.email};
const receipt = {operation:c.operation,organizationId:org,invitationId:org,idempotencyKey:org,revision:1,eventId:org,actorId:org,actorName:'Admin',recordedAt:item.createdAt,email:item.email,role:'MEMBER',expiresAt:item.expiresAt};
assert.equal(v.validReceipt(receipt,c),true);
for(const x of [{...receipt,revision:2},{...receipt,operation:'REVOKE_INVITATION'},{...receipt,email:'other@example.test'},{...receipt,password:'secret'},{...receipt,actorName:'\ud800'}]) assert.equal(v.validReceipt(x,c),false);
const inspect = {state:'NEW_ACCOUNT',organizationId:org,organizationName:'Org',emailHint:'t***@example.test',role:'MEMBER',expiresAt:item.expiresAt};
for(const state of ['NEW_ACCOUNT','SIGN_IN','MISMATCH','READY']) assert.equal(v.validInspection({...inspect,state}),true);
assert.equal(v.validInspection({...inspect,email:item.email}),false);
assert.equal(v.validAcceptance({organizationId:org,role:'MEMBER',requiresLogin:true,alreadyMember:false},org,true),true);
assert.equal(v.validAcceptance({organizationId:org,role:'ADMIN',requiresLogin:true,alreadyMember:false},org,true),false);
assert.equal(v.validAcceptance({organizationId:org,role:'MEMBER',requiresLogin:true,alreadyMember:true},org,true),false);
console.log('PASS: real production invitation DTO validators, exact fields, scope, microsecond lifecycle, cooldown, receipt binding, Unicode/password boundaries');

assert.equal(v.envelope({data:page,meta:{requestId:org}}),true);
for(const value of [{data:page},{data:page,meta:{}},{data:page,meta:{requestId:org},secret:'x'},{data:page,meta:{requestId:org,token:'x'}}]) assert.equal(v.envelope(value),false);

const {createHash} = require('node:crypto');
for (const [operation,revision,hash] of [
 ['CREATE_INVITATION',null,'c651c65696a23ed4e1e2c62e195d3d34a9c42bcefb9cd19a6612d4184209d6bd'],
 ['RESEND_INVITATION',1,'ff65bf6d78ea6d5a116bc50ac85e767281a78b60aa53af29697f48028b3e7279'],
 ['REVOKE_INVITATION',2,'41980eca49a300e04909063913b2757609beea5e555961501c0796f7b1ef42c8']]) {
 const correlation = {organizationId:'90909090-9090-4090-8090-909090909090',actorId:'90900000-0000-4000-8000-000000000001',key:org,operation,...(revision ? {invitationId:'90910000-0000-4000-8000-000000000101'} : {})};
 const body = revision ? {expectedRevision:revision,acknowledgement:true} : {email:'invitee@example.test',acknowledgement:true};
 const canonical = v.canonicalCommand(correlation,body);
 assert.equal(Object.keys(JSON.parse(canonical)).length,9);
 assert.equal(createHash('sha256').update(canonical,'utf8').digest('hex'),hash);
 assert.equal(v.canonicalCommand({...correlation,key:'abcdefab-5050-4050-8050-505050505050'},body),canonical);
 assert.throws(()=>v.canonicalCommand(correlation,{...body,token:'secret'}));
}
console.log('PASS: bounded single-mailbox grammar and all three domain/version canonical SHA256 vectors');
