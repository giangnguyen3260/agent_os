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
RECEIPT = {'organizationId', 'teamId', 'missionId', 'eventId', 'versionBefore', 'versionAfter', 'pausedBefore', 'pausedAfter', 'committedAt'}
CONTEXT = {'organizationId', 'teamId', 'missionId', 'checkedAt', 'teamName', 'missionTitle', 'teamStatus', 'missionStatus', 'intakePaused', 'intakeVersion', 'fingerprint', 'canChange', 'blockers'}


def ident(prefix, n):
    return f'{prefix}-0000-4000-8000-{n:012d}'


ORG, TEAM, MISSION, TASK = (ident(p, n) for p, n in (
    ('53000000', 1), ('53010000', 101), ('53020000', 101), ('53030000', 1001)))
MISSION_ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}'
ROOT = MISSION_ROOT + '/task-intake'


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
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','human_accounts','organization_memberships','authentication_audit','authentication_attempts') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    entries = [f"'{table}',(SELECT md5(coalesce(string_agg(row_to_json(x)::text,'|' ORDER BY row_to_json(x)::text),'')) FROM {table} x)" for table in tables]
    return json.loads(sql('SELECT json_build_object(' + ','.join(entries) + ');'))


def protected():
    tables = ('teams', 'agents', 'tasks', 'task_dependencies')
    return {t: rows(t) for t in tables}


class Client:
    def __init__(self, kind='admin'):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        csrf = body['data']
        headers = {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'}
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'mission-intake-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
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
        assert set(context) == CONTEXT
        assert re.fullmatch('[0-9a-f]{64}', context['fingerprint'])
        instant(context['checkedAt'])
        assert isinstance(context['intakeVersion'], str) and re.fullmatch(r'0|[1-9][0-9]*', context['intakeVersion'])
        assert int(context['intakeVersion']) <= 9223372036854775807
        assert context['canChange'] == (context['blockers'] == [])
        assert len(context['blockers']) == len(set(context['blockers']))
        return context

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))


def instant(value):
    assert re.fullmatch(r'\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,9})?Z', value)
    return dt.datetime.fromisoformat(value.replace('Z', '+00:00'))


def desired(context, paused=None):
    return {'expectedVersion': context['intakeVersion'], 'expectedFingerprint': context['fingerprint'],
            'paused': not context['intakePaused'] if paused is None else paused}


def mission():
    return rows('missions', f"id='{MISSION}'")[0]


def reject(action, status, code=None):
    before = snapshot()
    result = action()
    assert result[0] == status, (result[0], status)
    if code:
        assert result[1]['error']['code'] == code, result[1].get('error', {}).get('code')
    assert snapshot() == before, 'Read/rejection/replay mutated business data'
    return result


def commit(client, paused=None):
    context = client.context()
    body = desired(context, paused)
    key = str(uuid.uuid4())
    before, stable, counts = mission(), protected(), snapshot()
    other_missions = rows('missions', f"id<>'{MISSION}'")
    result = client.save(body, key)
    receipt = check_commit(before, context, body, key, result)
    assert protected() == stable
    assert rows('missions', f"id<>'{MISSION}'") == other_missions
    delta = snapshot()
    assert {k for k in counts if counts[k] != delta[k]} == {'missions', 'mission_intake_receipts', 'audit_events'}
    return receipt, key, body


def check_commit(before, context, body, key, result):
    assert result[0] == 201, result[0]
    receipt = result[1]['data']
    assert set(receipt) == RECEIPT
    assert [receipt[k] for k in ('organizationId', 'teamId', 'missionId')] == [ORG, TEAM, MISSION]
    assert receipt['versionBefore'] == context['intakeVersion']
    assert receipt['versionAfter'] == str(int(receipt['versionBefore']) + 1)
    assert receipt['pausedBefore'] == context['intakePaused'] and receipt['pausedAfter'] is body['paused']
    instant(receipt['committedAt'])
    after = mission()
    allowed = {'task_intake_paused', 'task_intake_version', 'updated_at'}
    assert {k: v for k, v in before.items() if k not in allowed} == {k: v for k, v in after.items() if k not in allowed}
    assert after['task_intake_paused'] is body['paused'] and after['task_intake_version'] == int(receipt['versionAfter'])
    timestamp = lambda value: dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    assert timestamp(after['updated_at']) == instant(receipt['committedAt'])
    assert after['updated_at'] != before['updated_at']
    stored = rows('mission_intake_receipts', f"organization_id='{ORG}' AND idempotency_key='{key}'")
    assert len(stored) == 1
    encoded = stored[0]['original_response']
    assert (json.loads(encoded) if isinstance(encoded, str) else encoded) == receipt
    assert stored[0]['event_id'] == receipt['eventId']
    actor = sql("SELECT id FROM human_accounts WHERE email='mission-intake-native-admin@example.test';")
    assert stored[0]['actor_id'] == actor
    assert timestamp(stored[0]['created_at']) == instant(receipt['committedAt'])
    audits = rows('audit_events', f"organization_id='{ORG}' AND event_type='MISSION_TASK_INTAKE_UPDATED'")
    matches = [a for a in audits if (json.loads(a['payload']) if isinstance(a['payload'], str) else a['payload'])['eventId'] == receipt['eventId']]
    assert len(matches) == 1
    audit = matches[0]
    assert audit['mission_id'] == MISSION and audit['task_id'] is None and audit['actor_type'] == 'HUMAN'
    assert audit['actor_id'] == actor and timestamp(audit['created_at']) == instant(receipt['committedAt'])
    payload = json.loads(audit['payload']) if isinstance(audit['payload'], str) else audit['payload']
    assert payload == {k: receipt[k] for k in ('eventId', 'versionBefore', 'versionAfter', 'pausedBefore', 'pausedAfter')}
    return receipt


def race(actions):
    barrier = threading.Barrier(len(actions))
    def run(action):
        barrier.wait(timeout=5)
        return action()
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(actions)) as pool:
        return list(pool.map(run, actions))


def ensure_open(client):
    if client.context()['intakePaused']:
        commit(client, False)


def lock_wait(client, mutation):
    body = desired(client.context())
    proc = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        proc.stdin.write(f"BEGIN; SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE; SELECT pg_backend_pid();\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == ORG
        pid = int(proc.stdout.readline().strip())
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(client.save, body)
            try:
                deadline = time.monotonic() + 8
                while time.monotonic() < deadline:
                    if sql(f"SELECT count(*) FROM pg_stat_activity WHERE {pid}=ANY(pg_blocking_pids(pid)) AND wait_event_type='Lock';") != '0':
                        break
                    time.sleep(.05)
                else:
                    raise AssertionError('No observed real HTTP command blocked by organization row lock')
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
    assert a.context()['intakeVersion'] == '7'
    reject(lambda: a.request(ROOT + '/receipts/' + str(uuid.uuid4())), 200)
    reject(lambda: a.save(desired(a.context(), False)), 409, 'MISSION_INTAKE_NO_CHANGE')
    receipt, key, body = commit(a, True)
    assert reject(lambda: a.save(body, key), 200)[1]['data'] == receipt
    assert a.request(ROOT + '/receipts/' + key)[1]['data'] == {'receipt': receipt}
    assert other.request(ROOT + '/receipts/' + key)[1]['data'] == {'receipt': None}
    audit_row = next(row for row in rows('audit_events', f"organization_id='{ORG}' AND event_type='MISSION_TASK_INTAKE_UPDATED'")
                     if json.loads(row['payload'])['eventId'] == receipt['eventId'])
    trace = reject(lambda: a.request(f"/api/v1/organizations/{ORG}/trace-events/{audit_row['id']}"), 200)[1]['data']['event']
    assert set(trace) == {'id','eventType','actorType','createdAt','team','mission','task'}
    assert trace['mission']['id'] == MISSION and trace['task'] is None

    reject(lambda: a.save(dict(body, paused=False), key), 409, 'IDEMPOTENCY_CONFLICT')
    stored = rows('mission_intake_receipts', f"idempotency_key='{key}' AND organization_id='{ORG}'")[0]
    original_response = stored['original_response']
    sql(f"UPDATE mission_intake_receipts SET original_response='PRIVATE_CORRUPT_RECEIPT' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")
    try:
        corrupt = reject(lambda: a.request(ROOT + '/receipts/' + key), 500, 'MISSION_INTAKE_UNAVAILABLE')
        assert 'PRIVATE_CORRUPT_RECEIPT' not in json.dumps(corrupt[1])
        corrupt_post = reject(lambda: a.save(body, key), 500, 'MISSION_INTAKE_UNAVAILABLE')
        assert 'PRIVATE_CORRUPT_RECEIPT' not in json.dumps(corrupt_post[1])
    finally:
        encoded = original_response if isinstance(original_response, str) else json.dumps(original_response)
        escaped = encoded.replace("'", "''")
        sql(f"UPDATE mission_intake_receipts SET original_response='{escaped}' WHERE organization_id='{ORG}' AND idempotency_key='{key}';")

    # Pause blocks creation and fresh draft editing without changing existing tasks.
    task_root = MISSION_ROOT + '/tasks'
    task_edit = task_root + '/' + TASK + '/draft-settings'
    draft = a.request(task_edit)[1]['data']
    assert not draft['canEdit'] and 'INTAKE_PAUSED' in draft['blockers']
    assert not a.request(task_root + '/creation-context')[1]['data']['canCreate']
    commit(a, False)
    assert reject(lambda: a.save(body, key), 200)[1]['data'] == receipt  # historical replay, not a new pause
    assert not a.context()['intakePaused']
    # Replay remains recoverable after mutable lifecycle changes.
    sql(f"UPDATE missions SET status='COMPLETED' WHERE id='{MISSION}';")
    try:
        assert reject(lambda: a.save(body, key), 200)[1]['data'] == receipt
        assert a.request(ROOT + '/receipts/' + key)[1]['data']['receipt'] == receipt
    finally:
        sql(f"UPDATE missions SET status='CREATED' WHERE id='{MISSION}';")
    for path, status in ((ROOT.replace(ORG, ident('53000000', 5)), 403),
                         (ROOT.replace(TEAM, ident('53010000', 102)), 404),
                         (ROOT.replace(MISSION, ident('53020000', 501)), 404)):
        reject(lambda p=path: a.request(p), status)
    reject(lambda: member.request(ROOT), 403)
    reject(lambda: member.save(desired(a.context())), 403)
    for suffix in ('?x=1', '?page=0', '?x=1&x=2'):
        reject(lambda s=suffix: a.request(ROOT+s), 400)
    wire = json.dumps(desired(a.context())).encode()
    reject(lambda: a.request(ROOT, wire, {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())}), 403)
    for bad in (wire[:-1] + b',"unknown":1}', wire[:-1] + b',"paused":true}', b'{}', b'{"paused":"true"}', b'[' + b' '*4096 + b']'):
        reject(lambda raw=bad: a.request(ROOT, raw, dict(a.headers, **{'Idempotency-Key': str(uuid.uuid4())})), 400)
    for raw in (wire+b'{}', b'\xff', json.dumps(dict(desired(a.context()), expectedFingerprint='INVALID')).encode()):
        reject(lambda raw=raw: a.request(ROOT, raw, dict(a.headers, **{'Idempotency-Key': str(uuid.uuid4())})), 400)
    for key_value in ('invalid', str(uuid.uuid4()).upper(), str(uuid.uuid4())+','+str(uuid.uuid4())):
        reject(lambda k=key_value: a.request(ROOT, wire, dict(a.headers, **{'Idempotency-Key': k})), 400)
    reject(lambda: a.request(ROOT, wire, dict(a.headers, **{'Idempotency-Key': str(uuid.uuid4()), 'Content-Type': 'application/json;charset=ISO-8859-1'})), 400)
    for path in (ROOT, ROOT+'/receipts/'+str(uuid.uuid4())):
        reject(lambda path=path: a.request(path+'?x=1'), 400)
    # urllib uses POST when data is supplied; use explicit GET to exercise body rejection.
    for path in (ROOT, ROOT+'/receipts/'+str(uuid.uuid4())):
        req = urllib.request.Request(BASE+path, data=b'{}', method='GET')
        before_get = snapshot()
        try:
            response = a.opener.open(req, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            assert response.status == 400
        assert snapshot() == before_get
    for version in ('00', '-1', '+1', '9223372036854775808', 7, None):
        reject(lambda v=version: a.save(dict(desired(a.context()), expectedVersion=v)), 400)
    for m, code in ((3, 'MISSION_NOT_EDITABLE'), (4, 'REVISION_EXHAUSTED'), (5, 'TEAM_ARCHIVED')):
        path = ROOT.replace(MISSION, ident('53020000', 100+m))
        if m == 5:
            path = path.replace(TEAM, ident('53010000', 102))
        c = a.context(path)
        assert not c['canChange'] and code in c['blockers']
        reject(lambda p=path, c=c: a.save(desired(c), path=p), 409, 'MISSION_INTAKE_BLOCKED')
    # Stale parent metadata beyond revision invalidates review.
    c = a.context()
    sql(f"UPDATE missions SET title=title||' changed' WHERE id='{MISSION}';")
    try:
        reject(lambda: a.save(desired(c)), 409, 'MISSION_INTAKE_CONFLICT')
    finally:
        sql(f"UPDATE missions SET title='Mission intake Mission 1' WHERE id='{MISSION}';")
    # Actual same-key and competing-key races.
    c, k = a.context(), str(uuid.uuid4())
    cmd = desired(c)
    before = mission()
    race_counts = {t: len(rows(t)) for t in ('mission_intake_receipts', 'audit_events')}
    result = race([lambda: a.save(cmd, k), lambda: b.save(cmd, k)])
    assert sorted(r[0] for r in result) == [200, 201]
    assert result[0][1]['data'] == result[1][1]['data']
    check_commit(before, c, cmd, k, next(r for r in result if r[0] == 201))
    assert all(len(rows(t)) == n+1 for t,n in race_counts.items())
    c = a.context(); cmd = desired(c)
    before = mission(); keys = [str(uuid.uuid4()), str(uuid.uuid4())]
    race_counts = {t: len(rows(t)) for t in ('mission_intake_receipts', 'audit_events')}
    result = race([lambda: a.save(cmd, keys[0]), lambda: b.save(cmd, keys[1])])
    assert sorted(r[0] for r in result) == [201, 409]
    winner = next(i for i,r in enumerate(result) if r[0] == 201)
    check_commit(before,c,cmd,keys[winner],result[winner])
    assert result[1-winner][1]['error']['code'] == 'MISSION_INTAKE_CONFLICT'
    assert all(len(rows(t)) == n+1 for t,n in race_counts.items())
    assert not rows('mission_intake_receipts', f"idempotency_key='{keys[1-winner]}'")
    # Insert failure rolls back mission, receipt and audit together.
    for table in ('mission_intake_receipts', 'audit_events'):
        condition = f"NEW.organization_id='{ORG}'" + (" AND NEW.event_type='MISSION_TASK_INTAKE_UPDATED'" if table == 'audit_events' else '')
        try:
            sql(f"CREATE FUNCTION qc_intake_fail() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF {condition} THEN RAISE EXCEPTION 'QC intentional failure'; END IF; RETURN NEW; END $$; CREATE TRIGGER qc_intake_fail BEFORE INSERT ON {table} FOR EACH ROW EXECUTE FUNCTION qc_intake_fail();")
            reject(lambda: a.save(desired(a.context())), 500)
        finally:
            sql(f"DROP TRIGGER IF EXISTS qc_intake_fail ON {table}; DROP FUNCTION IF EXISTS qc_intake_fail();")
    # Real Task Create competes for the same parent revision/locks.
    ensure_open(a)
    c = a.context(); old_version = int(c['intakeVersion'])
    create_body = {'title': 'QC intake race', 'description': 'Real input', 'acceptanceCriteria': 'Real acceptance',
                   'priority': 'NORMAL', 'ownerAgentId': None, 'dependencyIds': [], 'expectedIntakeVersion': old_version}
    create_key = str(uuid.uuid4())
    before_tasks = rows('tasks'); before_mission = mission()
    race_counts = {t: len(rows(t)) for t in ('mission_intake_receipts','task_create_receipts','audit_events')}
    result = race([lambda: a.save(desired(c, True)), lambda: b.save(create_body, create_key, task_root)])
    assert sorted(r[0] for r in result) == [201, 409]
    intake_won = result[0][0] == 201
    assert mission()['task_intake_version'] == before_mission['task_intake_version']+1
    assert mission()['task_intake_paused'] is intake_won
    assert len(rows('tasks')) == len(before_tasks)+(0 if intake_won else 1)
    assert len(rows('mission_intake_receipts')) == race_counts['mission_intake_receipts']+int(intake_won)
    assert len(rows('task_create_receipts')) == race_counts['task_create_receipts']+int(not intake_won)
    assert len(rows('audit_events')) == race_counts['audit_events']+1
    assert result[1 if intake_won else 0][1]['error']['code'] == ('TASK_SETUP_CONFLICT' if intake_won else 'MISSION_INTAKE_CONFLICT')
    if intake_won: assert rows('tasks') == before_tasks
    else:
        old_ids={t['id'] for t in before_tasks}
        assert [t for t in rows('tasks') if t['id'] in old_ids] == before_tasks
    ensure_open(a)
    # Accepted create replays after a subsequent pause; paused context cannot create new work.
    create_body['expectedIntakeVersion'] = int(a.context()['intakeVersion'])
    create_key = str(uuid.uuid4())
    created = a.save(create_body, create_key, task_root); assert created[0] == 201
    commit(a, True)
    assert reject(lambda: a.save(create_body, create_key, task_root), 200)[1]['data'] == created[1]['data']
    # Exhausted counter cannot overflow via Task Create; historical accepted replay remains valid.
    max_root = task_root.replace(MISSION, ident('53020000', 104))
    max_context = a.request(max_root + '/creation-context')[1]['data']
    assert max_context['intakeVersion'] == 9223372036854775807 and not max_context['canCreate']
    reject(lambda: a.save(dict(create_body, expectedIntakeVersion=9223372036854775807), path=max_root), 409, 'TASK_SETUP_CONFLICT')
    saved_version = int(a.context()['intakeVersion'])
    sql(f"UPDATE missions SET task_intake_version=9223372036854775807 WHERE id='{MISSION}';")
    try:
        assert reject(lambda: a.save(create_body, create_key, task_root), 200)[1]['data'] == created[1]['data']
    finally:
        sql(f"UPDATE missions SET task_intake_version={saved_version} WHERE id='{MISSION}';")
    # Real Draft Edit race: edit may precede pause, but cannot pass after it.
    ensure_open(a)
    edit_context = a.request(task_edit)[1]['data']; c = a.context()
    edit_body = dict(edit_context['values'], expectedRevision=edit_context['revision'], expectedFingerprint=edit_context['fingerprint'])
    edit_body.update(title='QC race edit', description='Race input', acceptanceCriteria='Race acceptance', priority='HIGH')
    old_tasks = rows('tasks'); old_deps = rows('task_dependencies'); old_edit_task = task()
    race_counts = {t: len(rows(t)) for t in ('mission_intake_receipts','task_edit_events','task_edit_receipts','audit_events')}
    result = race([lambda: a.save(desired(c, True)), lambda: b.save(edit_body, path=task_edit)])
    assert result[0][0] == 201 and result[1][0] in (201, 409)
    accepted = result[1][0] == 201
    if accepted:
        assert task()['task_edit_revision'] == old_edit_task['task_edit_revision']+1
        assert [task()[k] for k in ('title','description','acceptance_criteria','priority')] == [edit_body[k] for k in ('title','description','acceptanceCriteria','priority')]
    else:
        assert result[1][1]['error']['code'] == 'TASK_EDIT_CONFLICT'
        assert task() == old_edit_task
    assert [t for t in rows('tasks') if t['id'] != TASK] == [t for t in old_tasks if t['id'] != TASK]
    assert rows('task_dependencies') == old_deps
    assert len(rows('mission_intake_receipts')) == race_counts['mission_intake_receipts']+1
    assert len(rows('task_edit_events')) == race_counts['task_edit_events']+int(accepted)
    assert len(rows('task_edit_receipts')) == race_counts['task_edit_receipts']+int(accepted)
    assert len(rows('audit_events')) == race_counts['audit_events']+1+int(accepted)
    current = a.request(task_edit)[1]['data']
    assert not current['canEdit'] and 'INTAKE_PAUSED' in current['blockers']
    stable_task_keys = {'status', 'pending_reason', 'waiting_since', 'resolver', 'owner_agent_id', 'created_at', 'id', 'organization_id', 'team_id', 'mission_id'}
    assert [{k:v for k,v in t.items() if k in stable_task_keys} for t in rows('tasks')] == [{k:v for k,v in t.items() if k in stable_task_keys} for t in old_tasks]
    # Observe real blocking, then revoke role/credential/enabled state before command proceeds.
    account = "email='mission-intake-native-admin2@example.test'"
    own = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE {account})"
    for kind in ('role', 'disabled', 'credential'):
        client = Client('admin2'); original = rows('human_accounts', account)[0]
        mutation = (f"UPDATE organization_memberships SET role='MEMBER' WHERE {own};" if kind == 'role' else
                    f"UPDATE human_accounts SET {'enabled=false' if kind == 'disabled' else 'credential_version=credential_version+1'} WHERE {account};")
        before = snapshot()
        try:
            lock_wait(client, mutation)
            assert snapshot() == before
        finally:
            sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {own}; UPDATE human_accounts SET enabled={'true' if original['enabled'] else 'false'},credential_version={int(original['credential_version'])} WHERE {account};")
    # Current authority also gates existing own receipt lookup and historical replay.
    own_admin = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='mission-intake-native-admin@example.test')"
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE {own_admin};")
    try:
        reject(lambda: a.request(ROOT+'/receipts/'+key), 403)
        reject(lambda: a.save(body,key), 403)
    finally:
        sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {own_admin};")
    # Restore open state for independent downstream fixture use; no fabricated runtime transitions.
    ensure_open(a)
    print('PASS: mission intake real persistence/replay, strict wire, atomic rollback, Task Create/Edit races and lock-wait authority')


if __name__ == '__main__':
    main()
