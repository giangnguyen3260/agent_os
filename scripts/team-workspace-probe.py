"""PostgreSQL proof of scoped workspace lanes, safe event metadata and no business writes."""
import datetime
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '14141414-1414-4414-8414-141414141414'
TEAM = 'e1000000-0000-4000-8000-000000000001'
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/workspace'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(path, data=None, headers=None):
    try:
        with client.open(urllib.request.Request(BASE + path, data=data, headers=headers or {}), timeout=20) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query, text=True, timeout=15).strip()


def normalized(row):
    value = dict(row)
    for key in ('waitingSince', 'createdAt'):
        if value.get(key) is not None:
            value[key] = datetime.datetime.fromisoformat(value[key].replace('Z', '+00:00'))
    return value


status, body = request('/api/v1/auth/csrf')
assert status == 200
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'workspace-team-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), {body['data']['headerName']: body['data']['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
assert status == 200
snapshot = f"""
SELECT row_to_json(t)::text FROM teams t WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(a)::text FROM agents a WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(t)::text FROM tasks t WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(e)::text FROM audit_events e WHERE organization_id='{ORG}' ORDER BY id;
SELECT count(*) FROM agent_configuration_versions WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_configuration_receipts WHERE organization_id='{ORG}';
SELECT count(*) FROM team_create_receipts WHERE organization_id='{ORG}';
"""
before = sql(snapshot)
expected = json.loads(sql(f"""SELECT coalesce(json_agg(x),'[]')::text FROM
(SELECT id,organization_id AS "organizationId",team_id AS "teamId",mission_id AS "missionId",title,status,
pending_reason AS "pendingReason",waiting_since AS "waitingSince",resolver FROM tasks
WHERE organization_id='{ORG}' AND team_id='{TEAM}' ORDER BY created_at DESC,id) x;"""))
status, result = request(ROOT)
assert status == 200
workspace = result['data']
assert workspace['team']['id'] == TEAM and workspace['team']['organizationId'] == ORG
assert workspace['team']['managerName'] == 'Workspace Agent 01'
assert workspace['team']['concurrency'] == 4 and workspace['team']['monthlyBudget'] == 100
assert workspace['summary'] == {'agentCount': 24, 'totalTasks': 31, 'runningTasks': 1, 'pendingTasks': 24, 'reviewTasks': 1}
datetime.datetime.fromisoformat(workspace['generatedAt'].replace('Z', '+00:00'))
lanes = {'INTAKE': ['DRAFT'], 'READY': ['READY'], 'RUNNING': ['RUNNING'], 'REVIEW': ['IN_REVIEW'],
         'PENDING': ['WAITING', 'BLOCKED'], 'DONE': ['COMPLETED'], 'FAILED': ['FAILED'], 'CANCELLED': ['CANCELLED']}
assert [row['lane'] for row in workspace['lanes']] == list(lanes)
for lane, states in lanes.items():
    matches = [row for row in expected if row['status'] in states]
    board = next(row for row in workspace['lanes'] if row['lane'] == lane)
    assert board['total'] == len(matches)
    assert list(map(normalized, board['items'])) == list(map(normalized, matches[:5]))
    actual = []
    for page in range(max(1, (len(matches) + 19) // 20)):
        status, body = request(ROOT + '/tasks?' + urllib.parse.urlencode({'lane': lane, 'page': page}))
        assert status == 200
        data = body['data']
        assert data['page'] == page and data['size'] == 20 and data['total'] == len(matches)
        actual.extend(data['items'])
    assert list(map(normalized, actual)) == list(map(normalized, matches))
events = []
for page in range(2):
    status, body = request(ROOT + '/activity?page=' + str(page))
    assert status == 200 and body['data']['total'] == 25
    assert body['data']['size'] == 20 and body['data']['page'] == page
    events.extend(body['data']['items'])
assert len(events) == 25 and len({row['id'] for row in events}) == 25
for event in events:
    assert set(event) == {'id', 'eventType', 'actorType', 'createdAt'}
    assert event['eventType'] == 'WORKSPACE_TASK_EVENT' and event['actorType'] == 'HUMAN'
expected_ids = sql(f"SELECT id FROM audit_events WHERE organization_id='{ORG}' AND event_type='WORKSPACE_TASK_EVENT' ORDER BY created_at DESC,id;").splitlines()
assert [row['id'] for row in events] == expected_ids
agent_ids = []
for page in range(2):
    status, body = request(f'/api/v1/organizations/{ORG}/agents/directory?teamId={TEAM}&page={page}')
    assert status == 200 and body['data']['total'] == 24
    assert all(row['organizationId'] == ORG and row['teamId'] == TEAM for row in body['data']['items'])
    agent_ids.extend(row['id'] for row in body['data']['items'])
assert len(set(agent_ids)) == 24
for suffix in ['/tasks?lane=OTHER', '/tasks?lane=PENDING&page=-1', '/activity?page=100001']:
    assert request(ROOT + suffix)[0] == 400
foreign_team = f'/api/v1/organizations/{ORG}/teams/f1000000-0000-4000-8000-000000000001/workspace'
assert request(foreign_team)[0] == 404
assert request('/api/v1/organizations/15151515-1515-4515-8515-151515151515/teams/f1000000-0000-4000-8000-000000000001/workspace')[0] == 403
assert before == sql(snapshot), 'Workspace reads changed business state'
print('PASS: PostgreSQL Team workspace snapshot, bounded lanes, pending/Agent/event pagination, safe activity scope/fields and zero mutation')
