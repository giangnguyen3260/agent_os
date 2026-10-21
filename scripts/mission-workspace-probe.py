"""PostgreSQL Mission workspace exact read projection and no-write boundary."""
import datetime
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '20202020-2020-4020-8020-202020202020'
TEAM = '20100000-0000-4000-8000-000000000001'
MISSION = '20300000-0000-4000-8000-000000000001'
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/workspace'
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
        input=query, text=True, timeout=20).strip()


def normalize(row):
    value = dict(row)
    for key in ('waitingSince', 'createdAt'):
        if value.get(key) is not None:
            value[key] = datetime.datetime.fromisoformat(value[key].replace('Z', '+00:00'))
    return value


status, body = request('/api/v1/auth/csrf')
assert status == 200
csrf = body['data']
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'mission-workspace-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
assert status == 200
snapshot = '\n'.join(f"SELECT row_to_json(x)::text FROM {table} x WHERE organization_id='{ORG}' ORDER BY id;"
                     for table in ('teams', 'agents', 'missions', 'tasks', 'audit_events'))
snapshot += f"\nSELECT count(*) FROM mission_create_receipts WHERE organization_id='{ORG}';"
before = sql(snapshot)
status, body = request(ROOT)
assert status == 200
workspace = body['data']
assert set(workspace) == {'generatedAt', 'mission', 'summary'}
assert workspace['mission'] == {'id': MISSION, 'organizationId': ORG, 'teamId': TEAM,
    'teamName': 'Mission Control Delivery', 'title': 'Mission Control Review', 'status': 'RUNNING',
    'priority': 'HIGH', 'managerAgentId': '20200000-0000-4000-8000-000000000001', 'managerName': 'Control Manager'}
assert workspace['summary'] == {'totalTasks': 31, 'completedTasks': 1, 'runningTasks': 1, 'pendingTasks': 24, 'reviewTasks': 1}
datetime.datetime.fromisoformat(workspace['generatedAt'].replace('Z', '+00:00'))
expected = json.loads(sql(f'''SELECT coalesce(json_agg(x),'[]')::text FROM
(SELECT id,organization_id AS "organizationId",team_id AS "teamId",mission_id AS "missionId",title,status,
pending_reason AS "pendingReason",waiting_since AS "waitingSince",resolver FROM tasks
WHERE organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}' ORDER BY created_at DESC,id) x;'''))
for state in ('ALL', 'PENDING', 'DRAFT', 'READY', 'RUNNING', 'WAITING', 'BLOCKED', 'IN_REVIEW', 'COMPLETED', 'FAILED', 'CANCELLED'):
    matches = [row for row in expected if state == 'ALL' or (row['status'] in ('WAITING', 'BLOCKED') if state == 'PENDING' else row['status'] == state)]
    actual = []
    for page in range(max(1, (len(matches) + 19) // 20)):
        status, body = request(ROOT + '/tasks?' + urllib.parse.urlencode({'status': state, 'page': page}))
        assert status == 200
        data = body['data']
        assert data['size'] == 20 and data['page'] == page and data['total'] == len(matches)
        actual.extend(data['items'])
    assert list(map(normalize, actual)) == list(map(normalize, matches)), state
    assert all(set(row) == set(expected[0]) for row in actual)
events = []
for page in range(2):
    status, body = request(ROOT + '/activity?page=' + str(page))
    assert status == 200 and body['data']['total'] == 25 and body['data']['size'] == 20
    events.extend(body['data']['items'])
assert len(events) == 25 and len({e['id'] for e in events}) == 25
for event in events:
    assert set(event) == {'id', 'eventType', 'actorType', 'createdAt'}
    assert event['eventType'] == 'CONTROL_TASK_EVENT' and event['actorType'] == 'HUMAN'
expected_ids = sql(f"SELECT id FROM audit_events WHERE organization_id='{ORG}' AND event_type='CONTROL_TASK_EVENT' ORDER BY created_at DESC,id;").splitlines()
assert [e['id'] for e in events] == expected_ids
empty = ROOT.replace(MISSION, '20300000-0000-4000-8000-000000000002')
status, body = request(empty)
assert status == 200
assert all(n == 0 for n in body['data']['summary'].values())
assert body['data']['mission']['managerAgentId'] is None and body['data']['mission']['managerName'] is None
for suffix in ('/tasks', '/activity'):
    status, body = request(empty + suffix)
    assert status == 200 and body['data']['items'] == [] and body['data']['total'] == 0
for suffix in ('/tasks?page=-1', '/tasks?page=100001', '/tasks?status=UNKNOWN', '/activity?page=-1', '/activity?page=bad'):
    assert request(ROOT + suffix)[0] == 400
for suffix in ('/tasks?page=100000', '/activity?page=100000'):
    status, body = request(ROOT + suffix)
    assert status == 200 and body['data']['items'] == []
for invalid in (ROOT.replace(MISSION, '20300000-0000-4000-8000-000000000004'),
                ROOT.replace(TEAM, '20100000-0000-4000-8000-000000000002'),
                ROOT.replace(MISSION, '21300000-0000-4000-8000-000000000001')):
    for suffix in ('', '/tasks', '/activity'):
        assert request(invalid + suffix)[0] == 404
assert request(ROOT.replace(ORG, '21212121-2121-4121-8121-212121212121'))[0] == 403
assert before == sql(snapshot), 'Mission workspace GET changed business state'
print('PASS: PostgreSQL Mission Control summary/task filters/paging/pending details, safe mixed-reference event scope, legacy/empty/scoped reads and zero writes')
