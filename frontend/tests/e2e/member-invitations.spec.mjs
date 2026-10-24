import {test, expect} from '@playwright/test';
import {randomUUID} from 'node:crypto';
import {execFileSync} from 'node:child_process';
import {mkdirSync} from 'node:fs';
import {join} from 'node:path';

const org='90909090-9090-4090-8090-909090909090';
const foreign='91919191-9191-4191-8191-919191919191';
const base=`/api/v1/organizations/${org}/invitations`;
const home=`/workspace/${org}/invitations`;
const publicBase='/api/v1/auth/invitations';
const warning='Quyền duyệt riêng còn hạn có thể tiếp tục có hiệu lực khi tham gia lại.';
const account=n=>`90900000-0000-4000-8000-${String(n).padStart(12,'0')}`;
const actor=i=>({desktop:3,tablet:4,mobile:5})[i.project.name];
const recipient=(i,n)=>({desktop:100,tablet:200,mobile:300})[i.project.name]+n;
const email=(i,n)=>`invitation-browser-${i.project.name}-${n}@example.test`;
const button=(p,name)=>p.getByRole('button',{name,exact:true});
const hook=(p,name)=>p.getByTestId(`invitation-${name}`);
const ack=p=>p.getByRole('checkbox');
const response=(p,path,method='GET')=>p.waitForResponse(r=>new URL(r.url()).pathname===path&&r.request().method()===method);
async function observed(p,path,action,method='GET') {const [r]=await Promise.all([response(p,path,method),action()]);return r;}
async function data(p,path) {const r=await p.request.get(path);expect(r.status()).toBe(200);return (await r.json()).data;}
async function post(p,path,body,key=randomUUID()) {const csrf=await data(p,'/api/v1/auth/csrf');return p.request.post(path,{headers:{[csrf.headerName]:csrf.token,'Idempotency-Key':key},data:body});}
async function login(p,who) {
  expect(process.env.E2E_PASSWORD,'Disposable E2E_PASSWORD required').toBeTruthy();
  await p.goto('/login');await p.getByLabel('Email',{exact:true}).fill(`invitation-${who}@example.test`);
  await p.getByLabel('Mật khẩu',{exact:true}).fill(process.env.E2E_PASSWORD);
  await button(p,'Đăng nhập').click();await expect(p).toHaveURL(/\/workspace$/);
}
async function create(p,address) {const r=await post(p,base,{email:address,acknowledgement:true});expect(r.status()).toBe(201);return (await r.json()).data;}
async function tokenFor(p,address,exclude=[]) {
  const mailbox=process.env.E2E_MAILPIT_URL||'http://127.0.0.1:8025';
  let token;
  await expect.poll(async()=>{
    const r=await p.request.get(`${mailbox}/api/v1/search`,{params:{query:`to:${address}`}});expect(r.status()).toBe(200);
    for(const message of (await r.json()).messages||[]) {
      const detail=await p.request.get(`${mailbox}/api/v1/message/${message.ID}`);expect(detail.status()).toBe(200);
      const value=await detail.json();const matches=[...(value.Text+' '+value.HTML).matchAll(/\/invitation#(?:token=)?([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])/g)];
      token=matches.map(m=>m[1]).find(t=>!exclude.includes(t));if(token)return true;
    }return false;
  },{timeout:20000,message:'Isolated real SMTP invitation arrives'}).toBe(true);
  return token;
}
async function openNew(p,address) {await p.goto(home+'/new');await p.getByLabel('Email người nhận',{exact:true}).fill(address);}
async function review(p) {await button(p,'Xem lại lời mời').click();await expect(hook(p,'review')).toBeVisible();await expect(ack(p)).not.toBeChecked();await expect(hook(p,'review')).toContainText(warning);}
async function submit(p,path=base,label='Gửi lời mời') {await ack(p).check();const r=await observed(p,path,()=>button(p,label).click(),'POST');expect(r.status()).toBe(201);await expect(hook(p,'result')).toBeVisible();return {receipt:(await r.json()).data,request:r.request()};}
function writes(p) {const items=[];p.on('request',r=>{if(r.method()==='POST'&&(new URL(r.url()).pathname.startsWith(base)||new URL(r.url()).pathname===publicBase+'/accept'))items.push(r);});return items;}
async function shot(p,i,state) {expect(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);const dir=process.env.E2E_VISUAL_DIR||'qa-visuals';mkdirSync(dir,{recursive:true});await p.screenshot({path:join(dir,`member-invitations-${i.project.name}-${state}.png`),fullPage:true});}
function sql(input) {const command=process.env.INV_PSQL_COMMAND?JSON.parse(process.env.INV_PSQL_COMMAND):['docker','compose','exec','-T','postgres','sh','-c','psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'];return execFileSync(command[0],command.slice(1),{cwd:new URL('../../../',import.meta.url),input,encoding:'utf8',timeout:15000,stdio:['pipe','pipe','pipe']}).trim();}
function role(i,value) {sql(`UPDATE organization_memberships SET role='${value}' WHERE organization_id='${org}' AND account_id='${account(actor(i))}';`);}
async function lose(p,path,label,malformed=false) {
  let committed,command;
  await p.route(`**${path}`,async route=>{
    if(route.request().method()!=='POST')return route.continue();
    command={body:route.request().postData(),key:route.request().headers()['idempotency-key']};
    const actual=await route.fetch();expect(actual.status()).toBe(201);committed=(await actual.json()).data;
    if(malformed)await route.fulfill({response:actual,json:{data:{unexpected:true}}});else await route.abort('failed');
  });
  try {await ack(p).check();await button(p,label).click();await expect(hook(p,'unknown')).toBeVisible();}
  finally {await p.unroute(`**${path}`);}
  await expect(hook(p,'result')).toHaveCount(0);return {committed,command};
}
async function anonymous(p) {const csrf=await data(p,'/api/v1/auth/csrf');const r=await p.request.post('/api/v1/auth/logout',{headers:{[csrf.headerName]:csrf.token}});expect([200,204]).toContain(r.status());}
async function publicOpen(p,token) {const r=await observed(p,publicBase+'/inspect',()=>p.goto('/invitation#token='+token),'POST');expect(r.status()).toBe(200);await expect(p).toHaveURL(/\/invitation$/);return (await r.json()).data;}
async function newFields(p) {await p.getByLabel('Họ và tên',{exact:true}).fill('Invitation Browser New');await p.getByLabel('Mật khẩu mới',{exact:true}).fill('Browser invitation pass 2026!');await p.getByLabel('Nhập lại mật khẩu',{exact:true}).fill('Browser invitation pass 2026!');}
async function publicReview(p) {await expect(hook(p,'accept-review')).toBeVisible();await expect(hook(p,'accept-review')).toContainText(warning);}
async function storage(p,secrets) {const contents=await p.evaluate(()=>JSON.stringify([Object.entries(localStorage),Object.entries(sessionStorage)]));for(const secret of secrets)expect(contents).not.toContain(secret);}
function barrier() {let release;const value=new Promise(resolve=>{release=resolve;});return {release,wait:()=>Promise.race([value,new Promise((_,reject)=>{const id=setTimeout(()=>reject(Error('Real HTTP barrier timeout')),15000);value.finally(()=>clearTimeout(id));})])};}

// Existing 471 browser cases/config are unchanged. These 17 cases run in each
// configured 1440/768/390 project, one worker, retries zero, on real HTTP only.
test.describe('member invitations independent QC',()=>{
  test.describe.configure({timeout:90000});
  test.beforeEach(async({page:p},i)=>{p.on('dialog',d=>d.dismiss());await login(p,i.project.name);});

  test('INV-B01 inventory link, list, reviewed create, exact immutable receipt and real delivery',async({page:p},i)=>{
    const observedWrites=writes(p);await p.goto(`/workspace/${org}/members`);
    await p.getByRole('link',{name:'Lời mời thành viên',exact:true}).click();await expect(p).toHaveURL(new RegExp(home+'$'));
    await openNew(p,email(i,1));await shot(p,i,'edit');await review(p);expect(observedWrites).toHaveLength(0);
    await expect(hook(p,'review')).toContainText(email(i,1));await expect(hook(p,'review')).toContainText('MEMBER');
    await expect(button(p,'Gửi lời mời')).toBeDisabled();await shot(p,i,'review');
    const {receipt:r,request}=await submit(p);expect(Object.keys(r).sort()).toEqual('operation organizationId invitationId idempotencyKey revision eventId actorId actorName recordedAt email role expiresAt'.split(' ').sort());
    expect(r).toMatchObject({operation:'CREATE_INVITATION',organizationId:org,actorId:account(actor(i)),role:'MEMBER',revision:1,email:email(i,1)});
    expect(request.postDataJSON()).toEqual({email:email(i,1),acknowledgement:true});expect(r.idempotencyKey).toBe(request.headers()['idempotency-key']);
    for(const value of [r.eventId,r.actorName,r.recordedAt])await expect(hook(p,'result')).toContainText(value);
    await shot(p,i,'recorded');const token=await tokenFor(p,email(i,1));await storage(p,[token,r.idempotencyKey]);
    await p.goto(home);await expect(hook(p,'row').filter({hasText:email(i,1)})).toContainText('Đang chờ chấp nhận');
    expect((await data(p,base+'/'+r.invitationId+'/context')).invitation.status).toBe('PENDING');await shot(p,i,'list');expect(observedWrites).toHaveLength(1);
  });

  test('INV-B02 invalid email blocked, edit resets acknowledgement, no early POST',async({page:p},i)=>{
    const observedWrites=writes(p);await openNew(p,'bad email');
    for(const invalid of ['bad email','.invitation@example.test','invitation.@example.test','invitation..dot@example.test','invitation,other@example.test','invitation;other@example.test','"invitation"@example.test','invitation<other@example.test','invitation\\other@example.test','i'.repeat(65)+'@example.test','invitation@'+'x'.repeat(64)+'.test']) {
      await p.getByLabel('Email người nhận',{exact:true}).fill(invalid);await expect(button(p,'Xem lại lời mời')).toBeDisabled();
    }
    await p.getByLabel('Email người nhận',{exact:true}).fill(email(i,2));await review(p);await ack(p).check();
    await button(p,'Quay lại chỉnh sửa').click();await review(p);await expect(ack(p)).not.toBeChecked();expect(observedWrites).toHaveLength(0);
  });

  test('INV-B03 detail resend/revoke exact revision and fixed expiry',async({page:p},i)=>{
    const r=await create(p,email(i,3));await tokenFor(p,r.email);sql(`UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='${r.invitationId}';`);
    await p.goto(home+'/'+r.invitationId);await expect(hook(p,'detail')).toContainText(r.email);await shot(p,i,'detail');
    await button(p,'Gửi lại lời mời').click();await expect(hook(p,'review')).toBeVisible();await expect(ack(p)).not.toBeChecked();await shot(p,i,'resend-review');
    const sent=await submit(p,base+'/'+r.invitationId+'/resend','Gửi lại lời mời');expect(sent.request.postDataJSON()).toEqual({expectedRevision:1,acknowledgement:true});expect(sent.receipt.expiresAt).toBe(r.expiresAt);
    await p.goto(home+'/'+r.invitationId);await button(p,'Thu hồi lời mời').click();await expect(hook(p,'review')).toBeVisible();await shot(p,i,'revoke-review');
    const revoked=await submit(p,base+'/'+r.invitationId+'/revoke','Thu hồi lời mời');expect(revoked.receipt.revision).toBe(3);
    await p.goto(home+'/'+r.invitationId);await expect(hook(p,'detail')).toContainText('Đã thu hồi');
    expect((await data(p,base+'/'+r.invitationId+'/context')).invitation.status).toBe('REVOKED');
  });

  test('INV-B04 committed lost reply manual replay freezes original body and key',async({page:p},i)=>{
    await openNew(p,email(i,4));await review(p);const {committed,command}=await lose(p,base,'Gửi lời mời');await shot(p,i,'unknown');await storage(p,[email(i,4),command.key]);
    const r=await observed(p,base,()=>button(p,'Gửi lại đúng yêu cầu này').click(),'POST');expect(r.status()).toBe(200);expect(r.request().postData()).toBe(command.body);expect(r.request().headers()['idempotency-key']).toBe(command.key);expect((await r.json()).data).toEqual(committed);await expect(hook(p,'result')).toBeVisible();
  });

  test('INV-B05 malformed committed reply, null receipt, advanced state and historical replay',async({page:p},i)=>{
    await openNew(p,email(i,5));await review(p);const {committed,command}=await lose(p,base,'Gửi lời mời',true);
    const advanced=await post(p,base+'/'+committed.invitationId+'/revoke',{expectedRevision:1,acknowledgement:true});expect(advanced.status()).toBe(201);
    const receiptPath=base+'/receipts/'+command.key;
    await p.route(`**${receiptPath}`,async route=>{const actual=await route.fetch();expect(actual.status()).toBe(200);const envelope=await actual.json();expect(envelope.data).toEqual(committed);await route.fulfill({response:actual,json:{...envelope,data:null}});});
    try {await observed(p,receiptPath,()=>button(p,'Kiểm tra biên nhận').click());await expect(hook(p,'unknown')).toBeVisible();await expect(hook(p,'result')).toHaveCount(0);}
    finally {await p.unroute(`**${receiptPath}`);}
    const r=await observed(p,base,()=>button(p,'Gửi lại đúng yêu cầu này').click(),'POST');expect(r.status()).toBe(200);expect((await r.json()).data).toEqual(committed);expect(r.request().postData()).toBe(command.body);
    expect((await data(p,base+'/'+committed.invitationId+'/context')).invitation.status).toBe('REVOKED');
  });

  test('INV-B06 observed focus denial removes draft; await private DOM cleanup before any action',async({page:p},i)=>{
    await openNew(p,email(i,6));await p.getByLabel('Email người nhận',{exact:true}).focus();
    try {role(i,'MEMBER');const r=await observed(p,`/api/v1/auth/workspaces/${org}`,()=>p.evaluate(()=>dispatchEvent(new Event('focus'))));expect((await r.json()).data.role).toBe('MEMBER');
      await expect(p.getByRole('alert').filter({hasText:'Bạn không có quyền quản lý lời mời trong tổ chức này.'})).toBeVisible();await expect(p.getByLabel('Email người nhận',{exact:true})).toHaveCount(0);await expect(p.locator('main')).not.toContainText(email(i,6));await expect(hook(p,'review')).toHaveCount(0);await shot(p,i,'denied');}
    finally {role(i,'ADMIN');}
  });

  test('INV-B07 unknown denial retains opaque GET recovery only',async({page:p},i)=>{
    const observedWrites=writes(p);await openNew(p,email(i,7));await review(p);const {committed}=await lose(p,base,'Gửi lời mời');
    try {role(i,'MEMBER');await observed(p,`/api/v1/auth/workspaces/${org}`,()=>button(p,'Kiểm tra biên nhận').click());
      await expect(p.getByRole('alert').filter({hasText:'Bạn không có quyền quản lý lời mời trong tổ chức này.'})).toBeVisible();await expect(p.locator('main')).not.toContainText(email(i,7));await expect(button(p,'Gửi lại đúng yêu cầu này')).toHaveCount(0);}
    finally {role(i,'ADMIN');}
    const count=observedWrites.length;const r=await observed(p,base+'/receipts/'+committed.idempotencyKey,()=>button(p,'Kiểm tra biên nhận').click());expect((await r.json()).data).toEqual(committed);await expect(hook(p,'result')).toBeVisible();expect(observedWrites).toHaveLength(count);
  });

  test('INV-B08 dirty Back and unload cancel, explicit accepted exit',async({page:p},i)=>{
    const observedWrites=writes(p);await p.goto(home);await openNew(p,email(i,8));
    const back=p.waitForEvent('dialog');await p.evaluate(()=>history.back());expect((await back).message()).toContain('chưa');await expect(p.getByLabel('Email người nhận',{exact:true})).toHaveValue(email(i,8));
    const unload=p.waitForEvent('dialog');await p.evaluate(()=>location.reload());expect((await unload).type()).toBe('beforeunload');await expect(p.getByLabel('Email người nhận',{exact:true})).toHaveValue(email(i,8));
    p.removeAllListeners('dialog');p.on('dialog',d=>d.accept());await p.evaluate(()=>history.back());await expect(p).toHaveURL(new RegExp(home+'$'));expect(observedWrites).toHaveLength(0);
  });

  test('INV-B09 shared-session account switch discards late committed reply and recovery',async({page:p},i)=>{
    await openNew(p,email(i,9));await review(p);const arrived=barrier(),release=barrier(),finished=barrier();let receipt;
    await p.route(`**${base}`,async route=>{if(route.request().method()!=='POST')return route.continue();try {const actual=await route.fetch();expect(actual.status()).toBe(201);receipt=(await actual.json()).data;arrived.release();await release.wait();await route.fulfill({response:actual});}catch(e){if(!/closed|cancel|handled|interception/i.test(String(e)))throw e;}finally{finished.release();}});
    const sibling=await p.context().newPage();
    try {await ack(p).check();await button(p,'Gửi lời mời').click();await arrived.wait();await anonymous(sibling);await login(sibling,'admin');
      const fresh=response(p,'/api/v1/auth/session');await p.evaluate(()=>dispatchEvent(new Event('focus')));expect((await (await fresh).json()).data.id).not.toBe(receipt.actorId);
      await expect(p.getByRole('alert').filter({hasText:'Bạn không có quyền quản lý lời mời trong tổ chức này.'})).toBeVisible();await expect(p.locator('main')).not.toContainText(email(i,9));release.release();await finished.wait();await expect(hook(p,'result')).toHaveCount(0);await expect(hook(p,'unknown')).toHaveCount(0);await expect(p.locator('main')).not.toContainText(receipt.eventId);
    }finally{release.release();await p.unroute(`**${base}`);await sibling.close();}
  });

  test('INV-B10 public fragment new account explicit accept; no storage, no automatic login',async({page:p},i)=>{
    const r=await create(p,email(i,10)),token=await tokenFor(p,r.email);await anonymous(p);const observedWrites=writes(p);
    expect((await publicOpen(p,token)).state).toBe('NEW_ACCOUNT');await newFields(p);await publicReview(p);await shot(p,i,'public-new');expect(observedWrites).toHaveLength(0);
    await ack(p).check();const accepted=await observed(p,publicBase+'/accept',()=>button(p,'Tạo tài khoản và tham gia').click(),'POST');expect(accepted.status()).toBe(200);expect((await accepted.json()).data).toMatchObject({requiresLogin:true,alreadyMember:false,role:'MEMBER'});
    await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveCount(0);await storage(p,[token,'Browser invitation pass 2026!']);expect((await p.request.get('/api/v1/auth/session')).status()).toBe(401);await expect(hook(p,'result')).toBeVisible();await shot(p,i,'public-success');expect(observedWrites).toHaveLength(1);
  });

  test('INV-B11 existing inline login keeps token in memory and requires explicit acceptance',async({page:p},i)=>{
    const n=recipient(i,11),r=await create(p,`invitation-existing-${n}@example.test`),token=await tokenFor(p,r.email);await anonymous(p);const observedWrites=writes(p);
    expect((await publicOpen(p,token)).state).toBe('SIGN_IN');await shot(p,i,'public-sign-in');await p.getByLabel('Email',{exact:true}).fill(r.email);await p.getByLabel('Mật khẩu',{exact:true}).fill(process.env.E2E_PASSWORD);
    await button(p,'Đăng nhập').click();await publicReview(p);await expect(button(p,'Tham gia tổ chức')).toBeVisible();expect(observedWrites).toHaveLength(0);await shot(p,i,'public-ready');
    await ack(p).check();const accepted=await observed(p,publicBase+'/accept',()=>button(p,'Tham gia tổ chức').click(),'POST');expect(accepted.status()).toBe(200);expect(accepted.request().postDataJSON()).toEqual({token,displayName:null,password:null});expect((await accepted.json()).data.requiresLogin).toBe(false);
    await expect(hook(p,'result')).toBeVisible();await shot(p,i,'public-existing-success');await p.getByRole('link',{name:'Vào tổ chức',exact:true}).click();await expect(p).toHaveURL(new RegExp(`/workspace/${org}`));
  });

  test('INV-B12 mismatch needs explicit logout and never exposes registration while signed in',async({page:p},i)=>{
    const r=await create(p,email(i,12)),token=await tokenFor(p,r.email),observedWrites=writes(p);expect((await publicOpen(p,token)).state).toBe('MISMATCH');
    await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveCount(0);await expect(button(p,'Tạo tài khoản và tham gia')).toHaveCount(0);await shot(p,i,'public-mismatch');
    await button(p,'Đăng xuất để đổi tài khoản').click();await expect(p.getByLabel('Họ và tên',{exact:true})).toBeVisible();expect(observedWrites).toHaveLength(0);
  });

  test('INV-B13 inspect faults recover blank review; expired and erased fragment remain invalid',async({page:p},i)=>{
    const r=await create(p,email(i,13)),token=await tokenFor(p,r.email);
    await anonymous(p);const observedWrites=writes(p);
    for(const fault of ['initial-503','preaccept-503','preaccept-malformed']) {
      if(fault!=='initial-503'){await newFields(p);await ack(p).check();}
      let injections=0;
      await p.route(`**${publicBase}/inspect`,async route=>{
        const actual=await route.fetch();expect(actual.status()).toBe(200);
        expect((await actual.json()).data.state).toBe('NEW_ACCOUNT');injections++;
        if(fault.endsWith('503'))await route.fulfill({response:actual,status:503,json:{error:{code:'SERVICE_UNAVAILABLE',message:'Temporary QC transport fault'}}});
        else await route.fulfill({response:actual,json:{data:{unexpected:true}}});
      });
      try {
        await observed(p,publicBase+'/inspect',()=>fault==='initial-503'?p.goto('/invitation#token='+token):button(p,'Tạo tài khoản và tham gia').click(),'POST');
        await expect(p.getByRole('heading',{name:'Chưa thể kiểm tra lời mời',exact:true})).toBeVisible();
        await expect(p.getByRole('heading',{name:'Lời mời không còn hiệu lực',exact:true})).toHaveCount(0);
        await expect(hook(p,'unknown')).toHaveCount(0);await expect(hook(p,'accept-review')).toHaveCount(0);
        await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveCount(0);expect(injections).toBe(1);expect(observedWrites).toHaveLength(0);
        await expect(p).toHaveURL(/\/invitation$/);await storage(p,[token,'Browser invitation pass 2026!']);
      }finally{await p.unroute(`**${publicBase}/inspect`);}
      const retry=await observed(p,publicBase+'/inspect',()=>button(p,'Thử lại').click(),'POST');
      expect(retry.status()).toBe(200);expect((await retry.json()).data.state).toBe('NEW_ACCOUNT');await publicReview(p);
      for(const name of ['Họ và tên','Mật khẩu mới','Nhập lại mật khẩu'])await expect(p.getByLabel(name,{exact:true})).toHaveValue('');
      await expect(ack(p)).not.toBeChecked();await expect(button(p,'Tạo tài khoản và tham gia')).toBeDisabled();expect(observedWrites).toHaveLength(0);
      await storage(p,[token,'Browser invitation pass 2026!']);
    }
    sql(`UPDATE organization_invitations SET created_at=created_at-interval '73 hours',expires_at=expires_at-interval '73 hours' WHERE id='${r.invitationId}';`);
    const result=await observed(p,publicBase+'/inspect',()=>p.goto('/invitation#token='+token),'POST');expect(result.status()).toBe(400);expect((await result.json()).error.code).toBe('INVALID_INVITATION');
    await expect(p).toHaveURL(/\/invitation$/);await expect(p.getByRole('heading',{name:'Lời mời không còn hiệu lực',exact:true})).toBeVisible();await expect(button(p,'Tạo tài khoản và tham gia')).toHaveCount(0);await expect(p.locator('main')).not.toContainText(r.email);await shot(p,i,'public-invalid');
    await p.reload();await expect(button(p,'Tạo tài khoản và tham gia')).toHaveCount(0);expect(observedWrites).toHaveLength(0);
  });

  test('INV-B14 committed malformed acceptance remains unknown after consumed-token reinspection',async({page:p},i)=>{
    const r=await create(p,email(i,14)),token=await tokenFor(p,r.email);await anonymous(p);await publicOpen(p,token);await newFields(p);await publicReview(p);const observedWrites=writes(p);
    await p.route(`**${publicBase}/accept`,async route=>{const actual=await route.fetch();expect(actual.status()).toBe(200);await route.fulfill({response:actual,json:{data:{unexpected:true}}});});
    try {await ack(p).check();await button(p,'Tạo tài khoản và tham gia').click();await expect(p.getByRole('heading',{name:'Chưa xác định kết quả',exact:true})).toBeVisible();}
    finally {await p.unroute(`**${publicBase}/accept`);}
    await p.evaluate(()=>dispatchEvent(new Event('focus')));await expect(p.getByRole('heading',{name:'Chưa xác định kết quả',exact:true})).toBeVisible();await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveCount(0);await storage(p,[token,'Browser invitation pass 2026!']);expect(observedWrites).toHaveLength(1);await shot(p,i,'public-unknown');
  });

  test('INV-B15 public observed account switch clears entered name/password before reinspection',async({page:p},i)=>{
    const r=await create(p,email(i,15)),token=await tokenFor(p,r.email);await anonymous(p);await publicOpen(p,token);await newFields(p);const observedWrites=writes(p);const sibling=await p.context().newPage();
    try {await login(sibling,'admin');await observed(p,'/api/v1/auth/session',()=>p.evaluate(()=>dispatchEvent(new Event('focus'))));
      await expect(button(p,'Đăng xuất để đổi tài khoản')).toBeVisible();await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveCount(0);await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveCount(0);
      await button(p,'Đăng xuất để đổi tài khoản').click();await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveValue('');await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveValue('');expect(observedWrites).toHaveLength(0);
    }finally{await sibling.close();}
  });

  test('INV-B16 list outage clears private data, real retry restores list; foreign detail stays denied',async({page:p},i)=>{
    const csrf=await data(p,'/api/v1/auth/csrf');const foreignSession=await p.context().browser().newContext({baseURL:process.env.E2E_BASE_URL||'http://127.0.0.1:8080'});const other=await foreignSession.newPage();let r;
    try {await login(other,'foreign');const c=await data(other,'/api/v1/auth/csrf');const result=await other.request.post(`/api/v1/organizations/${foreign}/invitations`,{headers:{[c.headerName]:c.token,'Idempotency-Key':randomUUID()},data:{email:email(i,16),acknowledgement:true}});expect(result.status()).toBe(201);r=(await result.json()).data;}
    finally{await foreignSession.close();}
    const local=await create(p,email(i,160));
    expect(csrf.token).toBeTruthy();const observedWrites=writes(p);
    const listed=await observed(p,base,()=>p.goto(home));expect(listed.status()).toBe(200);
    expect((await listed.json()).data.items.some(item=>item.id===local.invitationId)).toBe(true);
    await expect(hook(p,'row').filter({hasText:local.email})).toBeVisible();
    let faults=0;
    await p.route(`**${base}?*`,async route=>{
      if(route.request().method()!=='GET')return route.continue();
      const actual=await route.fetch();expect(actual.status()).toBe(200);
      expect((await actual.json()).data.items.some(item=>item.id===local.invitationId)).toBe(true);faults++;
      await route.fulfill({response:actual,status:503,json:{error:{code:'SERVICE_UNAVAILABLE',message:'Temporary QC transport fault'}}});
    });
    try {
      const failed=await observed(p,base,()=>p.reload());expect(failed.status()).toBe(503);
      await expect(p.getByRole('alert').filter({hasText:'Chưa thể tải lời mời. Vui lòng thử lại.'})).toBeVisible();
      await expect(p.getByRole('alert').filter({hasText:'Bạn không có quyền quản lý lời mời trong tổ chức này.'})).toHaveCount(0);
      for(const state of ['row','detail','review','result'])await expect(hook(p,state)).toHaveCount(0);
      await expect(p.locator('main')).not.toContainText(local.email);await expect(p.locator('main')).not.toContainText(r.email);
      expect(faults).toBe(1);expect(observedWrites).toHaveLength(0);
    }finally{await p.unroute(`**${base}?*`);}
    const restored=await observed(p,base,()=>button(p,'Thử lại').click());expect(restored.status()).toBe(200);
    expect((await restored.json()).data.items.some(item=>item.id===local.invitationId)).toBe(true);
    await expect(hook(p,'row').filter({hasText:local.email})).toBeVisible();
    await expect(p.getByRole('alert').filter({hasText:'Chưa thể tải lời mời. Vui lòng thử lại.'})).toHaveCount(0);expect(observedWrites).toHaveLength(0);
    const denied=await observed(p,base+'/'+r.invitationId+'/context',()=>p.goto(home+'/'+r.invitationId));expect(denied.status()).toBe(404);
    await expect(p.getByRole('alert').filter({hasText:'Bạn không có quyền quản lý lời mời trong tổ chức này.'})).toBeVisible();await expect(hook(p,'detail')).toHaveCount(0);await expect(p.locator('main')).not.toContainText(r.email);await p.evaluate(()=>document.dispatchEvent(new Event('visibilitychange')));await expect(hook(p,'review')).toHaveCount(0);expect(observedWrites).toHaveLength(0);
  });

  test('INV-B17 same-document fragment replacement guards drafts and fences late inspection',async({page:p},i)=>{
    const a=await create(p,email(i,17)),b=await create(p,`invitation-existing-${recipient(i,17)}@example.test`);
    const first=await tokenFor(p,a.email),second=await tokenFor(p,b.email);
    await anonymous(p);await publicOpen(p,first);await newFields(p);await ack(p).check();
    const observedWrites=writes(p);const documentId=await p.evaluate(()=>{window.__invitationQcDocument=crypto.randomUUID();return window.__invitationQcDocument;});
    // location.hash produces a real same-document hashchange (no synthetic event).
    p.removeAllListeners('dialog');
    let prompts=0;
    const change=token=>p.evaluate(value=>{location.hash='token='+value;},token);
    const refuse=p.waitForEvent('dialog').then(async d=>{expect(d.type()).toBe('confirm');prompts++;await d.dismiss();});
    await change(second);await refuse;await expect(p).toHaveURL(/\/invitation$/);
    await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveValue('Invitation Browser New');
    await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveValue('Browser invitation pass 2026!');
    const allow=p.waitForEvent('dialog').then(async d=>{expect(d.type()).toBe('confirm');prompts++;await d.accept();});
    const replaced=await observed(p,publicBase+'/inspect',async()=>{await change(second);await allow;},'POST');
    expect((await replaced.json()).data.state).toBe('SIGN_IN');await expect(p).toHaveURL(/\/invitation$/);
    await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveCount(0);await expect(p.getByLabel('Mật khẩu',{exact:true})).toHaveValue('');
    const held=barrier(),arrived=barrier(),finished=barrier();
    await p.route(`**${publicBase}/inspect`,async route=>{
      if(route.request().postDataJSON().token!==second)return route.continue();
      try{const actual=await route.fetch();expect(actual.status()).toBe(200);expect((await actual.json()).data.state).toBe('SIGN_IN');arrived.release();await held.wait();await route.fulfill({response:actual});}
      finally{finished.release();}
    });
    try{
      await p.evaluate(()=>dispatchEvent(new Event('focus')));await arrived.wait();
      // A busy preflight may also require the same dirty confirmation.
      p.on('dialog',async d=>{expect(d.type()).toBe('confirm');await d.accept();});
      const current=await observed(p,publicBase+'/inspect',()=>change(first),'POST');expect((await current.json()).data.state).toBe('NEW_ACCOUNT');
      await expect(p).toHaveURL(/\/invitation$/);await expect(p.getByLabel('Họ và tên',{exact:true})).toHaveValue('');
      await expect(p.getByLabel('Mật khẩu mới',{exact:true})).toHaveValue('');await expect(p.getByLabel('Nhập lại mật khẩu',{exact:true})).toHaveValue('');await expect(ack(p)).not.toBeChecked();
      const delivered=p.waitForEvent('requestfinished',{predicate:r=>new URL(r.url()).pathname===publicBase+'/inspect'&&r.postDataJSON()?.token===second});
      held.release();await finished.wait();await delivered;await p.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));await publicReview(p);await expect(p.getByLabel('Họ và tên',{exact:true})).toBeVisible();await expect(button(p,'Đăng nhập')).toHaveCount(0);
      expect(await p.evaluate(()=>window.__invitationQcDocument)).toBe(documentId);expect(prompts).toBe(2);expect(observedWrites).toHaveLength(0);
      await storage(p,[first,second,'Browser invitation pass 2026!']);
    }finally{held.release();await p.unroute(`**${publicBase}/inspect`);p.removeAllListeners('dialog');p.on('dialog',d=>d.dismiss());}
  });

});
