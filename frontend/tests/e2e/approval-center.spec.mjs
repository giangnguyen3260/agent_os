import {test, expect} from '@playwright/test';

const org = '26262626-2626-4626-8626-262626262626';
const foreign = '27272727-2727-4727-8727-272727272727';
const empty = '28282828-2828-4828-8828-282828282828';
const api = `/api/v1/organizations/${org}/approvals`;
const url = `/workspace/${org}/approvals`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const rows = p => p.getByTestId('approval-record');
async function login(p, role = 'admin') {
  expect(process.env.E2E_PASSWORD).toBeTruthy();
  await p.goto('/login');
  await p.getByLabel('Email', {exact:true}).fill(`approval-${role}@example.test`);
  await p.getByLabel('Mật khẩu', {exact:true}).fill(process.env.E2E_PASSWORD);
  await button(p,'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
}
async function open(p, suffix='') {
  const [response] = await Promise.all([p.waitForResponse(r=>new URL(r.url()).pathname===api && r.request().method()==='GET'),p.goto(url+suffix)]);
  expect(response.status()).toBe(200);
  return (await response.json()).data;
}

test('AC01 real approval paging, metadata inspection, linked task and reload', async ({page:p})=>{
  await login(p);
  const writes=[];p.on('request',r=>{if(r.method()!=='GET' && r.url().includes('/api/v1/organizations/'))writes.push(r.url());});
  const data=await open(p);
  expect(data.counts).toEqual({pending:24,needsInformation:3,decided:5});
  await expect(rows(p)).toHaveCount(20);
  await expect(rows(p).first()).toContainText('Approval request 01');
  await expect(rows(p).first()).toContainText('Requester unavailable');
  await expect(p.getByRole('main')).not.toContainText('MOVED_REQUESTER_PRIVATE');
  await button(rows(p).first(),'Inspect').click();
  await expect(p.getByRole('region',{name:'Request metadata'})).toContainText('Recorded risk: HIGH');
  await expect(p.getByRole('link',{name:'Open task: Approval Task'})).toHaveAttribute('href',`/workspace/${org}/teams/26100000-0000-4000-8000-000000000001/missions/26300000-0000-4000-8000-000000000001/tasks/26400000-0000-4000-8000-000000000001`);
  await button(p,'Next').click();await expect(rows(p)).toHaveCount(4);
  await expect(rows(p).first()).toContainText('Approval request 21');
  await expect(p.getByRole('region',{name:'Request metadata'})).toHaveCount(0);
  await p.reload();await expect(rows(p)).toHaveCount(4);
  await button(p,'Previous').click();await expect(rows(p)).toHaveCount(20);
  expect(writes).toEqual([]);
  expect(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth)).toBe(true);
});

test('AC02 information and closed groups preserve real statuses and URL state', async ({page:p})=>{
  await login(p);await open(p);
  await button(p,'Needs information · 3').click();await expect(rows(p)).toHaveCount(3);
  await expect(rows(p).first()).toContainText('NEEDS_INFORMATION');
  await expect(p).toHaveURL(/group=NEEDS_INFORMATION/);
  await button(p,'Decided · 5').click();await expect(rows(p)).toHaveCount(5);
  for(const state of ['APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED'])await expect(p.locator('.status-badge').filter({hasText:new RegExp(`^${state}$`)})).toHaveCount(1);
  await p.reload();await expect(rows(p)).toHaveCount(5);
  await expect(p.getByRole('main')).toContainText('không có nghĩa đã được phê duyệt');
  await expect(p.getByRole('button',{name:/^(Approve|Reject|Bulk approve)$/})).toHaveCount(0);
});

test('AC03 empty organization, invalid filters and out-of-range pages stay honest',async ({page:p})=>{
  await login(p);await p.goto(`/workspace/${empty}/approvals`);
  await expect(p.getByRole('status')).toHaveText('No approval records in this group.');
  await expect(button(p,'Pending · 0')).toBeVisible();
  await open(p,'?page=100000');await expect(rows(p)).toHaveCount(0);
  await expect(p.getByRole('status')).toHaveText('No approval records in this group.');
  for(const suffix of ['?group=ALL','?page=-1']){
    await p.goto(url+suffix);await expect(p.getByRole('alert')).toContainText('Invalid approval filters');
    await expect(rows(p)).toHaveCount(0);
    await button(p,'Clear filters').click();await expect(rows(p)).toHaveCount(20);
  }
});

test('AC04 member reads do not confer decision rights; foreign org denied',async ({page:p})=>{
  await login(p,'member');await open(p);await expect(rows(p)).toHaveCount(20);
  const denied=await p.request.get(`/api/v1/organizations/${foreign}/approvals`);expect(denied.status()).toBe(403);
  await p.goto(`/workspace/${foreign}/approvals`);await expect(p.getByRole('alert')).toContainText('Approval access is unavailable');
  await expect(rows(p)).toHaveCount(0);await expect(p.getByRole('main')).not.toContainText('Approval request');
});

test('AC05 failures and malformed refresh discard rows/counts/inspection, real GET retry restores',async ({page:p})=>{
  await login(p);const data=await open(p);
  const cases=[null,{...data,counts:{...data.counts,pending:999}},{...data,items:data.items.map((x,i)=>i?x:{...x,organizationId:foreign})},{...data,items:data.items.map((x,i)=>i?x:{...x,requestPayload:'PRIVATE'})}];
  for(const broken of cases){
    await button(rows(p).first(),'Inspect').click();
    await p.route(`**${api}*`,r=>r.fulfill({status:200,json:{data:broken}}));
    await button(p,'Refresh').click();await expect(p.getByRole('alert')).toContainText('Invalid approval response');
    await expect(rows(p)).toHaveCount(0);await expect(p.getByRole('region',{name:'Request metadata'})).toHaveCount(0);
    await expect(button(p,'Pending · 24')).toHaveCount(0);
    await p.unroute(`**${api}*`);await button(p,'Try again').click();await expect(rows(p)).toHaveCount(20);
  }
  await p.route(`**${api}*`,r=>r.fulfill({status:503,json:{message:'Unavailable'}}));
  await button(p,'Refresh').click();await expect(p.getByRole('alert')).toContainText('Unable to load approvals');await expect(rows(p)).toHaveCount(0);
  await p.unroute(`**${api}*`);await button(p,'Try again').click();await expect(rows(p)).toHaveCount(20);
});

test('AC06 revoked API read clears private state; compact menu supports Escape/focus',async ({page:p},testInfo)=>{
  await login(p);await open(p);await expect(rows(p)).toHaveCount(20);
  if(testInfo.project.name!=='desktop'){
    await button(p,'Menu').click();await expect(p.getByRole('dialog')).toBeVisible();await p.keyboard.press('Escape');await expect(p.getByRole('dialog')).not.toBeVisible();await expect(button(p,'Menu')).toBeFocused();
  }
  await p.route(`**${api}*`,r=>r.fulfill({status:403,json:{message:'Denied'}}));
  await button(p,'Refresh').click();await expect(p.getByRole('alert')).toContainText('Approval access is unavailable');await expect(rows(p)).toHaveCount(0);
});
