"""Trace Explorer disposable-fixture acceptance; stdlib HTTP/cookies/CSRF + psql.

Run from any directory with E2E_BASE_URL and E2E_PASSWORD. Requires the main
fixture and running compose services. Writes are isolated, explicit and restored.
Payload is never returned raw; SQL-only digests detect payload mutations.
"""
import datetime as dt
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080').rstrip('/')
REPO = Path(__file__).resolve().parents[1]


def ident(kind, number):
    return f'509{kind}0000-0000-4000-8000-{number:012d}'


ORG = ident(0, 1)
ROOT = f'/api/v1/organizations/{ORG}/trace-events'
EVENT = lambda n: ident(4, 1000 + n)


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres',
        'sh', '-c', 'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        cwd=REPO, input=query, text=True, timeout=30).strip()


class Client:
    def __init__(self):
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, path, data=None, headers=None):
        try:
            response = self.opener.open(urllib.request.Request(BASE + path,
                data=data, headers=headers or {}), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert not re.search(r'"(?:payload|actorId|actor_id|runId|correlationId|output|passwordHash)"\s*:|TRACE_PAYLOAD_MUST_NEVER_LEAK|never-return-this', raw), path
            return response.status, json.loads(raw) if raw else None

    def login(self, kind='admin'):
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'trace-native-{kind}@example.test',
            'password': os.environ['E2E_PASSWORD']}).encode(),
            {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
        assert status == 200, ('login', status)
        return self


def normal(row):
    return dict(row, createdAt=dt.datetime.fromisoformat(row['createdAt'].replace('Z', '+00:00')))


def envelope(data, detail=False):
    assert set(data) == ({'organizationId', 'generatedAt', 'runtimeStatus', 'event'} if detail else
        {'organizationId', 'generatedAt', 'runtimeStatus', 'items', 'page', 'size', 'total'})
    assert data['organizationId'] == ORG and data['runtimeStatus'] == 'UNAVAILABLE'
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', data['generatedAt'])
    assert abs((dt.datetime.now(dt.timezone.utc) - dt.datetime.fromisoformat(
        data['generatedAt'].replace('Z', '+00:00'))).total_seconds()) < 120
    for event in [data['event']] if detail else data['items']:
        assert set(event) == {'id', 'eventType', 'actorType', 'createdAt', 'team', 'mission', 'task'}
        assert set(event['mission']) == {'id', 'title'}
        for key, label in [('task', 'title'), ('team', 'name')]:
            assert event[key] is None or set(event[key]) == {'id', label}


def expected_rows():
    # Independent fixture oracle: explicit accepted IDs, not the service's visibility JOIN.
    return json.loads(sql(f'''SELECT json_agg(x ORDER BY x."createdAt" DESC,x.id)::text FROM (
      SELECT e.id,e.event_type AS "eventType",e.actor_type AS "actorType",e.created_at AS "createdAt",
      CASE WHEN tm.id IS NULL THEN NULL ELSE json_build_object('id',tm.id,'name',tm.name) END AS team,
      json_build_object('id',m.id,'title',m.title) AS mission,
      CASE WHEN t.id IS NULL THEN NULL ELSE json_build_object('id',t.id,'title',t.title) END AS task
      FROM audit_events e LEFT JOIN tasks t ON t.id=e.task_id
      JOIN missions m ON m.id=coalesce(e.mission_id,t.mission_id)
      LEFT JOIN teams tm ON tm.id=m.team_id
      WHERE e.organization_id='{ORG}' AND e.id IN ({','.join(repr(EVENT(n)) for n in [*range(1,33),38])})
    ) x;'''))


def snapshot():
    # Only SQL digests leave PostgreSQL; no raw payload/config/grant/receipt values.
    # Security/session tables are excluded because authentication intentionally mutates them.
    tables = ['organizations', 'teams', 'agents', 'missions', 'tasks',
              'task_dependencies', 'audit_events', 'approval_grants',
              'agent_draft_config', 'agent_configuration_versions']
    tables += sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND right(tablename,9)='_receipts' ORDER BY tablename;").splitlines()
    queries = []
    for table in tables:
        if table == 'audit_events':
            columns = sql("SELECT string_agg(quote_ident(column_name),',' ORDER BY ordinal_position) FROM information_schema.columns WHERE table_schema='public' AND table_name='audit_events' AND column_name<>'payload';")
            source = f'(SELECT {columns},md5(payload) AS payload_digest FROM audit_events)'
        else:
            source = table
        queries.append(f"SELECT md5(coalesce(string_agg(row_digest,'' ORDER BY row_digest),'')) FROM (SELECT md5(row_to_json(x)::text) AS row_digest FROM {source} x) digests;")
    return hashlib.sha256(sql('\n'.join(queries)).encode()).hexdigest()


def main():
    client = Client().login()
    before = snapshot()
    expected = expected_rows()
    assert len(expected) == 33, 'Frozen fixture expectation is 33; do not silently rebaseline'

    def listing(filters=None, page=0):
        filters = filters or {}
        status, body = client.request(ROOT + '?' + urllib.parse.urlencode(dict(filters, page=page)))
        assert status == 200, (filters, status)
        data = body['data']
        envelope(data)
        matches = [e for e in expected if
            filters.get('q', '').strip('\t\n\v\f\r\x1c\x1d\x1e\x1f \u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2008\u2009\u200a\u2028\u2029\u205f\u3000').lower() in e['eventType'].lower() and
            (not filters.get('missionId') or e['mission']['id'] == filters['missionId']) and
            (not filters.get('taskId') or (e['task'] or {}).get('id') == filters['taskId'])]
        assert (data['total'], data['size'], data['page']) == (len(matches), 20, page)
        assert list(map(normal, data['items'])) == list(map(normal, matches[page*20:(page+1)*20]))
        return data

    for page in (0, 1, 2, 100000):
        listing(page=page)
    for query in ('%', '_', '!', '\\', '<script>', 'literal%_!\\<script>',
                  '  tRaCeEvEnT 01  ', '\u2003TraceEvent\u2003', '', ' ', 'x'*100,
                  "' OR 1=1 --", '\U0001f680'*100, '\u00a0TraceEvent\u00a0'):
        listing({'q': query})
    for mission in (ident(2, 101), ident(2, 102), ident(2, 103), ident(2, 501), ident(2, 999)):
        listing({'missionId': mission})
        listing({'missionId': mission, 'taskId': ident(3, 101)})
    for task in (ident(3, 101), ident(3, 102), ident(3, 501), ident(3, 999)):
        listing({'taskId': task})
    for event in expected:
        status, body = client.request(ROOT + '/' + event['id'])
        assert status == 200
        envelope(body['data'], True)
        assert normal(body['data']['event']) == normal(event)
    assert next(e for e in expected if e['id'] == EVENT(31))['mission']['id'] == ident(2, 101)
    null = next(e for e in expected if e['id'] == EVENT(32))
    assert null['team'] is None and null['task'] is None
    for number in range(33, 38):
        assert client.request(ROOT + '/' + EVENT(number))[0] == 404
    for event in (ident(4, 2001), ident(4, 999999)):
        assert client.request(ROOT + '/' + event)[0] == 404
    malformed = ['size=20', 'sort=id', 'unknown=x', 'q=a&q=b', 'page=0&page=1',
        'missionId='+ident(2,101)+'&missionId='+ident(2,101), 'taskId=&taskId=',
        'page=', 'page=00', 'page=01', 'page=+1', 'page=-1', 'page=100001', 'page=1.0', 'page=oops',
        'missionId=', 'taskId=', 'missionId=bad', 'taskId=bad',
        'missionId=50920000-0000-4000-8000-00000000010A',
        'missionId=50920000-0000-6000-8000-000000000101',
        'taskId=50930000-0000-4000-7000-000000000101',
        'q='+urllib.parse.quote('x'*101), 'q='+urllib.parse.quote('\U0001f680'*101)]
    for query in malformed:
        assert client.request(ROOT + '?' + query)[0] == 400, query
    for suffix in ('?q=', '?page=0', '?size=20', '?q=a&q=b'):
        assert client.request(ROOT + '/' + EVENT(1) + suffix)[0] == 400
    for event in ('bad', '50940000-0000-4000-8000-00000000100A', '50940000-0000-7000-8000-000000001001'):
        assert client.request(ROOT + '/' + event)[0] == 400
    foreign = ROOT.replace(ORG, ident(0, 5))
    for path in (foreign, foreign + '/' + EVENT(1)):
        assert client.request(path)[0] == 403
    assert Client().request(ROOT)[0] == 401
    member = Client().login('member')
    assert member.request(ROOT)[0] == 200
    assert member.request(ROOT + '/' + EVENT(1))[0] == 200
    # Legacy payload-bearing preview routes must terminate before any service SQL.
    for authorized in (client, member):
        for suffix in ('/audit-events', '/missions/' + ident(2, 101) + '/trace',
                       '/missions/' + ident(2, 501) + '/trace',
                       '/missions/' + ident(2, 999999) + '/trace'):
            legacy = f'/api/v1/organizations/{ORG}' + suffix
            assert authorized.request(legacy)[0] == 404, legacy
            assert authorized.request(legacy.replace(ORG, ident(0, 5)))[0] == 403
    assert snapshot() == before, 'Read acceptance changed business state'

    # Real persisted insertion is the only audit write; cleanup even on failed assertions.
    fresh = EVENT(99)
    try:
        sql(f"INSERT INTO audit_events(id,organization_id,mission_id,task_id,actor_type,event_type,payload,created_at) VALUES ('{fresh}','{ORG}','{ident(2,101)}','{ident(3,101)}','HUMAN','QC Native Refresh','{{}}',now());")
        status, body = client.request(ROOT + '?page=0')  # native HTTP Refresh
        assert status == 200
        envelope(body['data'])
        assert body['data']['total'] == 34 and body['data']['items'][0]['id'] == fresh
        assert client.request(ROOT + '/' + fresh)[0] == 200
    finally:
        sql(f"DELETE FROM audit_events WHERE id='{fresh}' AND organization_id='{ORG}';")
    assert snapshot() == before, 'Isolated insertion did not restore business snapshot'

    # Each revoked session is exercised on both endpoints, with exact SQL restoration.
    email = 'trace-native-other@example.test'
    original = json.loads(sql(f"SELECT row_to_json(x) FROM (SELECT id,enabled,credential_version FROM human_accounts WHERE email='{email}') x;"))
    role = sql(f"SELECT role FROM organization_memberships WHERE account_id='{original['id']}' AND organization_id='{ORG}';")
    assert role in ('ADMIN', 'MEMBER')
    for mode in ('membership', 'disabled', 'credentials'):
        other = Client().login('other')
        try:
            if mode == 'membership':
                sql(f"DELETE FROM organization_memberships WHERE account_id='{original['id']}' AND organization_id='{ORG}';")
            elif mode == 'disabled':
                sql(f"UPDATE human_accounts SET enabled=false WHERE id='{original['id']}';")
            else:
                sql(f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{original['id']}';")
            for path in (ROOT, ROOT + '/' + EVENT(1)):
                assert other.request(path)[0] in (401, 403), mode
        finally:
            sql(f"UPDATE human_accounts SET enabled={'true' if original['enabled'] else 'false'},credential_version={int(original['credential_version'])} WHERE id='{original['id']}'; INSERT INTO organization_memberships(account_id,organization_id,role) VALUES ('{original['id']}','{ORG}','{role}') ON CONFLICT (account_id,organization_id) DO UPDATE SET role=EXCLUDED.role;")
        restored = Client().login('other')
        assert restored.request(ROOT)[0] == 200
        assert restored.request(ROOT + '/' + EVENT(1))[0] == 200
    assert snapshot() == before
    print('PASS: 33 SQL projections, exact lists/details, scope/query boundaries, no payload, no business writes; isolated real Refresh and restored authority loss')


if __name__ == '__main__':
    main()
