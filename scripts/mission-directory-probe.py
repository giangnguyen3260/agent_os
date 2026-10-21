"""Native PostgreSQL Mission projection, filtering and read-only proof."""
import datetime
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '18181818-1818-4818-8818-181818181818'
TEAM = '18100000-0000-4000-8000-000000000001'
ROOT = f'/api/v1/organizations/{ORG}/missions/directory'
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


def normalized(row):
    value = dict(row)
    value['createdAt'] = datetime.datetime.fromisoformat(value['createdAt'].replace('Z', '+00:00'))
    return value


status, body = request('/api/v1/auth/csrf')
assert status == 200
csrf = body['data']
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'mission-directory-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
assert status == 200
snapshot = '\n'.join(f"SELECT row_to_json(x)::text FROM {table} x WHERE organization_id='{ORG}' ORDER BY id;"
                     for table in ('teams', 'agents', 'missions', 'tasks', 'audit_events'))
snapshot += f"\nSELECT count(*) FROM mission_create_receipts WHERE organization_id='{ORG}';"
before = sql(snapshot)
expected = json.loads(sql(f'''SELECT coalesce(json_agg(x),'[]')::text FROM
(SELECT m.id,m.organization_id AS "organizationId",m.title,m.status,m.priority,
m.team_id AS "teamId",t.name AS "teamName",a.id AS "managerAgentId",a.name AS "managerName",
(SELECT count(*) FROM tasks q WHERE q.organization_id=m.organization_id AND q.team_id=m.team_id AND q.mission_id=m.id) AS "totalTasks",
(SELECT count(*) FROM tasks q WHERE q.organization_id=m.organization_id AND q.team_id=m.team_id AND q.mission_id=m.id AND q.status='COMPLETED') AS "completedTasks",
(SELECT count(*) FROM tasks q WHERE q.organization_id=m.organization_id AND q.team_id=m.team_id AND q.mission_id=m.id AND q.status IN ('WAITING','BLOCKED')) AS "pendingTasks",
m.created_at AS "createdAt"
FROM missions m LEFT JOIN teams t ON t.organization_id=m.organization_id AND t.id=m.team_id
LEFT JOIN agents a ON a.organization_id=m.organization_id AND a.team_id=m.team_id AND a.id=m.manager_agent_id
WHERE m.organization_id='{ORG}' ORDER BY m.created_at DESC,m.id) x;'''))
actual = []
for page in range(max(1, (len(expected) + 19) // 20)):
    status, body = request(ROOT + '?page=' + str(page))
    assert status == 200
    data = body['data']
    assert data['page'] == page and data['size'] == 20 and data['total'] == len(expected) and data['team'] is None
    actual.extend(data['items'])
assert list(map(normalized, actual)) == list(map(normalized, expected))
assert all(set(item) == set(expected[0]) for item in actual)
assert 'PRIVATE_DIRECTORY_GOAL_DO_NOT_PROJECT' not in json.dumps(actual)
assert 'FOREIGN_PRIVATE' not in json.dumps(actual)
legacy = next(row for row in actual if row['teamId'] is None)
assert legacy['managerAgentId'] is None and legacy['managerName'] is None and legacy['totalTasks'] == 0
for state in ('CREATED', 'PLANNING', 'READY', 'RUNNING', 'WAITING_APPROVAL', 'REVIEW', 'COMPLETED', 'FAILED', 'CANCELLED'):
    status, body = request(ROOT + '?' + urllib.parse.urlencode({'status': state}))
    matches = [row for row in expected if row['status'] == state]
    assert status == 200 and body['data']['total'] == len(matches)
    assert list(map(normalized, body['data']['items'])) == list(map(normalized, matches[:20]))
for query in ('%_!', "' OR 1=1 --", '  directory mission 25  ', 'ZZZ absent'):
    status, body = request(ROOT + '?' + urllib.parse.urlencode({'query': query}))
    matches = [row for row in expected if query.strip().lower() in row['title'].lower()]
    assert status == 200 and body['data']['total'] == len(matches)
    assert list(map(normalized, body['data']['items'])) == list(map(normalized, matches[:20]))
status, body = request(ROOT + '?teamId=' + TEAM)
assert status == 200 and body['data']['team'] == {'id': TEAM, 'name': 'Directory Delivery', 'status': 'ACTIVE'}
assert body['data']['total'] == sum(row['teamId'] == TEAM for row in expected)
assert all(row['teamId'] == TEAM for row in body['data']['items'])
for suffix in ('?page=-1', '?page=100001', '?page=bad', '?status=BAD', '?teamId=bad', '?query=' + 'x' * 201):
    assert request(ROOT + suffix)[0] == 400, suffix
assert request(ROOT + '?teamId=19100000-0000-4000-8000-000000000001')[0] == 404
assert request(ROOT.replace(ORG, '19191919-1919-4919-8919-191919191919'))[0] == 403
status, body = request(ROOT + '?page=100000')
assert status == 200 and body['data']['items'] == [] and body['data']['total'] == len(expected)
assert before == sql(snapshot), 'Mission directory reads changed business state'
print('PASS: PostgreSQL Mission directory exact projection/order, bounded pagination, literal/status/Team filters, recorded task counts, safe manager scope and zero writes')
