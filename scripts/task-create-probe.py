"""Disposable PostgreSQL Task intake persistence, replay, concurrency and scope acceptance."""
import concurrent.futures
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
ORG = '22222222-2323-4232-8232-222222222222'
FOREIGN = '23232323-2323-4323-8323-232323232323'


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


TEAM, MISSION = ident('2210', 1), ident('2230', 1)
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks'


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        input=query, text=True, timeout=20).strip()


class Client:
    def __init__(self, email='task-create-admin@example.test'):
        self.client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': email, 'password': os.environ['E2E_PASSWORD']}).encode(),
            {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
        assert status == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        self.headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}

    def request(self, path, data=None, headers=None):
        try:
            response = self.client.open(urllib.request.Request(BASE + path, data=data, headers=headers or {}), timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            return response.status, json.loads(raw)

    def create(self, key, body, root=ROOT):
        return self.request(root, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key}))

    def context(self, root=ROOT):
        status, response = self.request(root + '/creation-context')
        assert status == 200, (root, status, response)
        return response['data']


def business_snapshot():
    return sql('\n'.join(f"SELECT row_to_json(x)::text FROM {table} x WHERE organization_id='{ORG}' ORDER BY 1;"
                         for table in ('teams', 'agents', 'missions', 'tasks', 'task_dependencies', 'task_create_receipts', 'audit_events')))


def error_status(client, expected, body, root=ROOT):
    before = business_snapshot()
    status, result = client.create(str(uuid.uuid4()), body, root)
    assert status == expected, (status, expected, result)
    assert before == business_snapshot(), 'Failed create mutated business rows'
    assert 'private-intake-description' not in json.dumps(result)
    assert 'private-intake-criteria' not in json.dumps(result)
    return result


def normalize(value):
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))


def main():
    admin = Client()
    context = admin.context()
    assert context['organizationId'] == ORG and context['teamId'] == TEAM and context['missionId'] == MISSION
    assert context['canCreate'] and not context['intakePaused'] and context['intakeVersion'] == 0
    for suffix, names in [('eligible-owners', 'name'), ('eligible-dependencies', 'title')]:
        seen = []
        for page in (0, 1):
            status, response = admin.request(f'{ROOT}/{suffix}?page={page}&query=')
            assert status == 200
            data = response['data']
            assert (data['page'], data['size'], data['total']) == (page, 20, 25)
            assert len(data['items']) == (20 if page == 0 else 5)
            seen.extend(data['items'])
        assert len({x['id'] for x in seen}) == 25
        assert all('PRIVATE' not in x[names] for x in seen)
        status, response = admin.request(f'{ROOT}/{suffix}?page=0&query=25')
        assert status == 200 and response['data']['total'] == 1
        for invalid in ('page=-1', 'page=100001', 'page=bad', 'query=' + 'x' * 101):
            assert admin.request(f'{ROOT}/{suffix}?{invalid}')[0] == 400
    body = {'title': '  PostgreSQL task intake  ', 'description': ' private-intake-description ',
            'acceptanceCriteria': ' private-intake-criteria ', 'priority': 'HIGH',
            'ownerAgentId': ident('2220', 1), 'dependencyIds': [ident('2240', 2), ident('2240', 1)],
            'expectedIntakeVersion': context['intakeVersion']}
    key = str(uuid.uuid4())
    status, response = admin.create(key, body)
    assert status == 201, (status, response)
    saved = response['data']
    task = str(uuid.UUID(saved['task']['id']))
    t = saved['task']
    assert t['organizationId'] == ORG and t['teamId'] == TEAM and t['missionId'] == MISSION
    assert (t['title'], t['description'], t['acceptanceCriteria'], t['priority']) == (
        body['title'].strip(), body['description'].strip(), body['acceptanceCriteria'].strip(), 'HIGH')
    assert t['status'] == 'DRAFT' and t['ownerAgentId'] == body['ownerAgentId'] and t['ownerName'] == 'Task Owner 01'
    assert sorted(t['dependencyIds']) == sorted(body['dependencyIds'])
    assert all(t[x] is None for x in ('pendingReason', 'waitingSince', 'resolver'))
    row = json.loads(sql(f"SELECT row_to_json(t)::text FROM tasks t WHERE id='{task}';"))
    assert row['description'] == t['description'] and row['acceptance_criteria'] == t['acceptanceCriteria']
    assert row['priority'] == t['priority'] and row['owner_agent_id'] == t['ownerAgentId']
    assert normalize(row['created_at']) == normalize(t['createdAt'])
    assert sql(f"SELECT count(*) FROM task_dependencies WHERE task_id='{task}';") == '2'
    assert sql(f"SELECT task_intake_version FROM missions WHERE id='{MISSION}';") == '1'
    assert sql(f"SELECT count(*) FROM task_create_receipts WHERE organization_id='{ORG}' AND idempotency_key='{key}';") == '1'
    assert sql(f"SELECT count(*) FROM audit_events WHERE task_id='{task}' AND event_type='TASK_CREATED';") == '1'
    payloads = sql(f"SELECT payload FROM audit_events WHERE task_id='{task}';")
    assert 'private-intake' not in payloads and 'PostgreSQL task' not in payloads
    replay_body = dict(body, title=body['title'].strip(), description=body['description'].strip(),
                       acceptanceCriteria=body['acceptanceCriteria'].strip(), dependencyIds=list(reversed(body['dependencyIds'])))
    before = business_snapshot()
    status, replay = admin.create(key, replay_body)
    assert status == 200 and replay['data'] == saved
    assert admin.create(key, dict(body, title='Different request'))[0] == 409
    assert before == business_snapshot()
    status, detail = admin.request(ROOT + '/' + task)
    assert status == 200 and detail['data']['task'] == t
    # Existing owner assignment cannot leak identity after the Agent moves out of this Team.
    try:
        sql(f"UPDATE agents SET team_id='{ident('2210', 3)}' WHERE id='{ident('2220', 1)}';")
        status, moved = admin.request(ROOT + '/' + task)
        assert status == 200 and moved['data']['task']['ownerAgentId'] is None and moved['data']['task']['ownerName'] is None
    finally:
        sql(f"UPDATE agents SET team_id='{TEAM}' WHERE id='{ident('2220', 1)}';")
    member = Client('task-create-member@example.test')
    assert member.request(ROOT + '/' + task)[0] == 200
    for suffix in ('creation-context', 'eligible-owners', 'eligible-dependencies'):
        assert member.request(ROOT + '/' + suffix)[0] == 403
    error_status(member, 403, body)
    # Replay still authorizes, but returns immutable original after mutable setup changes.
    sql(f"UPDATE missions SET task_intake_paused=true WHERE id='{MISSION}';")
    assert not admin.context()['canCreate']
    status, paused_replay = admin.create(key, body)
    assert status == 200 and paused_replay['data'] == saved
    current = dict(body, expectedIntakeVersion=1)
    error_status(admin, 409, current)
    sql(f"UPDATE missions SET task_intake_paused=false WHERE id='{MISSION}';")
    error_status(admin, 409, body)
    for change in ({'title': ' '}, {'description': ' '}, {'acceptanceCriteria': ' '},
                   {'priority': 'AUTO'}, {'dependencyIds': [ident('2240', 1)] * 2},
                   {'dependencyIds': [ident('2240', n) for n in range(1, 22)]},
                   {'expectedIntakeVersion': -1}, {'ownerAgentId': 'bad'}, {'privateExtra': 'private-intake-description'}):
        error_status(admin, 400, dict(current, **change))
    for owner in (ident('2220', 26), ident('2220', 27), ident('2320', 1), ident('2220', 999)):
        error_status(admin, 409, dict(current, ownerAgentId=owner))
    for dependency in (ident('2240', 26), ident('2340', 1), ident('2240', 999)):
        error_status(admin, 409, dict(current, dependencyIds=[dependency]))
    for root, expected in ((ROOT.replace(ORG, FOREIGN), 403),
                           (ROOT.replace(TEAM, ident('2210', 3)), 404),
                           (ROOT.replace(MISSION, ident('2230', 4)), 404),
                           (ROOT.replace(MISSION, ident('2230', 999)), 404),
                           (ROOT.replace(MISSION, ident('2230', 2)), 409),
                           (ROOT.replace(MISSION, ident('2230', 3)), 409),
                           (ROOT.replace(TEAM, ident('2210', 2)).replace(MISSION, ident('2230', 5)), 409)):
        error_status(admin, expected, current, root)
    assert admin.request(ROOT, json.dumps(current).encode(), admin.headers)[0] == 400
    assert admin.request(ROOT, b'{broken-json', dict(admin.headers, **{'Idempotency-Key': str(uuid.uuid4())}))[0] == 400
    assert admin.request(ROOT, json.dumps(current).encode(), {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})[0] == 403
    # Same-key across independent sessions is exactly one identity; two distinct keys at one revision conflict.
    clients = [Client(), Client()]
    race_key = str(uuid.uuid4())
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda c: c.create(race_key, current), clients))
    assert sorted(x[0] for x in responses) == [200, 201], responses
    assert responses[0][1]['data'] == responses[1][1]['data']
    fresh = dict(current, expectedIntakeVersion=admin.context()['intakeVersion'], ownerAgentId=None, dependencyIds=[])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda c: c.create(str(uuid.uuid4()), fresh), clients))
    assert sorted(x[0] for x in responses) == [201, 409], responses
    assert sql(f"SELECT count(*) FROM tasks WHERE mission_id='{MISSION}';") == '28'
    assert sql(f"SELECT task_intake_version FROM missions WHERE id='{MISSION}';") == '3'
    assert sql(f"SELECT count(*) FROM task_create_receipts WHERE organization_id='{ORG}';") == '3'
    assert sql(f"SELECT count(*) FROM audit_events WHERE organization_id='{ORG}' AND event_type='TASK_CREATED';") == '3'
    assert sql(f"SELECT count(*) FROM task_dependencies WHERE organization_id='{ORG}';") == '4'
    # Receipt replay rejects fresh membership loss and leaves state unchanged.
    membership = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-create-admin@example.test')"
    try:
        sql(f'DELETE FROM organization_memberships WHERE {membership};')
        error_status(admin, 403, body)
        assert admin.create(key, body)[0] == 403
    finally:
        sql(f"INSERT INTO organization_memberships(account_id,organization_id,role) SELECT id,'{ORG}','ADMIN' FROM human_accounts WHERE email='task-create-admin@example.test' ON CONFLICT(account_id,organization_id) DO UPDATE SET role='ADMIN';")
    print('PASS: PostgreSQL Task Intake fields/owner/dependencies, atomic receipt/event/version, concurrent replay/stale conflict, scoped candidates/denials and error rollback')


if __name__ == '__main__':
    main()
