import {test, expect} from '@playwright/test';
import {execFileSync} from 'node:child_process';

const org = '88888888-8888-4888-8888-888888888888';
const foreign = '99999999-9999-4999-8999-999999999999';
const url = `/workspace/${org}/agents`;
const api = `/api/v1/organizations/${org}/agents/directory`;
const button = (p, name) => p.getByRole('button', {name, exact: true});
const rows = p => p.getByTestId('directory-row');
function sql(input) {
  return execFileSync('docker', ['compose', 'exec', '-T', 'postgres', 'sh', '-c',
    'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], {
    cwd: new URL('../../../', import.meta.url), input, encoding: 'utf8', timeout: 15000,
    stdio: ['pipe', 'pipe', 'pipe'],
  }).trim();
}
async function login(p, member = false) {
  await p.goto('/login');
  await p.getByLabel('Email', {exact:true}).fill(`directory-${member ? 'member' : 'admin'}@example.test`);
  await p.getByLabel('Mật khẩu', {exact:true}).fill(process.env.E2E_PASSWORD);
  await button(p, 'Đăng nhập').click();
  await expect(p).toHaveURL(/\/workspace$/);
  await p.goto(url);
  await expect(rows(p)).toHaveCount(20);
}
async function filter(p, query, state = '') {
  await p.getByLabel('Search agents', {exact:true}).fill(query);
  await p.getByLabel('Status', {exact:true}).selectOption(state);
  await button(p, 'Apply filters').click();
}

test.describe('real Agent directory', () => {
  test('stable pages, URL/back restoration and scoped real fields', async ({page:p}) => {
    await login(p);
    await expect(rows(p).first()).toContainText('Directory 100%_ready!');
    await expect(rows(p).first()).toContainText('ARCHIVED / Unavailable');
    await expect(rows(p).first()).toContainText('Configured concurrency: 2');
    await button(p,'Next').click();
    await expect(rows(p)).toHaveCount(5);
    await expect(p).toHaveURL(/page=1/);
    await expect(rows(p).first()).toContainText('Directory agent 05');
    await p.goBack();
    await expect(rows(p)).toHaveCount(20);
    await expect(button(p,'Previous')).toBeDisabled();
    expect(await p.locator('body').evaluate(e => e.scrollWidth <= window.innerWidth)).toBeTruthy();
  });
  test('literal search, role/status and Team filters with real counts', async ({page:p}) => {
    await login(p);
    await filter(p,'%');
    await expect(rows(p)).toHaveCount(1);
    await expect(rows(p)).toContainText('Directory 100%_ready!');
    await filter(p,'Reviewer','DRAFT');
    await expect(rows(p)).toHaveCount(3);
    await expect(rows(p).first()).toContainText('DRAFT / Unavailable');
    await p.reload();
    await expect(p.getByLabel('Search agents', {exact:true})).toHaveValue('Reviewer');
    await expect(rows(p)).toHaveCount(3);
    const r = await p.request.get(`${api}?teamId=81000000-0000-4000-8000-000000000001`);
    expect(r.status()).toBe(200); expect((await r.json()).data.total).toBe(13);
    await button(p,'Clear filters').click(); await expect(rows(p)).toHaveCount(20);
  });
  test('no-results, navigation to Studio/create and compact keyboard menu', async ({page:p}, info) => {
    await login(p);
    await filter(p,'does-not-exist');
    await expect(p.getByRole('heading',{name:'No matching agents'})).toBeVisible();
    await expect(rows(p)).toHaveCount(0);
    await button(p,'Clear filters').click(); await expect(rows(p)).toHaveCount(20);
    await rows(p).first().getByRole('link',{name:'Open Studio',exact:true}).click();
    await expect(p).toHaveURL(/\/studio$/);
    await p.goto(url); await expect(rows(p)).toHaveCount(20);
    if(info.project.name !== 'desktop') {
      const menu=button(p,'Menu'); await menu.click();
      await expect(p.getByRole('dialog',{name:'Workspace navigation'})).toBeVisible();
      await p.keyboard.press('Escape'); await expect(menu).toBeFocused();
    }
    await p.getByRole('link',{name:'Create Agent',exact:true}).click();
    await expect(p).toHaveURL(/\/agents\/new$/);
  });
  test('member read access and revoked membership clear current rows', async ({page:p}) => {
    await login(p,true);
    await expect(p.getByRole('link',{name:'Create Agent',exact:true})).toHaveCount(0);
    await expect(p.getByRole('link',{name:'Review activation',exact:true})).toHaveCount(0);
    sql(`DELETE FROM organization_memberships WHERE organization_id='${org}' AND account_id=(SELECT id FROM human_accounts WHERE email='directory-member@example.test');`);
    try {
      await button(p,'Refresh').click();
      await expect(rows(p)).toHaveCount(0);
      await expect(p.getByRole('alert')).toBeVisible();
      expect((await p.request.get(api)).status()).toBe(403);
    } finally {
      sql(`INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'${org}','MEMBER' FROM human_accounts WHERE email='directory-member@example.test' ON CONFLICT DO NOTHING;`);
    }
  });
  test('network/null/wrong-scope responses clear old rows and allow retry', async ({page:p}) => {
    await login(p);
    let mode='error';
    await p.route(`**${api}*`, async route => {
      if(mode==='error') return route.fulfill({status:503,json:{}});
      if(mode==='null') return route.fulfill({status:200,json:null});
      const r=await route.fetch(); const body=await r.json();
      body.data.items[0].organizationId=foreign;
      return route.fulfill({json:body});
    });
    for(mode of ['error','null','scope']) {
      await button(p, mode==='error' ? 'Refresh' : 'Thử lại').click();
      await expect(p.getByRole('alert')).toBeVisible();
      await expect(rows(p)).toHaveCount(0);
    }
    await p.unroute(`**${api}*`);
    await button(p,'Thử lại').click(); await expect(rows(p)).toHaveCount(20);
  });
  test('foreign Team/workspace and invalid filters reject without leaking names', async ({page:p}) => {
    await login(p);
    expect((await p.request.get(`${api}?teamId=91000000-0000-4000-8000-000000000001`)).status()).toBe(404);
    expect((await p.request.get(`/api/v1/organizations/${foreign}/agents/directory`)).status()).toBe(403);
    await p.goto(url+'?status=READY');
    await expect(p.getByRole('alert')).toContainText('Invalid directory filters');
    await expect(rows(p)).toHaveCount(0);
    await button(p,'Clear filters').click(); await expect(rows(p)).toHaveCount(20);
    await p.goto(`/workspace/${foreign}/agents`);
    await expect(rows(p)).toHaveCount(0);
    await expect(p.getByRole('alert')).toBeVisible();
    await expect(p.locator('body')).not.toContainText('Foreign Private');
  });
});
