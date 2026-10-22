"""Verify actual PostgreSQL metadata projection, foreign-scope denial and zero business writes."""
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ORG = '26262626-2626-4626-8626-262626262626'
FOREIGN = '27272727-2727-4727-8727-272727272727'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ROOT = f'/api/v1/organizations/{ORG}/approvals'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

def request(path, body=None, headers=None):
    try:
        response = client.open(urllib.request.Request(BASE + path, data=body, headers=headers or {}), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        assert not any(x in raw for x in ('FOREIGN_APPROVAL_PRIVATE', 'PRIVATE_APPROVAL_GOAL', 'MOVED_REQUESTER_PRIVATE', 'password_hash', 'requestPayload', 'decisionReason'))
        return response.status, json.loads(raw)

def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c', 'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'], cwd=Path(__file__).resolve().parents[1], input=query, text=True, timeout=20).strip()

def main():
    status, body = request('/api/v1/auth/csrf')
    assert status == 200
    csrf = body['data']
    status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({'username': 'approval-member@example.test', 'password': os.environ['E2E_PASSWORD']}).encode(), {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
    assert status == 200
    tables = ('approval_requests', 'teams', 'agents', 'missions', 'tasks', 'audit_events')
    snapshot = '\n'.join(f'SELECT row_to_json(x)::text FROM {t} x ORDER BY id;' for t in tables)
    before = sql(snapshot)
    for group, expected in [('PENDING',24),('NEEDS_INFORMATION',3),('DECIDED',5)]:
        rows=[]
        for page in range(2):
            status, body=request(ROOT+f'?group={group}&page={page}')
            assert status==200
            data=body['data']
            assert data['counts']=={'pending':24,'needsInformation':3,'decided':5}
            assert data['total']==expected and data['page']==page and data['size']==20
            rows.extend(data['items'])
        states = "('APPROVED','REJECTED','EXPIRED','CANCELLED','INVALIDATED')" if group=='DECIDED' else f"('{group}')"
        ids=json.loads(sql(f"SELECT json_agg(id ORDER BY created_at DESC,id)::text FROM approval_requests WHERE organization_id='{ORG}' AND status IN {states};"))
        assert [r['id'] for r in rows]==ids
        for r in rows:
            assert r['organizationId']==ORG and r['teamName']=='Approval Delivery' and r['taskTitle']=='Approval Task'
            if r['id'].endswith('000000000001'):
                assert r['requesterName'] is None and r['requesterId'] is None
            else:
                assert r['requesterName']=='Approval Requester'
    assert request(ROOT+'?page=-1')[0]==400
    assert request(f'/api/v1/organizations/{FOREIGN}/approvals')[0]==403
    assert sql(snapshot)==before, 'Read endpoints changed business data'
    print('PASS: PostgreSQL Approval Center exact groups/counts/order/paging, moved requester, tenant denial and no business writes')

if __name__=='__main__':
    main()
