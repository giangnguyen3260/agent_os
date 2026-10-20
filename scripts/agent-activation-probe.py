"""Disposable PostgreSQL proof that current activation review cannot mutate state."""
import concurrent.futures
import http.cookiejar
import json
import os
import subprocess
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '77777777-7777-4777-8777-777777777777'
AGENT = '74000000-0000-4000-8000-000000000001'
RESOURCE = f'/api/v1/organizations/{ORG}/agents/{AGENT}'
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def request(path, data=None, headers=None, method=None, client=opener):
    req = urllib.request.Request(BASE + path, data=data, headers=headers or {}, method=method)
    try:
        with client.open(req, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def csrf():
    status, result = request('/api/v1/auth/csrf')
    assert status == 200, f'CSRF failed: {status}'
    return {result['data']['headerName']: result['data']['token']}


def sql(query):
    return subprocess.check_output(
        ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
         'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query, text=True, timeout=15).strip()


headers = csrf()
headers['Content-Type'] = 'application/x-www-form-urlencoded'
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'activation-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), headers)
assert status == 200, f'Login failed: {status}'
headers = csrf()
headers['Content-Type'] = 'application/json'
status, result = request(RESOURCE + '/configuration')
assert status == 200
current = result['data']
fields = ['responsibilities', 'expectedInput', 'outputFormat', 'acceptanceCriteria',
          'model', 'skills', 'tools', 'limits']
body = {field: current['agent'].get(field) for field in fields}
body.update(expectedRevision=current['revision'], model='Requested-only model', concurrency=2,
            maxTokensPerRun=1000, monthlyBudget=50)
setup_headers = dict(headers, **{'Idempotency-Key': str(uuid.uuid4())})
status, result = request(RESOURCE + '/configuration', json.dumps(body).encode(), setup_headers, 'PUT')
assert status == 200
revision = result['data']['revision']
status, result = request(RESOURCE + '/activation-review')
assert status == 200
review = result['data']
assert review['revision'] == revision and review['activationAllowed'] is False
checks = {item['code']: item['status'] for item in review['checks']}
assert all(checks[code] == 'PASS' for code in ['DRAFT_STATUS', 'CONTRACT', 'MODEL_SELECTION',
                                            'STRUCTURED_LIMITS', 'TEAM_ACTIVE', 'TEAM_ENVELOPE'])
assert all(checks[code] == 'UNAVAILABLE' for code in ['MODEL_AUTHORIZATION', 'TOOL_AUTHORIZATION', 'EXECUTION_RUNTIME'])
query = f"""
SELECT row_to_json(a)::text FROM agents a WHERE id='{AGENT}';
SELECT row_to_json(c)::text FROM agent_draft_config c WHERE agent_id='{AGENT}';
SELECT count(*) FROM agent_configuration_versions WHERE agent_id='{AGENT}';
SELECT count(*) FROM agent_configuration_receipts WHERE agent_id='{AGENT}';
SELECT count(*) FROM team_create_receipts WHERE organization_id='{ORG}';
SELECT count(*) FROM agent_create_receipts WHERE organization_id='{ORG}';
SELECT count(*) FROM tasks WHERE organization_id='{ORG}';
"""
before = sql(query)
barrier = threading.Barrier(2)


def blocked(_):
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    barrier.wait(timeout=10)
    return request(RESOURCE + '/activation', json.dumps({'expectedRevision': revision}).encode(),
                   headers, 'POST', client)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    outcomes = list(pool.map(blocked, range(2)))
for status, result in outcomes:
    assert status == 409 and result['error']['code'] == 'ACTIVATION_BLOCKED'
    assert result['error']['details']['review']['activationAllowed'] is False
status, stale = request(RESOURCE + '/activation', json.dumps({'expectedRevision': revision - 1}).encode(), headers, 'POST')
assert status == 409 and stale['error']['code'] == 'CONFIGURATION_CONFLICT'
status, missing_csrf = request(RESOURCE + '/activation', json.dumps({'expectedRevision': revision}).encode(),
                               {'Content-Type': 'application/json'}, 'POST')
assert status == 403
assert sql(query) == before, 'Blocked/stale activation changed persistent state'
print('PASS: PostgreSQL activation review configured checks, unavailable integrations, concurrent blocked/stale denial and zero mutation')
