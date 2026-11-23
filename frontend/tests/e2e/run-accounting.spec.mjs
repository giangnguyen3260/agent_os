import {test,expect} from '@playwright/test';
const org='95190000-0000-4000-8000-000000000001',run='95190000-0000-4000-8000-000000000002';
async function mock(page,override={}){
 const state={denied:false};await page.route('**/api/v1/**',async route=>{
 const url=new URL(route.request().url());let data;
 if(url.pathname.endsWith('/session'))data={id:org,email:'fixture@example.test',displayName:'Admin'};
 else if(url.pathname.includes('/auth/workspaces/')){if(state.denied)return route.fulfill({status:403});data={id:org,role:'ADMIN'};}
 else if(url.pathname.endsWith('/execution-runs/'+run)) data={id:run,teamId:'95190000-0000-4000-8000-000000000003',missionId:'95190000-0000-4000-8000-000000000004',taskId:'95190000-0000-4000-8000-000000000005',status:'BLOCKED',revision:'0',waitReason:null,attempts:[],accounting:{settledMicrousd:'9007199254740993',heldMicrousd:'10',unknownMicrousd:'1000000',unresolvedReservations:'2',inputTokens:'123',outputTokens:'45',...override}};
 else return route.fulfill({status:404});return route.fulfill({json:{data}});
 });await page.goto(`/workspace/${org}/execution-runs/${run}`);return state;
}
test('shows exact ledger amounts without losing integer precision',async({page})=>{
 await mock(page);await expect(page.getByText('Đã đối soát: 9007199254.740993 USD',{exact:true})).toBeVisible();await expect(page.getByText('Giữ cho kết quả UNKNOWN: 1.000000 USD',{exact:true})).toBeVisible();await expect(page.getByText('Khoản chưa đối soát: 2',{exact:true})).toBeVisible();
});
test('invalid numeric accounting cannot be displayed as verified cost',async({page})=>{
 await mock(page,{settledMicrousd:'-1'});await expect(page.getByRole('heading',{name:'Chi phí và token đã ghi nhận',exact:true})).toHaveCount(0);
});
test('fresh role revocation removes accounting on focus',async({page})=>{
 const state=await mock(page);await expect(page.getByText('Đã đối soát: 9007199254.740993 USD',{exact:true})).toBeVisible();state.denied=true;await page.evaluate(()=>window.dispatchEvent(new Event('focus')));await expect(page.getByRole('heading',{name:'Chi phí và token đã ghi nhận',exact:true})).toHaveCount(0);
});
