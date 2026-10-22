"""Native PostgreSQL exact-persistence, revocation, concurrent and no-execution probes.
Run after approval-detail-ci-fixture.sql against disposable Docker CI only.
"""
import concurrent.futures
from datetime import datetime
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import select
import time
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '30303030-3030-4030-8030-303030303030'
FOREIGN = '31313131-3131-4131-8131-313131313131'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ROOT = f'/api/v1/organizations/{ORG}/approvals/'


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


def sql(query, timeout=30):
    return subprocess.check_output(
        ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
         'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        cwd=Path(__file__).resolve().parents[1], input=query, text=True, timeout=timeout).strip()


class Client:
    def __init__(self, role):
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'approval-detail-{role}@example.test',
            'password': os.environ['E2E_PASSWORD']}).encode(), content_type='application/x-www-form-urlencoded')
        assert status == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']

    def request(self, path, body=None, key=None, content_type='application/json', csrf=True):
        headers = {'Content-Type': content_type}
        if key:
            headers['Idempotency-Key'] = key
        if body is not None and csrf:
            headers[self.csrf['headerName']] = self.csrf['token']
        if isinstance(body, dict):
            body = json.dumps(body).encode()
        try:
            response = self.http.open(urllib.request.Request(BASE + path, body, headers), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert 'FOREIGN_APPROVAL_PRIVATE' not in raw and 'password_hash' not in raw
            return response.status, json.loads(raw) if raw else None

    def detail(self, n):
        status, body = self.request(ROOT + ident('3050', n))
        assert status == 200, (n, status, body)
        return body['data']

    def decide(self, n, operation='approve', key=None, body=None):
        if body is None:
            snap = self.detail(n)['snapshot']
            body = {'snapshotVersion': snap['version'], 'snapshotSha256': snap['payloadSha256'],
                    'acknowledgement': True, 'reason': 'Declined safely' if operation == 'reject' else None}
        return self.request(ROOT + ident('3050', n) + '/' + operation, body, key or str(uuid.uuid4()))


def state():
    return sql(''.join(f'SELECT row_to_json(x)::text FROM {t} x ORDER BY id;'
                      for t in ('approval_requests', 'approval_decisions', 'audit_events')) +
               'SELECT row_to_json(x)::text FROM approval_receipts x ORDER BY organization_id,actor_id,idempotency_key;' +
               'SELECT row_to_json(x)::text FROM approval_snapshots x ORDER BY approval_id;' +
               'SELECT row_to_json(x)::text FROM approval_action_heads x ORDER BY action_id;')


def effects():
    return sql(''.join(f'SELECT row_to_json(x)::text FROM {t} x ORDER BY id;'
                      for t in ('teams', 'agents', 'missions', 'tasks')))


class Holder:
    """Controlled native transaction; every exit rolls back or ends its backend."""
    def __init__(self, statement):
        self.name = 'approval-detail-probe-' + uuid.uuid4().hex
        self.pid = None
        self.process = subprocess.Popen(
            ['docker','compose','exec','-T','postgres','sh','-c',
             'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
            cwd=Path(__file__).resolve().parents[1],stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            self.send(f"SET application_name='{self.name}';\n\\o /dev/null\nBEGIN; {statement};\n\\o\nSELECT 'LOCKED:'||pg_backend_pid();")
            assert select.select([self.process.stdout],[],[],15)[0], 'lock-holder marker timed out'
            marker = self.process.stdout.readline().strip()
            assert marker.startswith('LOCKED:'), marker
            self.pid = int(marker.split(':')[1])
        except BaseException:
            self.close()
            raise

    def send(self, statement):
        self.process.stdin.write(statement + '\n')
        self.process.stdin.flush()

    def expect_marker(self, expected):
        assert select.select([self.process.stdout],[],[],15)[0], 'transaction completion marker timed out'
        assert self.process.stdout.readline().strip() == expected, 'transaction failed before completion'

    def close(self):
        try:
            if self.process.poll() is None:
                try:
                    self.send('ROLLBACK;')
                except (BrokenPipeError, ValueError):
                    pass
                if not self.process.stdin.closed:
                    self.process.stdin.close()
                try:
                    self.process.wait(timeout=3)
                except subprocess.TimeoutExpired:
                    # Terminate this exact dedicated PG backend, never an application backend.
                    try:
                        sql(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{self.name}' AND pid<>pg_backend_pid();", timeout=5)
                    finally:
                        self.process.terminate()
                        try:
                            self.process.wait(timeout=3)
                        except subprocess.TimeoutExpired:
                            self.process.kill()
                            self.process.wait(timeout=3)
        finally:
            for pipe in (self.process.stdin,self.process.stdout,self.process.stderr):
                if not pipe.closed:
                    try:
                        pipe.close()
                    except (BrokenPipeError, ValueError):
                        pass


def await_lock_wait(holder, relation):
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        blocked = sql(f"SELECT count(*) FROM pg_stat_activity WHERE pid<>pg_backend_pid() AND wait_event_type='Lock' AND query ILIKE '%{relation}%' AND {holder.pid}=ANY(pg_blocking_pids(pid));", timeout=max(0.1,min(3,deadline-time.monotonic())))
        if int(blocked) > 0:
            return
    raise AssertionError(f'No application waiter observed on {relation}')


def capture_shared_state():
    accounts = ','.join("'"+ident('3080',n)+"'" for n in (1,2,3))
    grants = ','.join("'"+ident('3070',n)+"'" for n in (1,2))
    return {
        'accounts': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT id,enabled,credential_version FROM human_accounts WHERE id IN ({accounts}) ORDER BY id) x;")),
        'memberships': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT account_id,organization_id,role FROM organization_memberships WHERE account_id IN ({accounts}) AND organization_id='{ORG}' ORDER BY account_id) x;")),
        'grants': json.loads(sql(f"SELECT json_agg(x) FROM (SELECT * FROM approval_grants WHERE id IN ({grants}) ORDER BY id) x;")),
    }


def restore_shared_state(baseline):
    def literal(value):
        if value is None:
            return 'NULL'
        if isinstance(value,bool):
            return 'true' if value else 'false'
        return "'" + str(value).replace("'", "''") + "'"
    queries = ['BEGIN;', 'ALTER TABLE audit_events DROP CONSTRAINT IF EXISTS approval_detail_probe_rollback;']
    for account in baseline['accounts']:
        queries.append(f"UPDATE human_accounts SET enabled={literal(account['enabled'])},credential_version={literal(account['credential_version'])} WHERE id={literal(account['id'])};")
    for membership in baseline['memberships']:
        queries.append('INSERT INTO organization_memberships(account_id,organization_id,role) VALUES (' +
                       ','.join(literal(membership[k]) for k in ('account_id','organization_id','role')) +
                       ') ON CONFLICT (account_id,organization_id) DO UPDATE SET role=EXCLUDED.role;')
    for grant in baseline['grants']:
        fields = ('organization_id','team_id','account_id','action_type','risk_level','expires_at','revoked_at')
        queries.append('UPDATE approval_grants SET ' + ','.join(k+'='+literal(grant[k]) for k in fields) +
                       ' WHERE id='+literal(grant['id'])+';')
    queries.append('COMMIT;')
    sql(''.join(queries))
    assert capture_shared_state() == baseline, 'Shared browser authority was not restored exactly'


def main():
    admin, member = Client('admin'), Client('member')
    untouched = effects()
    # Native storage canonical bytes agree with Java recomputation.
    assert sql("SELECT bool_and(payload_sha256=encode(sha256(convert_to(canonical_payload,'UTF8')),'hex')) FROM approval_snapshots WHERE right(approval_id::text,12)::int%100<>13;") == 't'
    before = state()
    snap = member.detail(930)['snapshot']
    valid = {'snapshotVersion':1,'snapshotSha256':snap['payloadSha256'],'acknowledgement':True,'reason':None}
    endpoint = ROOT + ident('3050',930) + '/approve'
    assert member.request(endpoint,valid,str(uuid.uuid4()),csrf=False)[0] == 403
    for invalid in (dict(valid,snapshotVersion=1.5),dict(valid,acknowledgement='true'),dict(valid,acknowledgement=False),dict(valid,unexpected='payload')):
        assert member.request(endpoint,invalid,str(uuid.uuid4()))[0] == 400
    assert member.request(f'/api/v1/organizations/{ORG}/teams',{})[0] == 403
    assert state() == before
    for n, expected in ((904,403), (905,403), (906,409), (907,409), (909,409), (910,409), (912,409), (913,409), (914,403), (940,409)):
        detail = admin.detail(n)
        assert not detail['eligibility']['canApprove']
        snap = detail['snapshot']
        body = {'snapshotVersion': 1, 'snapshotSha256': snap['payloadSha256'] if snap else '0'*64,
                'acknowledgement': True, 'reason': None}
        assert admin.decide(n, body=body)[0] == expected, n
    assert admin.detail(908)['snapshot'] is None
    assert admin.request(f'/api/v1/organizations/{FOREIGN}/approvals/{ident("3050",9999)}')[0] == 403
    assert admin.request(ROOT + ident('3050',9999))[0] == 404
    assert state() == before
    # ADMIN and MEMBER independently record results; replay is exact and survives inactive head.
    for client, n in ((admin,901),(member,902)):
        key = str(uuid.uuid4())
        snap = client.detail(n)['snapshot']
        body = {'snapshotVersion': 1, 'snapshotSha256': snap['payloadSha256'], 'acknowledgement': True, 'reason': None}
        code, result = client.decide(n,key=key,body=body)
        assert code == 201, (code,result)
        stored = json.loads(sql(f"SELECT row_to_json(d)::text FROM approval_decisions d WHERE approval_id='{ident('3050',n)}';"))
        decision = result['data']
        assert stored['id'] == decision['id'] and stored['snapshot_sha256'] == body['snapshotSha256']
        assert decision['approvalId'] == ident('3050',n) and decision['status'] == 'APPROVED'
        assert decision['actorId'] == ident('3080',1 if n==901 else 2)
        assert stored['actor_id'] == decision['actorId'] and stored['actor_name'] == decision['actorName']
        assert stored['reason'] is None and decision['reason'] is None
        assert stored['snapshot_version'] == decision['snapshotVersion'] == 1
        assert datetime.fromisoformat(stored['decided_at']) == datetime.fromisoformat(decision['decidedAt'].replace('Z','+00:00'))
        sql(f"UPDATE approval_action_heads SET active=false WHERE action_id='{ident('3060',n)}';")
        replay_code, replay = client.decide(n,key=key,body=body)
        # ApiMeta.requestId is fresh per HTTP response; only data is the immutable result.
        assert replay_code == 200, {'case': n, 'replayStatus': replay_code,
                                   'errorCode': replay.get('error', {}).get('code') if isinstance(replay.get('error'), dict) else None}
        replay_data = replay.get('data')
        differing_fields = sorted(k for k in set(decision) | set(replay_data or {})
                                  if decision.get(k) != (replay_data or {}).get(k))
        assert replay_data == decision, {'case': n, 'differingDecisionFields': differing_fields,
                                         'replayDataType': type(replay_data).__name__}
        assert client.decide(n,key=key,body=dict(body,reason='different'))[0] == 409
        assert sql(f"SELECT count(*) FROM audit_events WHERE event_type='APPROVAL_DECIDED' AND payload::jsonb->>'approvalId'='{ident('3050',n)}';") == '1'
        grant = ident('3070',1 if n==901 else 2)
        sql(f"UPDATE approval_grants SET revoked_at=now() WHERE id='{grant}';")
        frozen = state()
        assert client.decide(n,key=key,body=body)[0] == 403
        assert state() == frozen
        sql(f"UPDATE approval_grants SET revoked_at=null WHERE id='{grant}';")
    # Two different actors on independent sessions race opposite decisions on one locked head.
    gate = threading.Barrier(2)
    def race(client, operation):
        gate.wait(timeout=10)
        return client.decide(921,operation)[0]
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        calls = [pool.submit(race,admin,'approve'),pool.submit(race,member,'reject')]
        assert sorted(c.result(timeout=45) for c in calls) == [201,409]
    assert sql(f"SELECT count(*) FROM approval_decisions WHERE approval_id='{ident('3050',921)}';") == '1'
    # Same key, different actors is intentionally a different namespace.
    shared = str(uuid.uuid4())
    assert admin.decide(922,key=shared)[0] == 201
    assert member.decide(923,key=shared)[0] == 201
    frozen = state()
    assert admin.decide(926,key=shared)[0] == 409
    assert state() == frozen
    assert admin.detail(927)['eligibility']['canApprove']
    # Force the final audit write to fail: status/decision/receipt must all roll back.
    sql(f"ALTER TABLE audit_events ADD CONSTRAINT approval_detail_probe_rollback CHECK (organization_id <> '{ORG}' OR event_type <> 'APPROVAL_DECIDED') NOT VALID;")
    frozen = state()
    try:
        assert admin.decide(927)[0] != 201
        assert state() == frozen
    finally:
        sql('ALTER TABLE audit_events DROP CONSTRAINT approval_detail_probe_rollback;')
    # Deterministic org-first writer barrier: approval must wait on the held org row,
    # leaving its actor available to the existing create-service lock order.
    snap = admin.detail(931)['snapshot']
    body = {'snapshotVersion':1,'snapshotSha256':snap['payloadSha256'],'acknowledgement':True,'reason':None}
    holder = Holder(f"SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE")
    pool = concurrent.futures.ThreadPoolExecutor(1)
    try:
        pending = pool.submit(admin.decide,931,'approve',str(uuid.uuid4()),body)
        await_lock_wait(holder,'organizations')
        holder.send(f"\\o /dev/null\nSET LOCAL lock_timeout='3s'; SELECT id FROM human_accounts WHERE id='{ident('3080',1)}' FOR UPDATE; SELECT account_id FROM organization_memberships WHERE account_id='{ident('3080',1)}' AND organization_id='{ORG}' FOR UPDATE; COMMIT;\n\\o\nSELECT 'WRITER_COMMITTED';")
        holder.expect_marker('WRITER_COMMITTED')
        assert pending.result(timeout=20)[0] == 201
    finally:
        try:
            holder.close()
        finally:
            pool.shutdown(wait=True,cancel_futures=True)
    # Deterministic grant-revocation barrier: observe the actual POST waiting on the
    # still-uncommitted grant update before allowing revocation to commit.
    grant = ident('3070',2)
    snap = member.detail(924)['snapshot']
    body = {'snapshotVersion':1,'snapshotSha256':snap['payloadSha256'],'acknowledgement':True,'reason':None}
    frozen = state()
    holder = Holder(f"UPDATE approval_grants SET revoked_at=now() WHERE id='{grant}'")
    pool = concurrent.futures.ThreadPoolExecutor(1)
    try:
        pending = pool.submit(member.decide,924,'approve',str(uuid.uuid4()),body)
        await_lock_wait(holder,'approval_grants')
        holder.send("COMMIT; SELECT 'REVOCATION_COMMITTED';")
        holder.expect_marker('REVOCATION_COMMITTED')
        assert pending.result(timeout=20)[0] == 403
        assert state() == frozen
    finally:
        try:
            holder.close()
        finally:
            try:
                pool.shutdown(wait=True,cancel_futures=True)
            finally:
                sql(f"UPDATE approval_grants SET revoked_at=null WHERE id='{grant}';")
    # Credential and membership revocation reject without business writes.
    account = ident('3080',2)
    for update, restore in ((f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{account}';",f"UPDATE human_accounts SET credential_version=credential_version-1 WHERE id='{account}';"),
                            (f"DELETE FROM organization_memberships WHERE account_id='{account}' AND organization_id='{ORG}';",f"INSERT INTO organization_memberships VALUES ('{account}','{ORG}','MEMBER');")):
        sql(update)
        frozen = state()
        assert member.decide(925,body={'snapshotVersion':1,'snapshotSha256':'0'*64,'acknowledgement':True,'reason':None})[0] in (401,403)
        assert state() == frozen
        sql(restore)
        member = Client('member')
    assert effects() == untouched, 'Decisions changed task/mission/runtime inputs'
    print('PASS: native PostgreSQL exact decisions/events/receipts, scoped ADMIN/MEMBER, replay, concurrency, lock/revocation, blockers, no execution writes')


if __name__ == '__main__':
    baseline = capture_shared_state()
    try:
        main()
    finally:
        restore_shared_state(baseline)
