"""Disposable real HTTP/CSRF/PostgreSQL independent QC; apply main's fixture first.
No mocked DTOs, application imports, fixture edits, or automatic fixture resets.
Run from repository root with E2E_PASSWORD and E2E_BASE_URL set.
"""
import concurrent.futures
import datetime as dt
import http.cookiejar
import json
import os
import re
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
VALUES = {'title', 'description', 'acceptanceCriteria', 'priority'}
RECEIPT = {'organizationId', 'teamId', 'missionId', 'taskId', 'eventId',
           'revision', 'committedAt', 'before', 'after'}
CONTEXT = {'organizationId', 'teamId', 'missionId', 'taskId', 'checkedAt', 'revision',
           'fingerprint', 'teamName', 'missionTitle', 'taskStatus', 'values', 'canEdit', 'blockers'}


def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'


ORG, TEAM, MISSION, TASK = (ident(p, n) for p, n in (
    ('51000000', 1), ('51010000', 101), ('51020000', 101), ('51030000', 1001)))
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks/{TASK}/draft-settings'


def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=20).strip()


def rows(table, where='true'):
    assert re.fullmatch('[a-z_]+', table)
    return json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE {where};"))


def task():
    return rows('tasks', f"id='{TASK}'")[0]


def snapshot():
    # Compare every persisted business table, including all other organizations,
    # history, runtime/grants and receipts. Authentication tables alone are excluded.
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','human_accounts','organization_memberships') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    entries = [f"'{table}',(SELECT md5(coalesce(string_agg(row_to_json(x)::text,'|' ORDER BY row_to_json(x)::text),'')) FROM {table} x)" for table in tables]
    return json.loads(sql('SELECT json_build_object(' + ','.join(entries) + ');'))


def protected():
    tables = ('teams', 'agents', 'missions', 'task_dependencies')
    return {t: rows(t) for t in tables}


class Client:
    def __init__(self, kind='admin'):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'}
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'task-edit-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
        }).encode(), headers)[0] == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        self.headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/json'}

    def request(self, path, body=None, headers=None):
        try:
            response = self.opener.open(urllib.request.Request(BASE + path, data=body, headers=headers or {}), timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            try:
                result = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                result = {'nonJson': True}  # HTML errors must not leak bodies in assertion output.
            return response.status, result

    def context(self, path=ROOT):
        status, body = self.request(path)
        assert status == 200, status
        context = body['data']
        assert set(context) == CONTEXT and set(context['values']) == VALUES
        assert re.fullmatch('[0-9a-f]{64}', context['fingerprint'])
        instant(context['checkedAt'])
        assert type(context['revision']) is int and context['revision'] >= 0
        assert context['canEdit'] == (context['blockers'] == [])
        assert len(context['blockers']) == len(set(context['blockers']))
        return context

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))


def instant(value):
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def command(context, **changes):
    body = dict(context['values'], expectedRevision=context['revision'], expectedFingerprint=context['fingerprint'])
    body.update(changes)
    return body


def desired(context, title=None):
    return command(context, title=title or 'Native private ' + str(uuid.uuid4()),
                   description='Native input\n<script>literal</script>', acceptanceCriteria='Native acceptance', priority='URGENT')


def reject(action, status, code=None):
    before = snapshot()
    result = action()
    assert result[0] == status, (result[0], status)
    if code:
        assert result[1]['error']['code'] == code, result[1].get('error', {}).get('code')
    assert snapshot() == before, 'Read/rejection/replay mutated business data'
    return result


def decoded(value):
    return json.loads(value) if isinstance(value, str) else value


def assert_commit(before, context, body, key, result, path=ROOT):
    assert result[0] == 201, result[0]
    receipt = result[1]['data']
    assert set(receipt) == RECEIPT
    assert set(receipt['before']) == VALUES and set(receipt['after']) == VALUES
    for name, value in zip(('organizationId', 'teamId', 'missionId', 'taskId'), (ORG, TEAM, MISSION, TASK)):
        assert receipt[name] == value
    assert str(uuid.UUID(receipt['eventId'])) == receipt['eventId']
    assert receipt['before'] == context['values']
    assert receipt['after'] == {k: body[k].strip() for k in VALUES}
    assert receipt['revision'] == context['revision'] + 1
    instant(receipt['committedAt'])
    after = task()
    mutable = {'title', 'description', 'acceptance_criteria', 'priority', 'updated_at', 'task_edit_revision'}
    assert {k: v for k, v in before.items() if k not in mutable} == {k: v for k, v in after.items() if k not in mutable}
    assert [after[k] for k in ('title', 'description', 'acceptance_criteria', 'priority')] == [receipt['after'][k] for k in ('title', 'description', 'acceptanceCriteria', 'priority')]
    assert after['task_edit_revision'] == receipt['revision']
    events = rows('task_edit_events', f"id='{receipt['eventId']}'")
    assert len(events) == 1
    event = events[0]
    assert event['task_id'] == TASK and event['revision'] == receipt['revision']
    assert decoded(event['before_json']) == receipt['before'] and decoded(event['after_json']) == receipt['after']
    assert dt.datetime.fromisoformat(event['created_at']) == instant(receipt['committedAt'])
    stored = rows('task_edit_receipts', f"idempotency_key='{key}'")
    assert len(stored) == 1 and decoded(stored[0]['original_response']) == receipt
    assert stored[0]['event_id'] == event['id'] and re.fullmatch('[0-9a-f]{64}', stored[0]['body_sha256'])
    audits = rows('audit_events', f"task_id='{TASK}' AND event_type='TASK_DRAFT_UPDATED'")
    matching = [x for x in audits if decoded(x['payload']).get('eventId') == event['id']]
    assert len(matching) == 1
    audit = matching[0]
    assert decoded(audit['payload']) == {'eventId': event['id'], 'revision': receipt['revision']}
    assert audit['organization_id'] == ORG and audit['mission_id'] == MISSION and audit['task_id'] == TASK
    assert audit['actor_id'] == event['actor_id'] == stored[0]['actor_id']
    return receipt, audit['id']


def race(clients, attempts):
    barrier = threading.Barrier(2)
    def run(pair):
        client, (body, key) = pair
        barrier.wait(timeout=5)
        return client.save(body, key)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        return list(pool.map(run, zip(clients, attempts)))


def lock_wait(client, mutation):
    context = client.context()
    proc = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        proc.stdin.write(f"BEGIN; SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE; SELECT pg_backend_pid();\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == ORG
        pid = int(proc.stdout.readline().strip())
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.save, desired(context))
            try:
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    if sql(f"SELECT count(*) FROM pg_stat_activity WHERE {pid}=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock';") != '0':
                        break
                    time.sleep(.05)
                else:
                    raise AssertionError('No observed HTTP writer blocked by actual organization row lock')
            finally:
                proc.stdin.write(mutation + ' COMMIT;\n'); proc.stdin.flush()
            assert future.result(timeout=27)[0] in (401, 403)
    finally:
        if proc.poll() is None:
            proc.stdin.write('ROLLBACK;\n\\q\n'); proc.stdin.flush()
        proc.communicate(timeout=10)


def main():
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('Probe exceeded 299 seconds')))
    signal.alarm(299)
    a, b, other, member = Client(), Client(), Client('admin2'), Client('member')
    stable = protected()
    initial = snapshot()
    unedited_tasks = rows('tasks', f"id<>'{TASK}'")
    journal_before = {t: rows(t) for t in ('task_edit_events', 'task_edit_receipts', 'audit_events')}
    legacy_path = ROOT.replace(TASK, ident('51030000', 1002))
    legacy = a.context(legacy_path)
    assert legacy['values'] == {'title': 'Task edit Draft 2', 'description': None, 'acceptanceCriteria': None, 'priority': None}
    c = a.context()
    assert c['taskStatus'] == 'DRAFT' and c['revision'] == 0 and c['canEdit']
    baseline = snapshot()
    assert a.request(ROOT + '/receipts/' + str(uuid.uuid4()))[1]['data'] == {'receipt': None}
    assert snapshot() == baseline
    reject(lambda: a.save(command(c)), 409, 'TASK_EDIT_NO_CHANGE')
    body, key, before = desired(c), str(uuid.uuid4()), task()
    receipt, audit_id = assert_commit(before, c, body, key, a.save(body, key))
    assert protected() == stable
    after_snapshot = snapshot()
    canonical_replay = a.save(dict(body, title='  ' + body['title'] + '  '), key)
    assert canonical_replay[0] == 200 and canonical_replay[1]['data'] == receipt
    original_key = key
    # The replay must be exact in data, irrespective of common envelope metadata.
    assert a.save(body, key)[0] == 200 and a.save(body, key)[1]['data'] == receipt
    assert snapshot() == after_snapshot
    reject(lambda: a.save(dict(body, title='Different canonical request'), key), 409, 'IDEMPOTENCY_CONFLICT')
    assert other.request(ROOT + '/receipts/' + key)[1]['data'] == {'receipt': None}
    assert a.request(ROOT + '/receipts/' + key)[1]['data'] == {'receipt': receipt}
    # Safe Trace projects structured references, never receipt/form or actor data.
    trace = a.request(f'/api/v1/organizations/{ORG}/trace-events/{audit_id}')
    assert trace[0] == 200
    event = trace[1]['data']['event']
    assert set(event) == {'id', 'eventType', 'actorType', 'createdAt', 'team', 'mission', 'task'}
    assert event['task']['id'] == TASK and event['mission']['id'] == MISSION
    assert not any(k in json.dumps(trace[1]) for k in ('Native input', 'Native acceptance', 'body_sha256', 'actorId', 'before_json'))
    assert snapshot() == after_snapshot

    # Historical replay precedes state eligibility, without rewriting later data.
    original = task()['status']
    try:
        sql(f"UPDATE tasks SET status='READY' WHERE id='{TASK}';")
        historical = snapshot()
        assert a.save(body, key)[0] == 200 and a.save(body, key)[1]['data'] == receipt
        assert a.request(ROOT + '/receipts/' + key)[1]['data'] == {'receipt': receipt}
        assert snapshot() == historical
    finally:
        sql(f"UPDATE tasks SET status='{original}' WHERE id='{TASK}';")

    # Strict JSON and wire: CSRF, exact fields/types, duplicated keys, byte bound,
    # route UUIDs, keys and query strings. Each vector is a business no-write check.
    fresh = desired(a.context())
    for changes in ({'title': ''}, {'title': 'x'*256}, {'title': '\x7f'}, {'title': '\tbad'},
                    {'title': '\ud800'}, {'title': False}, {'description': None}, {'description': 'x'*8001},
                    {'description': '\x01'}, {'acceptanceCriteria': ' '}, {'acceptanceCriteria': 'x'*4001},
                    {'priority': ''}, {'priority': 'high'}, {'priority': 1}, {'expectedRevision': '1'},
                    {'expectedRevision': 1.0}, {'expectedRevision': -1}, {'expectedRevision': 2147483648},
                    {'expectedFingerprint': 'A'*64}, {'extra': 'private'}):
        reject(lambda changes=changes: a.save(dict(fresh, **changes)), 400)
    for field in fresh:
        missing = {k: v for k, v in fresh.items() if k != field}
        reject(lambda missing=missing: a.save(missing), 400)
    wire = json.dumps(fresh).encode()
    for raw in (b'{broken', b'{"title":"duplicate",' + wire[1:], b' '*65537 + wire, b'null', b'[]', b'\xff', wire + b'{}'):
        reject(lambda raw=raw: a.request(ROOT, raw, dict(a.headers, **{'Idempotency-Key': str(uuid.uuid4())})), 400)
    reject(lambda: a.request(ROOT, wire, {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}), 403)
    reject(lambda: a.request(ROOT, wire, a.headers), 400)
    for key_value in ('bad', str(uuid.uuid4()).upper(), str(uuid.uuid4()) + ',' + str(uuid.uuid4())):
        reject(lambda key_value=key_value: a.request(ROOT, wire, dict(a.headers, **{'Idempotency-Key': key_value})), 400)
    reject(lambda: a.request(ROOT, wire, dict(a.headers, **{'Content-Type': 'text/plain', 'Idempotency-Key': str(uuid.uuid4())})), 400)
    for suffix in ('?unknown=1', '?x=1&x=2'):
        reject(lambda suffix=suffix: a.request(ROOT + suffix), 400)
        reject(lambda suffix=suffix: a.save(fresh, path=ROOT + suffix), 400)
    for path, status in ((ROOT.replace(ORG, ident('51000000', 5)), 403),
                         (ROOT.replace(TEAM, ident('51010000', 102)), 404),
                         (ROOT.replace(MISSION, ident('51020000', 102)), 404),
                         (ROOT.replace(TASK, ident('51030000', 5001)), 404),
                         (ROOT.replace(TASK, 'not-a-uuid'), 400)):
        reject(lambda path=path: a.request(path), status)
        reject(lambda path=path: a.save(fresh, path=path), status)
    for suffix in ('', '/receipts/' + key):
        reject(lambda suffix=suffix: member.request(ROOT + suffix), 403)
    reject(lambda: member.save(fresh), 403)
    assert member.request(ROOT.removesuffix('/draft-settings'))[0] == 200
    for t, code in ((3,'TASK_NOT_DRAFT'), (4,'TASK_NOT_DRAFT'), (5,'TASK_NOT_DRAFT'),
                    (6,'MISSION_NOT_EDITABLE'), (7,'INTAKE_PAUSED'), (8,'TEAM_ARCHIVED'),
                    (9,'REVISION_EXHAUSTED'), (10,'TASK_NOT_DRAFT'), (11,'TASK_NOT_DRAFT'),
                    (12,'TASK_NOT_DRAFT'), (13,'TASK_NOT_DRAFT'), (14,'TASK_NOT_DRAFT')):
        path = ROOT.replace(TASK, ident('51030000', 1000+t))
        if t in (6,7,8):
            path = path.replace(MISSION, ident('51020000', {6:102,7:103,8:104}[t]))
        if t == 8:
            path = path.replace(TEAM, ident('51010000', 102))
        context = a.context(path)
        assert code in context['blockers'] and not context['canEdit']
        reject(lambda path=path, context=context: a.save(desired(context), path=path), 409, 'TASK_EDIT_BLOCKED')

    # Actual external non-editable change invalidates preflight; restore exact value.
    old = sql(f"SELECT task_intake_version FROM missions WHERE id='{MISSION}';")
    reviewed = a.context()
    try:
        sql(f"UPDATE missions SET task_intake_version=task_intake_version+1 WHERE id='{MISSION}';")
        assert a.context()['fingerprint'] != reviewed['fingerprint']
        reject(lambda: a.save(desired(reviewed)), 409, 'TASK_EDIT_CONFLICT')
    finally:
        sql(f"UPDATE missions SET task_intake_version={int(old)} WHERE id='{MISSION}';")

    # Same actor independent sessions; actual row serialization, no in-memory oracle.
    c, key = a.context(), str(uuid.uuid4())
    same_body = desired(c)
    before, journals = task(), {t: len(rows(t)) for t in ('task_edit_events','task_edit_receipts','audit_events')}
    results = race([a,b], [(same_body,key), (same_body,key)])
    assert sorted(r[0] for r in results) == [200,201]
    assert results[0][1]['data'] == results[1][1]['data']
    assert_commit(before, c, same_body, key, next(r for r in results if r[0] == 201))
    for t, count in journals.items():
        assert len(rows(t)) == count + 1
    c = a.context()
    bodies, keys = [desired(c, '🚀'*255), desired(c, '界'*255)], [str(uuid.uuid4()), str(uuid.uuid4())]
    for candidate in bodies:
        candidate.update(description='\u2003' + 'd'*8000 + '\u2003', acceptanceCriteria='a'*4000)
    before = task()
    results = race([a,b], list(zip(bodies, keys)))
    assert sorted(r[0] for r in results) == [201,409]
    winner = next(i for i, r in enumerate(results) if r[0] == 201)
    assert_commit(before, c, bodies[winner], keys[winner], results[winner])
    assert results[1-winner][1]['error']['code'] == 'TASK_EDIT_CONFLICT'
    assert not rows('task_edit_receipts', f"idempotency_key='{keys[1-winner]}'")

    # Force actual insert failures AFTER task update; all four transaction effects roll back.
    for table in ('task_edit_events', 'task_edit_receipts', 'audit_events'):
        condition = f"NEW.organization_id='{ORG}'" + (" AND NEW.event_type='TASK_DRAFT_UPDATED'" if table == 'audit_events' else '')
        try:
            sql(f"CREATE FUNCTION qc_task_edit_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF {condition} THEN RAISE EXCEPTION 'QC intentional transaction failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER qc_task_edit_fail BEFORE INSERT ON {table} FOR EACH ROW EXECUTE FUNCTION qc_task_edit_fail();")
            reject(lambda: a.save(desired(a.context())), 500)
        finally:
            sql(f"DROP TRIGGER IF EXISTS qc_task_edit_fail ON {table}; DROP FUNCTION IF EXISTS qc_task_edit_fail();")

    # Lock wait authority must be checked after acquiring org lock. Restore all mutations.
    account = "email='task-edit-native-other@example.test'"
    member_where = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE {account})"
    for kind in ('role', 'disabled', 'credential'):
        client = Client('other')
        old_account = rows('human_accounts', account)[0]
        before = snapshot()
        mutation = (f"UPDATE organization_memberships SET role='MEMBER' WHERE {member_where};" if kind == 'role' else
                    f"UPDATE human_accounts SET {'enabled=false' if kind == 'disabled' else 'credential_version=credential_version+1'} WHERE {account};")
        try:
            lock_wait(client, mutation)
            assert snapshot() == before
        finally:
            sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {member_where}; UPDATE human_accounts SET enabled={'true' if old_account['enabled'] else 'false'},credential_version={int(old_account['credential_version'])} WHERE {account};")
    # Existing original receipt is denied after fresh authority loss, including replay.
    own = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='task-edit-native-admin@example.test')"
    try:
        sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE {own};")
        reject(lambda: a.save(body, original_key), 403)
        reject(lambda: a.request(ROOT + '/receipts/' + original_key), 403)
    finally:
        sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {own};")
    assert a.context(legacy_path)['values'] == legacy['values']
    assert protected() == stable
    assert rows('tasks', f"id<>'{TASK}'") == unedited_tasks
    for table, old_rows in journal_before.items():
        new_rows = rows(table)
        assert len(new_rows) == len(old_rows) + 3, 'Expected exactly three successful transaction effects: ' + table
        assert all(row in new_rows for row in old_rows), 'Existing journal row changed: ' + table
    assert task()['task_edit_revision'] == 3
    final = snapshot()
    for table, digest in initial.items():
        if table not in ('tasks', 'task_edit_events', 'task_edit_receipts', 'audit_events'):
            assert final[table] == digest, 'Unexpected unrelated business mutation: ' + table
    print('PASS: real task draft editing persistence, strict wire/CSRF/scope, replay, PG races, rollback, lock-wait authority, legacy and safe Trace')


if __name__ == '__main__':
    main()
