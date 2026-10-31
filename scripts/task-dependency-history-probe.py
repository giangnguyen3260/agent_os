"""Actual HTTP/CSRF/PostgreSQL history QC; no synthetic provenance writes."""
import http.cookiejar
import json
import os
import signal
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '58000000-0000-4000-8000-000000000001'
TEAM = '58010000-0000-4000-8000-000000000101'
MISSION = '58020000-0000-4000-8000-000000000101'
TASK = '58030000-0000-4000-8000-000000001001'
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks/{TASK}/draft-dependencies'
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c', 'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=20).strip()


def snapshot():
    tables = sql("select tablename from pg_tables where schemaname='public' and tablename not in ('flyway_schema_history','authentication_audit','authentication_attempts') and tablename not like 'spring_session%' order by tablename;").splitlines()
    parts = [f"select '{t}' as name,md5(coalesce(string_agg(row_to_json(x)::text||'@'||xmin::text||':'||ctid::text,'|' order by row_to_json(x)::text,ctid),'')) as digest from {t} x" for t in tables]
    return json.loads(sql('select json_object_agg(name,digest order by name) from (' + ' union all '.join(parts) + ') x;'))


def pairs(items):
    result = {}
    for key, value in items:
        assert key not in result, 'Duplicate response key; body withheld'
        result[key] = value
    return result


class Client:
    def __init__(self, kind='admin'):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, csrf = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = csrf['data']
        form = urllib.parse.urlencode({'username': f'task-dependency-history-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']}).encode()
        assert self.request('/api/v1/auth/login', form, {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})[0] == 200
        self.csrf = self.request('/api/v1/auth/csrf')[1]['data']

    def request(self, path, body=None, headers=None):
        try:
            response = self.opener.open(urllib.request.Request(BASE + path, data=body, headers={'Accept': 'application/json', **(headers or {})}), timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            result = json.loads(response.read().decode('utf-8'), object_pairs_hook=pairs)
            assert set(result) == ({'data', 'meta'} if response.status < 400 else {'error', 'meta'})
            if response.status >= 400:
                assert set(result['error']) == {'code', 'message', 'details'}
                assert result['error']['details'] == {}
                assert 'PRIVATE' not in json.dumps(result['error'])
            return response.status, result


def history(client, page=0):
    before = snapshot()
    status, envelope = client.request(ROOT + '/history?page=' + str(page))
    assert status == 200, status
    value = envelope['data']
    assert set(value) == set('organizationId teamId missionId taskId teamName missionTitle taskTitle page pageSize total items'.split())
    assert [value[k] for k in ('organizationId', 'teamId', 'missionId', 'taskId')] == [ORG, TEAM, MISSION, TASK]
    assert type(value['total']) is int and value['total'] >= 0 and value['page'] == page and value['pageSize'] == 20
    assert len(value['items']) == min(20, max(value['total'] - page * 20, 0))
    events = json.loads(sql(f"select coalesce(json_agg(json_build_object('eventId',e.id,'revision',e.revision,'actorName',a.display_name,'before',e.before_json::json,'after',e.after_json::json) order by e.revision desc),'[]') from (select * from task_dependency_events where task_id='{TASK}' order by revision desc limit 20 offset {page*20}) e left join organization_memberships m on m.organization_id=e.organization_id and m.account_id=e.actor_id left join human_accounts a on a.id=m.account_id where e.task_id='{TASK}';"))
    assert len(value['items']) == len(events)
    for actual, expected in zip(value['items'], events):
        assert set(actual) == set('eventId revision committedAt actorName before after'.split())
        assert type(actual['revision']) is int and actual['revision'] > 0
        assert {k: actual[k] for k in expected} == expected
    assert snapshot() == before, 'History GET changed committed business state'
    return value


def main():
    signal.alarm(299)
    admin, second, member, other = Client(), Client('admin2'), Client('member'), Client('other')
    assert history(admin)['total'] == 0
    for i in range(21):
        current = admin.request(ROOT)[1]['data']
        target = f'58030000-0000-4000-8000-{1005 + i % 2:012d}'
        candidate = admin.request(ROOT + '/eligible-dependencies/' + target)[1]['data']
        body = {'expectedRevision': current['revision'], 'expectedFingerprint': current['fingerprint'], 'dependencies': [{'taskId': target, 'fingerprint': candidate['fingerprint']}]}
        status, _ = admin.request(ROOT, json.dumps(body).encode(), {admin.csrf['headerName']: admin.csrf['token'], 'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
        assert status == 201
    assert history(admin)['total'] == 21
    assert len(history(admin, 1)['items']) == 1
    assert history(admin, 100000)['total'] == 21
    for client in [member, other]:
        before = snapshot()
        status, result = client.request(ROOT + '/history?unknown=1')
        assert status == 403 and result['error']['code'] == 'FORBIDDEN'
        assert snapshot() == before
    for query in ['?page=-1', '?page=01', '?page=1e0', '?page=0&page=1', '?unknown=0']:
        before = snapshot()
        status, result = admin.request(ROOT + '/history' + query)
        assert status == 400 and result['error']['code'] == 'BAD_REQUEST'
        assert snapshot() == before
    sql(f"update teams set status='ARCHIVED' where id='{TEAM}'; update missions set task_intake_paused=true where id='{MISSION}'; update tasks set status='COMPLETED' where id='{TASK}';")
    assert history(admin)['total'] == 21
    sql(f"delete from organization_memberships where organization_id='{ORG}' and account_id=(select id from human_accounts where email='task-dependency-history-native-admin@example.test');")
    assert all(x['actorName'] is None for x in history(second)['items'])
    sql(f"update task_dependency_events set before_json='PRIVATE invalid' where task_id='{TASK}' and revision=1;")
    assert len(history(second)['items']) == 20
    before = snapshot()
    status, result = second.request(ROOT + '/history?page=1')
    assert status == 500 and result['error']['code'] == 'TASK_DEPENDENCY_HISTORY_UNAVAILABLE'
    assert snapshot() == before
    print('PASS: actual dependency history paging/snapshots, member privacy, lifecycle independence, strict query, selected/off-page corruption and no-write reads')


if __name__ == '__main__':
    main()
