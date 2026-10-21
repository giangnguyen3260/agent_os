"""Native Team projection/count integrity and unchanged business-state acceptance."""
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '12121212-1212-4212-8212-121212121212'
ROOT = f'/api/v1/organizations/{ORG}/teams/directory'
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


status, body = request('/api/v1/auth/csrf')
assert status == 200
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'team-directory-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), {body['data']['headerName']: body['data']['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
assert status == 200
snapshot = f"""
SELECT row_to_json(t)::text FROM teams t WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(a)::text FROM agents a WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(t)::text FROM tasks t WHERE organization_id='{ORG}' ORDER BY id;
SELECT count(*) FROM team_create_receipts WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_configuration_versions WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_configuration_receipts WHERE organization_id='{ORG}';
"""
before = sql(snapshot)
expected = json.loads(sql(f"""SELECT coalesce(json_agg(x),'[]')::text FROM
(SELECT t.id,t.organization_id AS "organizationId",t.name,t.purpose,t.status,
t.manager_agent_id AS "managerAgentId",m.name AS "managerName",
(SELECT count(*) FROM agents a WHERE a.organization_id=t.organization_id AND a.team_id=t.id) AS "agentCount",
(SELECT count(*) FROM tasks q WHERE q.organization_id=t.organization_id AND q.team_id=t.id) AS "totalTasks",
(SELECT count(*) FROM tasks q WHERE q.organization_id=t.organization_id AND q.team_id=t.id AND q.status='RUNNING') AS "runningTasks",
(SELECT count(*) FROM tasks q WHERE q.organization_id=t.organization_id AND q.team_id=t.id AND q.status IN ('WAITING','BLOCKED')) AS "pendingTasks",
(SELECT count(*) FROM tasks q WHERE q.organization_id=t.organization_id AND q.team_id=t.id AND q.status='IN_REVIEW') AS "reviewTasks"
FROM teams t LEFT JOIN agents m ON m.id=t.manager_agent_id AND m.organization_id=t.organization_id AND m.team_id=t.id
WHERE t.organization_id='{ORG}' ORDER BY t.created_at DESC,t.id) x;"""))
actual = []
for page in range((len(expected) + 19) // 20):
    status, result = request(ROOT + '?page=' + str(page))
    assert status == 200
    data = result['data']
    assert data['page'] == page and data['size'] == 20 and data['total'] == len(expected)
    actual.extend(data['items'])
assert actual == expected, 'Team projection/counts differ from PostgreSQL'
assert actual[0]['agentCount'] == 3 and actual[0]['totalTasks'] == 6
assert actual[0]['runningTasks'] == 2 and actual[0]['pendingTasks'] == 2 and actual[0]['reviewTasks'] == 1
for query in ['%', '_', '\\', 'review', 'TEAM', "' OR 1=1 --"]:
    status, result = request(ROOT + '?' + urllib.parse.urlencode({'query': query}))
    matches = [row for row in expected if query.lower() in row['name'].lower() or query.lower() in (row['purpose'] or '').lower()]
    assert status == 200 and result['data']['total'] == len(matches)
    assert result['data']['items'] == matches[:20]
for state in ['DRAFT', 'ACTIVE', 'ARCHIVED']:
    status, result = request(ROOT + '?status=' + state)
    matches = [row for row in expected if row['status'] == state]
    assert status == 200 and result['data']['total'] == len(matches)
    assert result['data']['items'] == matches[:20]
for query in ['page=-1', 'page=100001', 'status=RUNNING', 'query=' + 'x' * 201]:
    assert request(ROOT + '?' + query)[0] == 400
assert request('/api/v1/organizations/13131313-1313-4313-8313-131313131313/teams/directory')[0] == 403
assert before == sql(snapshot), 'Team directory reads changed business state'
print('PASS: PostgreSQL Team directory projection, non-multiplied Agent/task counts, pagination, literal filters, tenant scope and zero mutation')
