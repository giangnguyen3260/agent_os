"""Native Agent Permissions inspection; authoritative stored requests, never fake grants."""
import datetime
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '50800000-0000-4000-8000-000000000001'
AGENT = '50820000-0000-4000-8000-000000001001'
ROOT = f'/api/v1/organizations/{ORG}/agents/{AGENT}'


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
                                  'psql -X -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                                 input=query, text=True, timeout=15).strip()


class Client:
    def __init__(self, kind=None):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if kind:
            code, result = self.get('/api/v1/auth/csrf')
            assert code == 200
            token = result['data']
            body = urllib.parse.urlencode({'username': f'permissions-native-{kind}@example.test',
                                          'password': os.environ['E2E_PASSWORD']}).encode()
            code, _ = self.request('/api/v1/auth/login', 'POST', body,
                                   {token['headerName']: token['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
            assert code == 200

    def request(self, path, method='GET', body=None, headers=None):
        try:
            with self.client.open(urllib.request.Request(BASE + path, data=body, method=method,
                                                        headers=headers or {}), timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def get(self, path):
        return self.request(path)

    def command(self, path, method, body, headers=None):
        code, result = self.get('/api/v1/auth/csrf')
        assert code == 200
        token = result['data']
        return self.request(path, method, json.dumps(body).encode(),
                            {token['headerName']: token['token'], 'Content-Type': 'application/json', **(headers or {})})


def snapshot():
    query = '\n'.join(
        f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY id),'[]')::text FROM {table} x WHERE "
        + (f"id='{ORG}';" if table == 'organizations' else f"organization_id='{ORG}';")
        for table in ['organizations', 'teams', 'agents', 'missions', 'tasks', 'audit_events', 'approval_grants'])
    query += f"""
SELECT coalesce(json_agg(row_to_json(c) ORDER BY c.agent_id),'[]')::text
 FROM agent_draft_config c JOIN agents a ON a.id=c.agent_id WHERE a.organization_id='{ORG}';
SELECT coalesce(json_agg(row_to_json(v) ORDER BY v.agent_id,v.revision),'[]')::text
 FROM agent_configuration_versions v WHERE organization_id='{ORG}';
SELECT coalesce(json_agg(row_to_json(v) ORDER BY v.agent_id,v.idempotency_key),'[]')::text
 FROM agent_configuration_receipts v WHERE organization_id='{ORG}';
"""
    return sql(query)


def inspect(client, path=ROOT):
    code, response = client.get(path + '/permissions')
    assert code == 200, code
    data = response['data']
    assert set(data) == {'organizationId', 'agentId', 'name', 'status', 'configurationRevision',
                         'checkedAt', 'teamState', 'team', 'configurationPresent', 'sourceStatus', 'requests'}
    assert data['organizationId'] == ORG and data['agentId'] == path.split('/')[-1]
    assert data['sourceStatus'] == 'UNAVAILABLE'
    observed = datetime.datetime.fromisoformat(data['checkedAt'].replace('Z', '+00:00'))
    assert observed.utcoffset() == datetime.timedelta(0)
    assert abs((datetime.datetime.now(datetime.timezone.utc) - observed).total_seconds()) < 60
    assert [r['kind'] for r in data['requests']] == ['MODEL', 'TOOLS', 'SKILLS']
    for request in data['requests']:
        assert set(request) == {'kind', 'configured', 'effective', 'grantedBy', 'scope'}
        assert request['effective'] is None and request['grantedBy'] is None and request['scope'] is None
    expected = json.loads(sql(f"""
SELECT json_build_object('model',c.model,'tools',c.tools,'skills',c.skills,'revision',a.configuration_revision,
 'present',c.agent_id IS NOT NULL,'teamId',t.id,'teamName',t.name,'teamStatus',t.status,
 'state',CASE WHEN a.team_id IS NULL THEN 'UNASSIGNED' WHEN t.id IS NULL THEN 'UNAVAILABLE' ELSE 'ASSIGNED' END)::text
FROM agents a LEFT JOIN agent_draft_config c ON c.agent_id=a.id
LEFT JOIN teams t ON t.id=a.team_id AND t.organization_id=a.organization_id
WHERE a.organization_id='{ORG}' AND a.id='{data['agentId']}';
"""))
    assert [r['configured'] for r in data['requests']] == [expected['model'], expected['tools'], expected['skills']]
    assert data['configurationRevision'] == expected['revision']
    assert data['configurationPresent'] is expected['present'] and data['teamState'] == expected['state']
    team = None if expected['teamId'] is None else {'id': expected['teamId'], 'name': expected['teamName'], 'status': expected['teamStatus']}
    assert data['team'] == team
    return data


before = snapshot()
admin, member = Client('admin'), Client('member')
assert Client().get(ROOT + '/permissions')[0] == 401
for client in [admin, member]:
    for index, status in [(1, 'DRAFT'), (2, 'DRAFT'), (3, 'ACTIVE'), (4, 'SUSPENDED'), (5, 'ARCHIVED')]:
        data = inspect(client, ROOT.replace(AGENT, f'50820000-0000-4000-8000-{1000 + index:012}'))
        assert data['status'] == status
        assert data['configurationPresent'] is (index != 2)
    assert client.get(ROOT.replace(AGENT, '50820000-0000-4000-8000-000000005001') + '/permissions')[0] == 404
    assert client.get(ROOT.replace(ORG, '50800000-0000-4000-8000-000000000005') + '/permissions')[0] == 403
    for suffix in ['?page=0', '?unknown=1', '?query=a&query=b']:
        assert client.get(ROOT + '/permissions' + suffix)[0] == 400
    assert client.get(ROOT.replace(AGENT, 'ABCDEF00-0000-4000-8000-000000000001') + '/permissions')[0] == 400
assert json.loads(sql(f"SELECT json_build_object('n',count(*))::text FROM approval_grants WHERE organization_id='{ORG}';"))['n'] == 1
# An unmapped method uses sendError, whose /error dispatch is deliberately denied
# by the existing transport policy. Do not add a writer or relax that policy.
status, rejected = admin.command(ROOT + '/permissions', 'PUT', {})
assert status == 403, ('unsupported ADMIN write', status, rejected.get('error', {}).get('code'))
status, rejected = member.command(ROOT + '/permissions', 'PUT', {})
assert status == 403, ('unsupported MEMBER write', status, rejected.get('error', {}).get('code'))
print('PASS: unsupported Permissions PUT is rejected403 for ADMIN and MEMBER under existing transport policy')
assert admin.command(ROOT + '/activation', 'POST', {'expectedRevision': 0})[0] == 409
assert before == snapshot(), 'Permissions read/unsupported write/blocked activation changed business rows'

# One existing authoritative Studio write, followed by another strictly read-only boundary.
code, response = admin.get(ROOT + '/configuration')
assert code == 200
config = response['data']
body = {key: config['agent'][key] for key in ['responsibilities', 'expectedInput', 'outputFormat',
                                           'acceptanceCriteria', 'model', 'skills', 'tools', 'limits']}
body.update({key: config[key] for key in ['concurrency', 'maxTokensPerRun', 'monthlyBudget']})
body.update({'expectedRevision': config['revision'], 'model': 'fresh-model-request',
             'tools': 'PUBLISH_PREVIEW=ALLOW; still opaque requested text'})
code, saved = admin.command(ROOT + '/configuration', 'PUT', body, {'Idempotency-Key': str(uuid.uuid4())})
assert code == 200 and saved['data']['revision'] == config['revision'] + 1
after_studio = snapshot()
fresh = inspect(member)
assert fresh['configurationRevision'] == config['revision'] + 1
assert fresh['requests'][0]['configured'] == 'fresh-model-request'
assert fresh['requests'][1]['configured'] == body['tools']
assert admin.command(ROOT + '/activation', 'POST', {'expectedRevision': fresh['configurationRevision']})[0] == 409
assert after_studio == snapshot(), 'Read after actual Studio write changed business state'

account = sql("SELECT id FROM human_accounts WHERE email='permissions-native-admin@example.test';")
predicate = f"account_id='{account}' AND organization_id='{ORG}'"
try:
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE {predicate};")
    inspect(admin)
    sql(f"DELETE FROM organization_memberships WHERE {predicate};")
    assert admin.get(ROOT + '/permissions')[0] == 403
finally:
    sql(f"INSERT INTO organization_memberships(account_id,organization_id,role) VALUES('{account}','{ORG}','ADMIN') ON CONFLICT(account_id,organization_id) DO UPDATE SET role='ADMIN';")
try:
    sql(f"UPDATE human_accounts SET enabled=false WHERE id='{account}';")
    assert admin.get(ROOT + '/permissions')[0] in [401, 403]
finally:
    sql(f"UPDATE human_accounts SET enabled=true WHERE id='{account}';")
fresh_admin = Client('admin')
try:
    sql(f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{account}';")
    assert fresh_admin.get(ROOT + '/permissions')[0] in [401, 403]
finally:
    sql(f"UPDATE human_accounts SET credential_version=credential_version-1 WHERE id='{account}';")
assert after_studio == snapshot(), 'Authority vectors changed business rows beyond restored test authority'
print('PASS: native PostgreSQL Agent Permissions exact opaque requests, missing/null/empty/lifecycle, scope/authority, real Studio revision refresh, no human-grant inheritance and no inspection writes')
