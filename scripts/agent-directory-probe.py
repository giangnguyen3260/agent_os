"""Native PostgreSQL directory projection/read-only checks on disposable CI data."""
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '88888888-8888-4888-8888-888888888888'
ROOT = f'/api/v1/organizations/{ORG}/agents/directory'
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
    'username': 'directory-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), {body['data']['headerName']: body['data']['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
assert status == 200
snapshot = f"""
SELECT row_to_json(a)::text FROM agents a WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(c)::text FROM agent_draft_config c JOIN agents a ON a.id=c.agent_id WHERE a.organization_id='{ORG}' ORDER BY c.agent_id;
SELECT count(*) FROM tasks WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_configuration_versions WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_configuration_receipts WHERE organization_id='{ORG}';
"""
before = sql(snapshot)
expected = json.loads(sql(f"""SELECT coalesce(json_agg(x),'[]')::text FROM
(SELECT a.id,a.organization_id AS "organizationId",a.name,a.role,a.status,a.team_id AS "teamId",
t.name AS "teamName",a.configuration_revision AS "configurationRevision",c.concurrency AS "concurrencyLimit"
FROM agents a LEFT JOIN teams t ON t.id=a.team_id AND t.organization_id=a.organization_id
LEFT JOIN agent_draft_config c ON c.agent_id=a.id WHERE a.organization_id='{ORG}'
ORDER BY a.created_at DESC,a.id) x;"""))
actual = []
for page in range((len(expected) + 19) // 20):
    status, result = request(ROOT + '?page=' + str(page))
    assert status == 200
    data = result['data']
    assert data['page'] == page and data['size'] == 20 and data['total'] == len(expected)
    actual.extend(data['items'])
assert actual == expected, 'API projection/order differs from persisted rows'
assert len({row['id'] for row in actual}) == len(actual)
for query in ['%', '_', '\\', 'review', 'DIRECTORY', "' OR 1=1 --"]:
    status, result = request(ROOT + '?' + urllib.parse.urlencode({'query': query}))
    matches = [row for row in expected if query.lower() in row['name'].lower() or query.lower() in row['role'].lower()]
    assert status == 200 and result['data']['total'] == len(matches)
    assert result['data']['items'] == matches[:20]
for state in ['DRAFT', 'ACTIVE', 'DEGRADED', 'SUSPENDED', 'ARCHIVED']:
    status, result = request(ROOT + '?status=' + state)
    matches = [row for row in expected if row['status'] == state]
    assert status == 200 and result['data']['total'] == len(matches)
    assert result['data']['items'] == matches[:20]
assert request(ROOT + '?teamId=91000000-0000-4000-8000-000000000001')[0] == 404
assert request('/api/v1/organizations/99999999-9999-4999-8999-999999999999/agents/directory')[0] == 403
assert before == sql(snapshot), 'Directory reads changed business data'
print('PASS: PostgreSQL directory projection, stable pagination, literal search, lifecycle filters, tenant scope and zero mutation')
