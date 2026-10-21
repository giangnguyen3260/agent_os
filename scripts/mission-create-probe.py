"""Real PostgreSQL mission persistence, retry and authorization boundary."""
import concurrent.futures
import http.cookiejar
import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '16161616-1616-4616-8616-161616161616'
TEAM = '16100000-0000-4000-8000-000000000001'
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions'


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query, text=True, timeout=20).strip()


class Client:
    def __init__(self, email):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, response = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = response['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': email, 'password': os.environ['E2E_PASSWORD']}).encode(),
            {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
        assert status == 200
        status, response = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = response['data']
        self.headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}

    def request(self, path, data=None, headers=None):
        try:
            with self.client.open(urllib.request.Request(BASE + path, data=data, headers=headers or {}), timeout=25) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def create(self, key, body, root=ROOT):
        return self.request(root, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key}))


admin = Client('mission-admin@example.test')
body = {'title': 'PostgreSQL mission QC', 'goal': 'Persist this private objective',
        'requiredOutput': 'Verified result', 'managerAgentId': '16200000-0000-4000-8000-000000000001',
        'priority': 'HIGH', 'deadline': '2030-12-31', 'budgetLimit': 12.5,
        'approvalMode': 'HUMAN_REQUIRED', 'context': 'Private context must not enter audit'}
key = str(uuid.uuid4())
status, result = admin.create(key, body)
assert status == 201, (status, result)
saved = result['data']
mission = str(uuid.UUID(saved['id']))
assert saved['organizationId'] == ORG and saved['teamId'] == TEAM and saved['status'] == 'CREATED'
assert saved['goal'] == body['goal'] and saved['requiredOutput'] == body['requiredOutput']
assert saved['budgetLimit'] == 12.5 and saved['context'] == body['context']
status, replay = admin.create(key, dict(body, budgetLimit=12.50))
assert status == 200 and replay['data'] == saved
assert admin.create(key, dict(body, title='Changed request'))[0] == 409
status, detail = admin.request(ROOT + '/' + mission)
assert status == 200 and detail['data'] == saved
row = json.loads(sql(f"SELECT row_to_json(m) FROM missions m WHERE id='{mission}';"))
assert row['organization_id'] == ORG and row['team_id'] == TEAM and row['status'] == 'CREATED'
assert row['goal'] == body['goal'] and row['required_output'] == body['requiredOutput']
assert row['context'] == body['context'] and row['budget_limit'] == 12.5
assert row['created_by_user_id'] == sql("SELECT id FROM human_accounts WHERE email='mission-admin@example.test';")
payloads = sql(f"SELECT payload FROM audit_events WHERE mission_id='{mission}';")
assert payloads, 'Mission audit event missing'
assert body['goal'] not in payloads and body['context'] not in payloads and body['title'] not in payloads
assert sql(f"SELECT count(*) FROM tasks WHERE mission_id='{mission}';") == '0'
# Same key across independent sessions creates exactly one row.
clients = [Client('mission-admin@example.test') for _ in range(2)]
race_key = str(uuid.uuid4())
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    responses = list(pool.map(lambda client: client.create(race_key, body), clients))
assert sorted(status for status, _ in responses) == [200, 201], responses
assert responses[0][1]['data'] == responses[1][1]['data']
assert sql(f"SELECT count(*) FROM mission_create_receipts WHERE organization_id='{ORG}' AND idempotency_key='{race_key}';") == '1'
before = sql(f"SELECT count(*) FROM missions WHERE organization_id='{ORG}';")
for change in ({'title': ' '}, {'budgetLimit': -1}, {'budgetLimit': 1.001},
               {'budgetLimit': '12'}, {'approvalMode': 'AUTO'}, {'deadline': '2030-02-30'}):
    assert admin.create(str(uuid.uuid4()), dict(body, **change))[0] == 400, change
for manager in (24, 25, 26):
    bad = f'16200000-0000-4000-8000-{manager:012d}'
    assert admin.create(str(uuid.uuid4()), dict(body, managerAgentId=bad))[0] == 409
assert admin.create(str(uuid.uuid4()), body, ROOT.replace(TEAM, '16100000-0000-4000-8000-000000000002'))[0] == 409
assert admin.create(str(uuid.uuid4()), body, ROOT.replace(TEAM, '17100000-0000-4000-8000-000000000001'))[0] == 404
member = Client('mission-member@example.test')
assert member.request(ROOT + '/' + mission)[0] == 200
assert member.create(str(uuid.uuid4()), body)[0] == 403
assert admin.request(ROOT + '/eligible-managers?page=0')[1]['data']['total'] == 23
assert admin.request(ROOT + '/eligible-managers?page=1')[1]['data']['items'].__len__() == 3
assert admin.request(ROOT + '/eligible-managers?query=%25')[1]['data']['total'] == 0
assert before == sql(f"SELECT count(*) FROM missions WHERE organization_id='{ORG}';"), 'Rejected input changed missions'
# Fresh authorization must also protect a successful receipt replay; restore test fixture finally.
try:
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='mission-admin@example.test');")
    assert admin.create(key, body)[0] == 403
finally:
    sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='mission-admin@example.test');")
print('PASS: PostgreSQL Mission persistence/readback, no planning/tasks, canonical and concurrent replay, conflict rollback, manager scope, validation and fresh authorization')
