import {test,expect} from '@playwright/test';
const org='95190000-0000-4000-8000-000000000001',run='95190000-0000-4000-8000-000000000002';
async function mock(page,override={}){
 const state={denied:false};await page.route('**/api/v1/**',async route=>{
 const url=new URL(route.request().url());let data;
 if(url.pathname.endsWith('/session'))data={id:org,email:'fixture@example.test',displayName:'Admin'};
 else if(url.pathname.includes('/auth/workspaces/')){if(state.denied)return route.fulfill({status:403});data={id:org,role:'ADMIN'};}
 else if(url.pathname.endsWith('/execution-runs/'+run)) data={id:run,teamId:'95190000-0000-4000-8000-000000000003',missionId:'95190000-0000-4000-8000-000000000004',taskId:'95190000-0000-4000-8000-000000000005',status:'BLOCKED',revision:'0',waitReason:null,attempts:[],dispatches:override.dispatches ?? ['QUEUED','CLAIMED','UNKNOWN','FINISHED'].map((state,i)=>({id:'95190000-0000-4000-8000-00000000001'+i,state,sourceRevision:String(i),createdAt:'2026-11-23T00:00:00Z',handedOff:state!=='QUEUED'})),accounting:{settledMicrousd:'9007199254740993',heldMicrousd:'10',unknownMicrousd:'1000000',unresolvedReservations:'2',inputTokens:'123',outputTokens:'45',...override}};
 else return route.fulfill({status:404});return route.fulfill({json:{data}});
 });await page.goto(`/workspace/${org}/execution-runs/${run}`);return state;
}

test('shows queue claim unknown and finished as command states',async({page})=>{
 await mock(page);await expect(page.getByRole('heading',{name:'100 lệnh Start gần nhất',exact:true})).toBeVisible();await expect(page.locator('article')).toHaveCount(4);await expect(page.getByText('Kết quả request chưa rõ. Cần đối soát trước khi quyết định chạy lại.',{exact:true})).toBeVisible();await expect(page.getByText(/FINISHED biểu thị lệnh đã kết thúc xử lý/)).toBeVisible();await expect(page.getByRole('link',{name:'Khởi chạy Task',exact:true})).toHaveCount(0);
});
test('unknown dispatch vocabulary fails closed',async({page})=>{
 await mock(page,{dispatches:[{id:'95190000-0000-4000-8000-000000000010',state:'SUCCESS',sourceRevision:'1',createdAt:'2026-11-23T00:00:00Z',handedOff:true}]});await expect(page.getByRole('heading',{name:'100 lệnh Start gần nhất',exact:true})).toHaveCount(0);
});
test('revocation clears dispatch history on focus',async({page})=>{
 const state=await mock(page);await expect(page.locator('article')).toHaveCount(4);state.denied=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(page.locator('article')).toHaveCount(0);
});
