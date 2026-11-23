import {test,expect} from '@playwright/test';
const org='77777777-7777-4777-8777-777777777777',team='75000000-0000-4000-8000-000000000001',mission='75000000-0000-4000-8000-000000000002',agent='75000000-0000-4000-8000-000000000003',grant='75000000-0000-4000-8000-000000000004';
const scope={organizationId:org,teamId:team,missionId:mission,agentId:agent};
const path=`/workspace/${org}/teams/${team}/missions/${mission}/text-tool-grants/${agent}`;
async function mock(page,options={}){
 const state={denied:false,lost:false,bad:false,grant:null,commands:[],receipts:new Map(),...options};
 await page.route('**/api/v1/**',async route=>{
  const r=route.request(),url=new URL(r.url());let data;
  if(url.pathname.endsWith('/session'))data={id:org,email:'admin@example.test',displayName:'Admin'};
  else if(url.pathname.includes('/auth/workspaces/')){if(state.denied)return route.fulfill({status:403});data={id:org,role:'ADMIN'};}
  else if(url.pathname.endsWith('/csrf'))data={headerName:'X-CSRF-TOKEN',token:'fixture'};
  else if(url.pathname.includes('/text-tool-grants/')){
   if(r.method()==='POST'){
    const c=r.postDataJSON();state.commands.push(c);if(state.stale){state.stale=false;return route.fulfill({status:409});}data=state.receipts.get(c.idempotencyKey);
    if(!data){state.grant={id:grant,issuedAt:'2026-11-23T00:00:00Z',expiresAt:'2026-11-23T01:00:00Z',revokedAt:c.operation==='REVOKE'?'2026-11-23T00:01:00Z':null};data={scope,idempotencyKey:c.idempotencyKey,operation:c.operation,grantId:grant,recordedAt:'2026-11-23T00:00:00Z'};state.receipts.set(c.idempotencyKey,data);}
    if(state.lost){state.lost=false;return route.abort('failed');}
   } else data={scope,tool:'text.metrics',version:state.bad?2:1,capability:'text:inspect',action:'INSPECT',grant:state.grant,canIssue:!state.grant||!!state.grant.revokedAt,canRevoke:!!state.grant&&!state.grant.revokedAt,fingerprint:(state.grant?'b':'a').repeat(64)};
  }else return route.fulfill({status:404});
  return route.fulfill({json:{data}});
 });return state;
}
test('explicit grant and revoke show current scope without executing tool',async({page})=>{
 const state=await mock(page);await page.goto(path);
 const issue=page.getByRole('button',{name:'Cấp quyền trong một giờ',exact:true});await expect(issue).toBeDisabled();
 await page.getByRole('checkbox').check();await issue.click();
 await expect(page.getByRole('heading',{name:'Biên nhận thay đổi quyền',exact:true})).toBeVisible();
 await page.getByRole('checkbox').check();await page.getByRole('button',{name:'Thu hồi quyền',exact:true}).click();
 await expect(page.getByText('Đã thu hồi:',{exact:false})).toBeVisible();expect(state.commands.map(x=>x.operation)).toEqual(['ISSUE','REVOKE']);
 await expect(page.locator('body')).toContainText('Luồng Agent tự gọi Tool đang chờ tích hợp.');
 expect(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBeTruthy();
});
test('lost response retries exact command and authority loss clears private grant',async({page})=>{
 const state=await mock(page,{lost:true});await page.goto(path);await page.getByRole('checkbox').check();await page.getByRole('button',{name:'Cấp quyền trong một giờ',exact:true}).click();
 await page.getByRole('button',{name:'Gửi lại đúng lệnh',exact:true}).click();await expect(page.getByRole('heading',{name:'Biên nhận thay đổi quyền',exact:true})).toBeVisible();expect(state.commands[0]).toEqual(state.commands[1]);
 state.denied=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(page.getByRole('heading',{name:'Quyền hiện tại',exact:true})).toHaveCount(0);await expect(page.getByRole('heading',{name:'Biên nhận thay đổi quyền',exact:true})).toHaveCount(0);
});
test('unsupported catalog version cannot render grant authority',async({page})=>{await mock(page,{bad:true});await page.goto(path);await expect(page.getByText('Không đọc được quyền hiện tại. Công cụ có thể chưa được bật.',{exact:true})).toBeVisible();await expect(page.getByRole('checkbox')).toHaveCount(0);});

test('first stale rejection reloads and requires fresh confirmation',async({page})=>{const state=await mock(page,{stale:true});await page.goto(path);await page.getByRole('checkbox').check();await page.getByRole('button',{name:'Cấp quyền trong một giờ',exact:true}).click();await expect(page.getByText('Lệnh chưa được chấp nhận. Kiểm tra quyền hiện tại và xác nhận lại.',{exact:true})).toBeVisible();await expect(page.getByRole('button',{name:'Cấp quyền trong một giờ',exact:true})).toBeDisabled();await page.getByRole('checkbox').check();await page.getByRole('button',{name:'Cấp quyền trong một giờ',exact:true}).click();await expect(page.getByRole('heading',{name:'Biên nhận thay đổi quyền',exact:true})).toBeVisible();expect(state.commands[0].idempotencyKey).not.toBe(state.commands[1].idempotencyKey);});
