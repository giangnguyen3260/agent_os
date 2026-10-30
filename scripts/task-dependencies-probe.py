"""Real HTTP/CSRF/PostgreSQL dependency QC on isolated prefix570 fixtures.

Only actual commands create provenance. Observations cover committed values and
tuple identities, not forensic detection of rolled-back SQL attempts.
"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
import re
import signal
import subprocess
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
UUID_RE = r'[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}'
STATES = {'DRAFT', 'READY', 'RUNNING', 'WAITING', 'BLOCKED', 'IN_REVIEW', 'COMPLETED', 'FAILED', 'CANCELLED'}
CONTEXT = set('organizationId teamId missionId taskId checkedAt revision fingerprint teamName missionTitle taskTitle taskStatus dependencies canEdit blockers'.split())
RECEIPT = set('organizationId teamId missionId taskId eventId revision committedAt before after'.split())
PAGE = set('organizationId teamId missionId taskId page pageSize total query items'.split())


def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'


ORG, TEAM, MISSION = ident('57000000', 1), ident('57010000', 101), ident('57020000', 101)
MISSION_ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}'


def task(n):
    return ident('57030000', 1000 + n)


def root(n=1):
    return f'{MISSION_ROOT}/tasks/{task(n)}/draft-dependencies'


def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=20).strip()


def literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def rows(table, where='true'):
    assert re.fullmatch('[a-z_]+', table)
    return json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE {where};"))


def snapshot():
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','authentication_audit','authentication_attempts') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    # Object aggregation avoids PostgreSQL's variadic100-argument function cap.
    parts = [f"SELECT '{t}' AS name,md5(coalesce(string_agg(row_to_json(x)::text||'@'||x.xmin::text||':'||x.ctid::text,'|' ORDER BY row_to_json(x)::text,x.ctid),'')) AS digest FROM {t} x" for t in tables]
    return json.loads(sql('SELECT json_object_agg(name,digest) FROM (' + ' UNION ALL '.join(parts) + ') s;'))


def safe(value, limit, blank=False):
    return isinstance(value, str) and len(value) <= limit and (blank or bool(value.strip())) and not any(ord(c) < 32 or 127 <= ord(c) <= 159 or 0xd800 <= ord(c) <= 0xdfff or ord(c) in (0x2028, 0x2029) for c in value)


def instant(value):
    assert isinstance(value, str) and re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def envelope(value, status, path, media):
    assert media == 'application/json' and isinstance(value, dict), 'Unsafe API media/envelope; body withheld'
    if status == 405 and set(value) == {'timestamp', 'status', 'error', 'path'}:
        assert value['status'] == 405 and value['error'] == 'Method Not Allowed' and value['path'] == path
        assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?(?:Z|[+-]\d\d:\d\d)', value['timestamp'])
        dt.datetime.fromisoformat(value['timestamp'].replace('Z', '+00:00'))
        return
    assert set(value) == ({'error', 'meta'} if status >= 400 else {'data', 'meta'})
    assert set(value['meta']) == {'requestId'} and re.fullmatch(UUID_RE, value['meta']['requestId'])
    if status >= 400:
        error = value['error']
        assert set(error) == {'code', 'message', 'details'} and error['details'] == {}
        assert safe(error['code'], 100) and re.fullmatch('[A-Z_]+', error['code'])
        assert error['message'] in {'Request denied', 'Authentication required', 'Request could not be completed', 'Request cannot be completed', 'Saved configuration is unavailable', 'Invalid settings request', 'Invalid dependency request', 'Invalid request', 'Resource not found', 'Workspace not found', 'Saved dependency configuration is unavailable'}, 'Unexpected error message; body withheld'
        assert 'PRIVATE_' not in json.dumps(error) and task(5001) not in json.dumps(error)


def strict_json(raw):
    def pairs(items):
        result = {}
        for name, value in items:
            if name in result:
                raise AssertionError('Duplicate API JSON field; body withheld')
            result[name] = value
        return result

    def non_integer(_token):
        raise AssertionError('Non-integer numeric token in API payload; body withheld')

    return json.loads(raw.decode('utf-8'), object_pairs_hook=pairs,
                      parse_float=non_integer, parse_constant=non_integer)


def table_identity(table, where='true'):
    assert re.fullmatch('[a-z_]+', table)
    return sql(f"SELECT md5(coalesce(string_agg(row_to_json(x)::text||'@'||x.xmin::text||':'||x.ctid::text,'|' ORDER BY row_to_json(x)::text,x.ctid),'')) FROM {table} x WHERE {where};")


class Client:
    def __init__(self, kind='admin'):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({'username': f'task-dependencies-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']}).encode(), {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})[0] == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        self.headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}

    def request(self, path, body=None, headers=None, method=None):
        try:
            response = self.opener.open(urllib.request.Request(BASE + path, data=body, headers={'Accept': 'application/json', **(headers or {})}, method=method), timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            self.last_raw = raw  # Never print raw response bodies on failure.
            try:
                result = strict_json(raw)
            except (ValueError, UnicodeDecodeError):
                raise AssertionError('Non-JSON/UTF8 API response; body withheld') from None
            if response.status >= 400:
                assert b'PRIVATE_' not in raw and ident('57030000', 5001).encode() not in raw and ident('57040000', 5001).encode() not in raw, 'Private marker in rejection; body withheld'
            envelope(result, response.status, path, response.headers.get('Content-Type', '').split(';')[0].lower())
            return response.status, result

    def save(self, body, key=None, path=None):
        return self.request(path or root(), json.dumps(body).encode(), {**self.headers, 'Idempotency-Key': key or str(uuid.uuid4())})


def read(client, path):
    before = snapshot()
    status, body = client.request(path)
    assert status == 200, status
    assert snapshot() == before, 'GET changed committed business state'
    return body['data']


def reject(action, status, code):
    before = snapshot()
    result = action()
    assert result[0] == status and result[1]['error']['code'] == code, 'Unexpected rejection status/code'
    assert snapshot() == before, 'Rejected command/read changed committed business state'
    return result


def scope(value, n=1):
    assert [value[k] for k in ['organizationId', 'teamId', 'missionId', 'taskId']] == [ORG, TEAM, MISSION, task(n)]


def task_array(values):
    assert isinstance(values, list) and len(values) <= 20
    ids = []
    for value in values:
        assert set(value) == {'id', 'title', 'status'} and re.fullmatch(UUID_RE, value['id'])
        assert safe(value['title'], 255, True) and value['status'] in STATES
        ids.append(value['id'])
    assert ids == sorted(set(ids))


def context(client, n=1):
    value = read(client, root(n))
    assert set(value) == CONTEXT
    scope(value, n)
    instant(value['checkedAt'])
    assert type(value['revision']) is int and 0 <= value['revision'] <= 2147483647
    assert re.fullmatch('[0-9a-f]{64}', value['fingerprint'])
    assert safe(value['teamName'], 255) and safe(value['missionTitle'], 255) and safe(value['taskTitle'], 255, True)
    assert value['teamName'] == rows('teams', f"id='{TEAM}'")[0]['name']
    assert value['missionTitle'] == rows('missions', f"id='{MISSION}'")[0]['title']
    assert value['taskTitle'] == rows('tasks', f"id='{task(n)}'")[0]['title']
    assert value['revision'] == rows('tasks', f"id='{task(n)}'")[0]['dependency_revision']
    assert value['taskStatus'] in STATES and type(value['canEdit']) is bool
    assert value['canEdit'] == (value['blockers'] == [])
    task_array(value['dependencies'])
    expected = json.loads(sql(f"SELECT coalesce(json_agg(json_build_object('id',t.id,'title',t.title,'status',t.status) ORDER BY t.id::text),'[]') FROM task_dependencies d JOIN tasks t ON t.id=d.dependency_task_id WHERE d.organization_id='{ORG}' AND d.team_id='{TEAM}' AND d.mission_id='{MISSION}' AND d.task_id='{task(n)}';"))
    assert value['dependencies'] == expected
    return value


def candidate(client, n, target):
    value = read(client, root(n) + '/eligible-dependencies/' + task(target))
    assert set(value) == {'id', 'title', 'status', 'fingerprint'} and value['id'] == task(target)
    assert safe(value['title'], 255, True) and value['status'] in STATES and re.fullmatch('[0-9a-f]{64}', value['fingerprint'])
    persisted = rows('tasks', f"id='{task(target)}' AND organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}'")[0]
    assert value['title'] == persisted['title'] and value['status'] == persisted['status']
    return value


def command(client, n, targets):
    current = context(client, n)
    return {'expectedRevision': current['revision'], 'expectedFingerprint': current['fingerprint'], 'dependencies': [{'taskId': task(t), 'fingerprint': candidate(client, n, t)['fingerprint']} for t in targets]}


def receipt(value, n, revision, targets):
    assert set(value) == RECEIPT
    scope(value, n)
    assert type(value['revision']) is int and 1 <= value['revision'] <= 2147483647 and value['revision'] == revision and re.fullmatch(UUID_RE, value['eventId'])
    instant(value['committedAt'])
    task_array(value['before'])
    task_array(value['after'])
    assert [x['id'] for x in value['before']] != [x['id'] for x in value['after']]
    assert [x['id'] for x in value['after']] == sorted(task(t) for t in targets)
    assert rows('tasks', f"id='{task(n)}'")[0]['dependency_revision'] == revision
    persisted_edges = rows('task_dependencies', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}' AND task_id='{task(n)}'")
    assert sorted(x['dependency_task_id'] for x in persisted_edges) == sorted(task(t) for t in targets)
    events = rows('task_dependency_events', f"id='{value['eventId']}'")
    assert len(events) == 1
    event = events[0]
    assert event['revision'] == revision and json.loads(event['before_json']) == value['before'] and json.loads(event['after_json']) == value['after']
    assert dt.datetime.fromisoformat(event['created_at']) == instant(value['committedAt'])
    audits = rows('audit_events', f"task_id='{task(n)}' AND event_type='TASK_DEPENDENCIES_UPDATED'")
    assert len(audits) == revision
    assert any(json.loads(x['payload']) == {'eventId': value['eventId'], 'revision': revision} for x in audits)


def dag():
    vertices = {x['id'] for x in rows('tasks', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}'")}
    edges = rows('task_dependencies', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}'")
    outgoing = {v: set() for v in vertices}
    incoming = {v: 0 for v in vertices}
    for edge in edges:
        a, b = edge['task_id'], edge['dependency_task_id']
        assert a in vertices and b in vertices and a != b
        outgoing[a].add(b)
        incoming[b] += 1
    queue = [v for v in vertices if incoming[v] == 0]
    visited = 0
    while queue:
        v = queue.pop()
        visited += 1
        for target in outgoing[v]:
            incoming[target] -= 1
            if incoming[target] == 0:
                queue.append(target)
    assert visited == len(vertices), 'Committed cycle detected'


def main():
    signal.alarm(299)
    a, b, member, other = Client(), Client('admin2'), Client('member'), Client('other')
    initial = context(a)
    assert initial['canEdit'] and initial['revision'] == 0 and [x['id'] for x in initial['dependencies']] == [task(2)]
    all_items = []
    for page in range(2):
        value = read(a, root() + f'/eligible-dependencies?page={page}&query=')
        assert set(value) == PAGE
        scope(value)
        assert value['page'] == page and value['pageSize'] == 20 and value['query'] == ''
        assert len(value['items']) == min(20, max(value['total'] - page * 20, 0))
        all_items.extend(value['items'])
    assert len({x['id'] for x in all_items}) == len(all_items) == value['total']
    graph_nodes = {x['id']: x for x in rows('tasks', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}'")}
    reverse = {node: set() for node in graph_nodes}
    for edge in rows('task_dependencies', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}'"):
        reverse[edge['dependency_task_id']].add(edge['task_id'])
    excluded, queue = {task(1)}, [task(1)]
    while queue:
        for source in reverse[queue.pop()]:
            if source not in excluded:
                excluded.add(source)
                queue.append(source)
    assert {x['id'] for x in all_items} == set(graph_nodes) - excluded
    for item in all_items:
        assert set(item) == {'id', 'title', 'status', 'fingerprint'} and re.fullmatch('[0-9a-f]{64}', item['fingerprint'])
        assert item['title'] == graph_nodes[item['id']]['title'] and item['status'] == graph_nodes[item['id']]['status']
    assert {x['status'] for x in all_items} == STATES
    assert task(1) not in {x['id'] for x in all_items} and task(8) not in {x['id'] for x in all_items} and task(36) not in {x['id'] for x in all_items}
    assert [(x['title'], x['id']) for x in all_items] == sorted((x['title'], x['id']) for x in all_items)
    assert candidate(a, 1, 11)['title'] == ''
    key = str(uuid.uuid4())
    body = command(a, 1, [5, 2])
    old = rows('tasks', f"id='{task(1)}'")[0]
    protected = snapshot()
    other_tasks = table_identity('tasks', f"id<>'{task(1)}'")
    other_edges = table_identity('task_dependencies', f"task_id<>'{task(1)}'")
    event_state = table_identity('task_dependency_events')
    receipt_state = table_identity('task_dependency_receipts')
    audit_state = table_identity('audit_events')
    actor = rows('human_accounts', "email='task-dependencies-native-admin@example.test'")[0]['id']
    status, response = a.save(body, key)
    assert status == 201
    first = response['data']
    receipt(first, 1, 1, [2, 5])
    assert table_identity('tasks', f"id<>'{task(1)}'") == other_tasks
    assert table_identity('task_dependencies', f"task_id<>'{task(1)}'") == other_edges
    assert table_identity('task_dependency_events', f"id<>'{first['eventId']}'") == event_state
    assert table_identity('task_dependency_receipts', f"NOT (organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}' AND task_id='{task(1)}' AND actor_id='{actor}' AND idempotency_key='{key}')") == receipt_state
    matching_audits = rows('audit_events', f"task_id='{task(1)}' AND event_type='TASK_DEPENDENCIES_UPDATED'")
    assert len(matching_audits) == 1
    assert table_identity('audit_events', f"id<>'{matching_audits[0]['id']}'") == audit_state
    persisted_receipts = rows('task_dependency_receipts', f"organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}' AND task_id='{task(1)}' AND actor_id='{actor}' AND idempotency_key='{key}'")
    assert len(persisted_receipts) == 1 and persisted_receipts[0]['event_id'] == first['eventId'] and json.loads(persisted_receipts[0]['original_response']) == first
    after = rows('tasks', f"id='{task(1)}'")[0]
    assert {k: v for k, v in old.items() if k not in ('dependency_revision', 'updated_at')} == {k: v for k, v in after.items() if k not in ('dependency_revision', 'updated_at')}
    now = snapshot()
    assert all(now[t] == value for t, value in protected.items() if t not in {'tasks', 'task_dependencies', 'task_dependency_events', 'task_dependency_receipts', 'audit_events'})
    replay_body = {**body, 'dependencies': list(reversed(body['dependencies']))}
    before = snapshot()
    status, replay = a.save(replay_body, key)
    assert status == 200 and replay['data'] == first and snapshot() == before
    assert read(b, root() + '/receipts/' + key) == {'receipt': None}
    assert read(a, root() + '/receipts/' + key) == {'receipt': first}
    reject(lambda: a.save({**body, 'dependencies': []}, key), 409, 'IDEMPOTENCY_CONFLICT')
    reject(lambda: a.save(command(a, 1, [2, 5])), 409, 'TASK_DEPENDENCIES_NO_CHANGE')
    stale = command(a, 1, [2])
    sql(f"UPDATE tasks SET title='Off-path actual update',updated_at=clock_timestamp() WHERE id='{task(30)}';")
    reject(lambda: a.save(stale), 409, 'TASK_DEPENDENCIES_CONFLICT')
    old_target = candidate(a, 1, 5)
    sql(f"UPDATE tasks SET title='Renamed actual prerequisite',updated_at=clock_timestamp() WHERE id='{task(5)}';")
    fresh = context(a)
    mixed = {'expectedRevision': fresh['revision'], 'expectedFingerprint': fresh['fingerprint'], 'dependencies': [{'taskId': task(5), 'fingerprint': old_target['fingerprint']}]}
    reject(lambda: a.save(mixed), 409, 'TASK_DEPENDENCIES_CANDIDATE_CONFLICT')
    current = context(a)
    reject(lambda: a.save({'expectedRevision': current['revision'], 'expectedFingerprint': current['fingerprint'], 'dependencies': [{'taskId': task(8), 'fingerprint': '0' * 64}]}), 409, 'TASK_DEPENDENCIES_NOT_ELIGIBLE')
    status, response = a.save(command(a, 1, []))
    assert status == 201
    receipt(response['data'], 1, 2, [])
    # Historical recovery precedes mutable lifecycle and invalid-graph checks.
    sql(f"UPDATE tasks SET status='COMPLETED' WHERE id='{task(1)}'; UPDATE teams SET status='ARCHIVED' WHERE id='{TEAM}'; UPDATE missions SET task_intake_paused=true WHERE id='{MISSION}'; INSERT INTO task_dependencies(organization_id,team_id,mission_id,task_id,dependency_task_id) VALUES ('{ORG}','{TEAM}','{MISSION}','{task(4)}','{task(2)}');")
    reject(lambda: a.request(root()), 500, 'TASK_DEPENDENCIES_UNAVAILABLE')
    assert read(a, root() + '/receipts/' + key) == {'receipt': first}
    before = snapshot()
    status, replay = a.save(body, key)
    assert status == 200 and replay['data'] == first and snapshot() == before
    sql(f"DELETE FROM task_dependencies WHERE task_id='{task(4)}' AND dependency_task_id='{task(2)}'; UPDATE tasks SET status='DRAFT' WHERE id='{task(1)}'; UPDATE teams SET status='ACTIVE' WHERE id='{TEAM}'; UPDATE missions SET task_intake_paused=false WHERE id='{MISSION}';")
    for suffix in ['?page=0', '?unknown=x', '?x=1&x=2']:
        reject(lambda suffix=suffix: a.request(root() + suffix), 400, 'BAD_REQUEST')
    for suffix in ['?page=-1', '?page=01', '?page=1e0', '?page=100001', '?page=0&page=0', '?query=x&query=y', '?unexpected=1']:
        reject(lambda suffix=suffix: a.request(root() + '/eligible-dependencies' + suffix), 400, 'BAD_REQUEST')
    reject(lambda: a.request(root(), b'{}', method='GET'), 400, 'BAD_REQUEST')
    valid = command(a, 1, [11])
    for bad in [{**valid, 'expectedRevision': '2'}, {**valid, 'expectedRevision': 2.0}, {**valid, 'extra': True}, {**valid, 'dependencies': None}, {**valid, 'dependencies': valid['dependencies'] * 2}, {**valid, 'dependencies': [{'taskId': task(1), 'fingerprint': '0' * 64}]}]:
        reject(lambda bad=bad: a.save(bad), 400, 'BAD_REQUEST')
    for client in [member, other]:
        reject(lambda client=client: client.request(root() + '?unknown=1'), 403, 'FORBIDDEN')
        reject(lambda client=client: client.request(root() + '/receipts/' + key), 403, 'FORBIDDEN')
    reject(lambda: a.request(root().replace(TEAM, ident('57010000', 102))), 404, 'NOT_FOUND')
    reject(lambda: a.request(root() + '/eligible-dependencies/' + ident('57030000', 5001)), 404, 'NOT_FOUND')
    sql(f"UPDATE tasks SET title='Literal %_! prerequisite',updated_at=clock_timestamp() WHERE id='{task(25)}';")
    for query in ['%', '_', '!']:
        value = read(a, root() + '/eligible-dependencies?query=' + urllib.parse.quote(query, safe=''))
        assert value['total'] == 1 and [x['id'] for x in value['items']] == [task(25)]
    left, right = command(a, 9, [10]), command(b, 10, [9])
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda args: args[0].save(args[1], path=root(args[2])), [(a, left, 9), (b, right, 10)]))
    assert sorted(x[0] for x in results) == [201, 409]
    assert next(x for x in results if x[0] == 409)[1]['error']['code'] == 'TASK_DEPENDENCIES_CONFLICT'
    dag()
    before_aba = context(a)
    aba_time = rows('tasks', f"id='{task(1)}'")[0]['updated_at']
    edit = read(a, root().replace('/draft-dependencies', '/draft-settings'))
    owner = read(a, root().replace('/draft-dependencies', '/draft-owner'))
    status, _ = a.save(command(a, 1, [11]))
    assert status == 201
    status, _ = a.save(command(a, 1, []))
    assert status == 201
    sql(f"UPDATE tasks SET updated_at={literal(aba_time)} WHERE id='{task(1)}';")
    assert context(a)['revision'] == before_aba['revision'] + 2
    assert context(a)['fingerprint'] != before_aba['fingerprint']
    assert read(a, root().replace('/draft-dependencies', '/draft-settings'))['fingerprint'] != edit['fingerprint']
    assert read(a, root().replace('/draft-dependencies', '/draft-owner'))['fingerprint'] != owner['fingerprint']
    dag()
    stored = rows('task_dependency_events', f"id='{first['eventId']}'")[0]['before_json']
    sql(f"UPDATE task_dependency_events SET before_json='[]' WHERE id='{first['eventId']}';")
    reject(lambda: a.request(root() + '/receipts/' + key), 500, 'TASK_DEPENDENCIES_UNAVAILABLE')
    sql(f"UPDATE task_dependency_events SET before_json={literal(stored)} WHERE id='{first['eventId']}';")
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-dependencies-native-admin@example.test');")
    reject(lambda: a.request(root() + '/receipts/' + key), 403, 'FORBIDDEN')
    sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-dependencies-native-admin@example.test');")
    assert read(a, root() + '/receipts/' + key) == {'receipt': first}
    print('PASS: actual dependency persistence/replay, full scoped DAG/pages, off-path/candidate conflicts, historical recovery, strict wire/privacy, reciprocal race and cross-editor ABA fingerprints')


if __name__ == '__main__':
    main()
