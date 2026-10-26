"""Actual HTTP/PostgreSQL Organization Map read, scope and no-write acceptance."""
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '50700000-0000-4000-8000-000000000001'
TEAM = '50710000-0000-4000-8000-000000000101'
FOREIGN = '50700000-0000-4000-8000-000000000005'
ROOT = f'/api/v1/organizations/{ORG}/organization-map'


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
                                  'psql -X -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
                                 input=query, text=True, timeout=15).strip()


class Client:
    def __init__(self, kind):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        code, result = self.get('/api/v1/auth/csrf')
        assert code == 200
        token = result['data']
        body = urllib.parse.urlencode({'username': f'map-native-{kind}@example.test',
                                      'password': os.environ['E2E_PASSWORD']}).encode()
        code, _ = self.request('/api/v1/auth/login', body,
                               {token['headerName']: token['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
        assert code == 200

    def request(self, path, body=None, headers=None):
        try:
            with self.client.open(urllib.request.Request(BASE + path, data=body, headers=headers or {}), timeout=20) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def get(self, path):
        return self.request(path)


snapshot_query = '\n'.join(
    f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY id),'[]')::text FROM {table} x WHERE "
    + (f"id='{ORG}';" if table == 'organizations' else f"organization_id='{ORG}';")
    for table in ['organizations', 'teams', 'agents', 'missions', 'tasks', 'audit_events'])
before = sql(snapshot_query)
admin, member = Client('admin'), Client('member')
expected_teams = json.loads(sql(f"SELECT json_agg(id ORDER BY lower(name),id)::text FROM teams WHERE organization_id='{ORG}';"))
for client in [admin, member]:
    rows = []
    for page in [0, 1]:
        code, response = client.get(ROOT + f'?page={page}')
        assert code == 200
        data = response['data']
        assert data['organizationId'] == ORG and data['teamTotal'] == 25
        assert data['agentTotal'] == 29 and data['unassignedTotal'] == 3
        assert data['teams']['total'] == 25 and data['teams']['page'] == page and data['teams']['size'] == 20
        rows.extend(data['teams']['items'])
    assert [row['id'] for row in rows] == expected_teams
    assert rows[0]['agentCount'] == 25 and rows[0]['managerName'] == 'Map Agent 001'
    assert rows[1]['managerAgentId'] is None and rows[1]['managerName'] is None
    assert rows[3]['purpose'] is None and rows[3]['intake'] is None
    agents = []
    for page in [0, 1]:
        code, response = client.get(ROOT + '/agents?' + urllib.parse.urlencode({'teamId': TEAM, 'page': page}))
        assert code == 200
        data = response['data']
        assert data['teamId'] == TEAM and data['team']['id'] == TEAM and data['total'] == 25
        agents.extend(data['items'])
    assert len(agents) == len({a['id'] for a in agents}) == 25
    assert agents[1]['reportsToName'] == 'Map Agent 001'
    for index in [2, 3, 4]:
        assert agents[index]['reportsToAgentId'] is None and agents[index]['reportsToName'] is None
        assert agents[index]['reportingUnavailable'] is True
    assert agents[5]['reportsToName'] == 'Map Agent 007' and agents[6]['reportsToName'] == 'Map Agent 006'
    assert '000000005001' not in json.dumps(agents)
    code, response = client.get(ROOT + '/agents')
    assert code == 200 and response['data']['total'] == 3
    assert response['data']['team'] is None and response['data']['teamId'] is None
    for query in ['%', '_', "' OR 1=1 --"]:
        assert client.get(ROOT + '?' + urllib.parse.urlencode({'query': query}))[1]['data']['teams']['total'] == 0
        assert client.get(ROOT + '/agents?' + urllib.parse.urlencode({'query': query, 'teamId': TEAM}))[1]['data']['total'] == 0
    assert client.get(ROOT + '?status=ACTIVE')[1]['data']['teams']['total'] == 1
    for suffix in ['?page=-1', '?page=100001', '?page=1.0', '?page=0&page=1', '?unknown=1', '?status=BUSY', '?query=' + 'x' * 201]:
        assert client.get(ROOT + suffix)[0] == 400, suffix
    for suffix in ['?page=0&page=1', '?unknown=1', '?teamId=bad', '?teamId=' + TEAM + '&teamId=' + TEAM]:
        assert client.get(ROOT + '/agents' + suffix)[0] == 400, suffix
    assert client.get(ROOT.replace(ORG, FOREIGN))[0] == 403
    assert client.get(ROOT + '/agents?teamId=50710000-0000-4000-8000-000000000501')[0] == 404

account = sql("SELECT id FROM human_accounts WHERE email='map-native-admin@example.test';")
try:
    sql(f"DELETE FROM organization_memberships WHERE account_id='{account}' AND organization_id='{ORG}';")
    assert admin.get(ROOT)[0] == 403
finally:
    sql(f"INSERT INTO organization_memberships(account_id,organization_id,role) VALUES('{account}','{ORG}','ADMIN');")
try:
    sql(f"UPDATE human_accounts SET enabled=false WHERE id='{account}';")
    assert admin.get(ROOT)[0] in [401, 403]
finally:
    sql(f"UPDATE human_accounts SET enabled=true WHERE id='{account}';")
assert before == sql(snapshot_query), 'Organization Map GET changed persisted business state'
print('PASS: actual PostgreSQL Organization Map pages/counts, ADMIN/MEMBER scope, reporting redaction/cycles, live denial and no business writes')
