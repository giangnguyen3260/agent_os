"""Disposable native PostgreSQL role-management acceptance probe.
Run once after member-role-management-ci-fixture.sql. MRM_PSQL_COMMAND is a JSON
psql argv override; E2E_BASE_URL/E2E_PASSWORD follow existing probes.
--vectors-only checks the published vectors; it is not a native acceptance run.
"""
import concurrent.futures
from datetime import datetime
import hashlib
import http.cookiejar
import json
import os
import re
from pathlib import Path
import select
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '80808080-8080-4080-8080-808080808080'
FOREIGN = '81818181-8181-4181-8181-818181818181'
CROSS = '82828282-8282-4282-8282-828282828282'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
RESULT_FIELDS = {'operation','organizationId','accountId','actorId','actorName','idempotencyKey',
                 'eventId','revision','recordedAt','beforeRole','afterRole'}


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


def root(n, org=ORG):
    return f'/api/v1/organizations/{org}/members/{ident("8080", n)}/role-management'


def psql_command():
    if os.environ.get('MRM_PSQL_COMMAND'):
        command = json.loads(os.environ['MRM_PSQL_COMMAND'])
        assert isinstance(command, list) and all(isinstance(x, str) for x in command)
        return command
    return ['docker','compose','exec','-T','postgres','sh','-c',
            'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql(query, timeout=30):
    return subprocess.check_output(psql_command(), cwd=Path(__file__).resolve().parents[1],
                                   input=query, text=True, timeout=timeout).strip()


class Client:
    def __init__(self, role):
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.csrf = body['data']
        status, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'role-management-{role}@example.test',
            'password': os.environ['E2E_PASSWORD']}).encode(),
            content_type='application/x-www-form-urlencoded')
        assert status == 200, ('login', role, status)
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
            body = json.dumps(body, ensure_ascii=False).encode()
        try:
            response = self.http.open(urllib.request.Request(BASE+path,body,headers),timeout=40)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            assert 'password_hash' not in raw and 'FOREIGN_ROLE_TEAM_PRIVATE' not in raw
            return response.status, json.loads(raw) if raw else None

    def data(self, path):
        status, body = self.request(path)
        assert status == 200, (path,status,body)
        return body['data']


def command(revision=0, before='MEMBER', after='ADMIN', reason='  Native reason\t\n\r 😀  '):
    return dict(expectedRevision=revision, expectedCurrentRole=before, newRole=after,
                reason=reason, acknowledgement=True)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()


def vectors():
    value = dict(command(reason='é\r\n😀'), domain='MEMBER_ROLE_MANAGEMENT', version=1,
                 operation='CHANGE_ROLE', organizationId=ORG, accountId=ident('8080',2))
    assert digest(value)=='a6ee4d1ac1fa261229dcb1aeed52ffb420e6cf3ecedf1ba3dafdc4d99d16fe2c'
    value['reason']='e\u0301\r\n😀'
    assert digest(value)=='39e3e2f8e98a2eea93af07603c598aa1772d28163ed1e75c3988415758270955'
    print('Published literal UTF-8 canonical vectors matched (no server run)')


class Holder:
    """Controlled native transaction; every exit rolls back or ends its backend."""
    def __init__(self, statement):
        self.name = 'member-role-management-probe-' + uuid.uuid4().hex
        self.pid = None
        self.process = subprocess.Popen(
            psql_command(),
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



def blocked_by(pid, relation, minimum=1):
    deadline = time.monotonic()+15
    while time.monotonic()<deadline:
        rows = sql(f"WITH RECURSIVE edges AS (SELECT pid,unnest(pg_blocking_pids(pid)) blocker FROM pg_stat_activity), chain AS (SELECT * FROM edges UNION SELECT c.pid,e.blocker FROM chain c JOIN edges e ON e.pid=c.blocker) SELECT coalesce(string_agg(DISTINCT a.pid::text,','),'') FROM chain c JOIN pg_stat_activity a ON a.pid=c.pid WHERE c.blocker={int(pid)} AND a.wait_event_type='Lock' AND a.query ILIKE '%{relation}%';", timeout=3)
        if rows and len(rows.split(','))>=minimum:
            return [int(p) for p in rows.split(',')]
    raise AssertionError(f'Expected {minimum} observed {relation} waiters blocked by {pid}')


def state():
    # Entire affected business state, including auth credentials and unrelated orgs.
    keys = {
        'organization_memberships':'organization_id,account_id', 'human_accounts':'id',
        'approval_grants':'id', 'approval_grant_ledgers':'organization_id,account_id',
        'approval_grant_management_events':'id',
        'approval_grant_management_receipts':'organization_id,actor_id,idempotency_key',
        'member_role_ledgers':'organization_id,account_id', 'member_role_events':'id',
        'member_role_receipts':'organization_id,actor_id,idempotency_key',
        'audit_events':'id', 'teams':'id', 'agents':'id', 'missions':'id', 'tasks':'id',
        'approval_requests':'id', 'approval_decisions':'id',
        'approval_receipts':'organization_id,actor_id,idempotency_key',
        'approval_information_receipts':'organization_id,actor_id,idempotency_key',
        'approval_information_entries':'id', 'approval_action_heads':'action_id',
        'approval_snapshots':'approval_id,version'}
    return sql(''.join(f'SELECT row_to_json(x)::text FROM {table} x ORDER BY {key};' for table,key in keys.items()))


def preserved():
    # Sessions have access-time updates from HTTP; assert identity/credential and grant data,
    # plus runtime rows, rather than mistaking routine session touches for role mutations.
    return sql(''.join(f'SELECT row_to_json(x)::text FROM {table} x ORDER BY {key};' for table,key in {
        'human_accounts':'id','approval_grants':'id','approval_grant_ledgers':'organization_id,account_id',
        'approval_grant_management_events':'id','approval_grant_management_receipts':'organization_id,actor_id,idempotency_key',
        'teams':'id','agents':'id','missions':'id','tasks':'id'}.items()))


def change(client, target, body=None, org=ORG, key=None):
    body = body or command()
    key = key or str(uuid.uuid4())
    status, envelope = client.request(root(target,org),body,key)
    assert status==201,(status,envelope)
    result=envelope['data']
    assert set(result)==RESULT_FIELDS and result['operation']=='CHANGE_ROLE'
    assert result['accountId']==ident('8080',target) and result['organizationId']==org
    assert result['idempotencyKey']==key and result['revision']==body['expectedRevision']+1
    assert result['beforeRole']==body['expectedCurrentRole'] and result['afterRole']==body['newRole']
    assert re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?Z',result['recordedAt'])
    return result,key


def main():
    vectors()
    admin,member=Client('admin'),Client('member')
    unchanged=preserved()
    browser_before=sql(f"SELECT row_to_json(m)::text FROM organization_memberships m WHERE organization_id='{ORG}' AND account_id<'{ident('8080',900)}' ORDER BY account_id;")
    frozen=state()
    context=admin.data(root(905)+'/context')
    assert context['revision']==0 and context['selectedRole'] is None and not context['canChange']
    assert admin.data(root(905)+'/history')['total']==0
    for target,body,expected in [(1,command(before='ADMIN',after='MEMBER'),400),(6,command(),400),
                                 (7,command(),404),(99,command(),404),(905,command(after='MEMBER'),400),
                                 (905,command(revision=1),409)]:
        assert admin.request(root(target),body,str(uuid.uuid4()))[0]==expected
    assert member.request(root(905),command(),str(uuid.uuid4()))[0]==403
    assert admin.request(root(905),command(),str(uuid.uuid4()),csrf=False)[0]==403
    assert state()==frozen
    result,key=change(admin,905,command(reason='é\r\n😀'))
    assert admin.request(root(905),command(reason='é\r\n😀'),key)[1]['data']==result
    assert admin.data(root(905)+f'/receipts/{key}')==result
    assert admin.data(root(906)+f'/receipts/{key}') is None
    assert admin.request(root(906),command(reason='é\r\n😀'),key)[0]==409
    expected=digest(dict(command(reason='é\r\n😀'),domain='MEMBER_ROLE_MANAGEMENT',version=1,operation='CHANGE_ROLE',organizationId=ORG,accountId=ident('8080',905)))
    assert sql(f"SELECT fingerprint FROM member_role_receipts WHERE organization_id='{ORG}' AND actor_id='{ident('8080',1)}' AND idempotency_key='{key}';")==expected
    change(admin,905,command(1,'ADMIN','MEMBER'))
    assert admin.request(root(905),command(),str(uuid.uuid4()))[0]==409 # ABA
    assert admin.request(root(905),command(reason='é\r\n😀'),key)[1]['data']==result
    sql(f"DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{ident('8080',905)}'; UPDATE human_accounts SET enabled=false WHERE id='{ident('8080',905)}';")
    try:
        assert admin.request(root(905)+'/context')[0]==404
        assert admin.data(root(905)+'/history')['total']==2
        assert admin.request(root(905),command(reason='é\r\n😀'),key)[1]['data']==result
    finally:
        # Restore this deliberate native fixture change before comparing global state.
        # Keep the membership removed to retain the removed-target history coverage.
        sql(f"UPDATE human_accounts SET enabled=true WHERE id='{ident('8080',905)}';")
    assert preserved()==unchanged
    # Audit failure occurs after all writes. Compare every business table, not just counts.
    sql(f"ALTER TABLE audit_events ADD CONSTRAINT mrm_probe_rollback CHECK (organization_id<>'{ORG}' OR event_type<>'MEMBER_ROLE_CHANGED') NOT VALID;")
    frozen=state()
    try:
        assert admin.request(root(906),command(),str(uuid.uuid4()))[0]==500
        assert state()==frozen
    finally:
        sql('ALTER TABLE audit_events DROP CONSTRAINT mrm_probe_rollback;')
    # Overflow refuses creation or increments and does not change any business state.
    sql(f"INSERT INTO member_role_ledgers VALUES ('{ORG}','{ident('8080',907)}',2147483647);")
    frozen=state()
    assert admin.request(root(907),command(2147483647),str(uuid.uuid4()))[0]==409
    assert state()==frozen
    # Capture native release boundary after an observed wait on each final lock.
    # A pre-wait Instant.now would precede this boundary and fail the assertion.
    for table,column,rev,before,after in [
            ('organization_memberships','account_id',0,'MEMBER','ADMIN'),
            ('member_role_ledgers','account_id',1,'ADMIN','MEMBER')]:
        holder=Holder(f"SELECT {column} FROM {table} WHERE organization_id='{ORG}' AND account_id='{ident('8080',912)}' FOR UPDATE")
        pool=concurrent.futures.ThreadPoolExecutor(1)
        try:
            call=pool.submit(admin.request,root(912),command(rev,before,after),str(uuid.uuid4()))
            blocked_by(holder.pid,table)
            boundary=datetime.fromisoformat(sql('SELECT clock_timestamp();'))
            holder.send("COMMIT; SELECT 'FINAL_LOCK_RELEASED';")
            holder.expect_marker('FINAL_LOCK_RELEASED')
            status,envelope=call.result(timeout=25)
            assert status==201,(status,envelope)
            recorded=datetime.fromisoformat(envelope['data']['recordedAt'].replace('Z','+00:00'))
            assert recorded>=boundary,(table,recorded,boundary)
        finally:
            holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    # Two current peer ADMINs really compete while behind the same held org mutex.
    # Login both before creating the lock holder.
    peers=[Client('target-901'),Client('target-902')]
    holder=Holder(f"SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(2)
    try:
        calls=[pool.submit(peers[i].request,root(902 if i==0 else 901),command(0,'ADMIN','MEMBER'),str(uuid.uuid4())) for i in range(2)]
        blocked_by(holder.pid,'organizations',minimum=2)
        holder.send("COMMIT; SELECT 'PEERS_RELEASED';")
        holder.expect_marker('PEERS_RELEASED')
        assert sorted(call.result(timeout=25)[0] for call in calls)==[201,403]
        assert sql(f"SELECT count(*) FROM organization_memberships WHERE organization_id='{ORG}' AND account_id IN ('{ident('8080',901)}','{ident('8080',902)}') AND role='ADMIN';")=='1'
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    # Reversed actor/target across distinct org mutexes. Both begin ADMIN; each
    # demotes the other only in its own org. High actor must not hold high first.
    low,high=Client('cross-low'),Client('cross-high')
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('8080',91)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(2)
    try:
        high_call=pool.submit(high.request,root(91,CROSS),command(0,'ADMIN','MEMBER'),str(uuid.uuid4()))
        blocked_by(holder.pid,'human_accounts')
        holder.send(f"\\o /dev/null\nSET LOCAL lock_timeout='2s'; SELECT id FROM human_accounts WHERE id='{ident('8080',92)}' FOR UPDATE;\n\\o\nSELECT 'HIGH_AVAILABLE';")
        holder.expect_marker('HIGH_AVAILABLE')
        low_call=pool.submit(low.request,root(92),command(0,'ADMIN','MEMBER'),str(uuid.uuid4()))
        blocked_by(holder.pid,'human_accounts',minimum=2)
        boundary=datetime.fromisoformat(sql('SELECT clock_timestamp();'))
        holder.send("COMMIT; SELECT 'REVERSE_RELEASED';")
        holder.expect_marker('REVERSE_RELEASED')
        for call in (high_call,low_call):
            status,envelope=call.result(timeout=25)
            assert status==201,(status,envelope)
            assert datetime.fromisoformat(envelope['data']['recordedAt'].replace('Z','+00:00'))>=boundary
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    # Observe an existing ADMIN-only grant writer behind the actual role demotion.
    # Both competitor sessions are authenticated BEFORE deliberate account locking.
    demoted,writer=Client('target-904'),Client('target-904')
    decisions=f'/api/v1/organizations/{ORG}/approvals/'
    detail=demoted.data(decisions+ident('8050',903))
    assert detail['eligibility']['canApprove']
    snap=detail['snapshot']
    decision=dict(snapshotVersion=snap['version'],snapshotSha256=snap['payloadSha256'],acknowledgement=True,reason=None)
    grant_body=dict(expectedRevision=0,teamId=ident('8010',1),actionType='PUBLISH_PREVIEW',riskLevel='LOW',durationHours=1,reason='Admin writer after demotion',acknowledgement=True)
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('8080',904)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(2)
    try:
        changing=pool.submit(admin.request,root(904),command(0,'ADMIN','MEMBER'),str(uuid.uuid4()))
        changing_pid=blocked_by(holder.pid,'human_accounts')[0]
        grant_path=f'/api/v1/organizations/{ORG}/members/{ident("8080",908)}/approval-grants'
        waiting=pool.submit(writer.request,grant_path,grant_body,str(uuid.uuid4()))
        blocked_by(changing_pid,'organizations')
        holder.send("COMMIT; SELECT 'DEMOTION_RELEASED';")
        holder.expect_marker('DEMOTION_RELEASED')
        assert changing.result(timeout=25)[0]==201
        assert waiting.result(timeout=25)[0]==403
        assert sql(f"SELECT count(*) FROM approval_grant_ledgers WHERE organization_id='{ORG}' AND account_id='{ident('8080',908)}';")=='0'
        assert sql(f"SELECT count(*) FROM approval_grants WHERE organization_id='{ORG}' AND account_id='{ident('8080',908)}';")=='0'
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
    assert demoted.request(root(908)+'/context')[0]==403
    assert demoted.data('/api/v1/auth/session')['id']==ident('8080',904)
    assert demoted.data(decisions+ident('8050',903))['eligibility']['canApprove']
    assert demoted.request(decisions+ident('8050',903)+'/approve',decision,str(uuid.uuid4()))[0]==201
    # Actor-only replay must reauthorize after waiting, without touching removed target.
    # Establish native issuer authority through the supported writer before login/locks.
    change(admin,910)
    replay_client=Client('target-910')
    replay_result,replay_key=change(replay_client,909)
    sql(f"DELETE FROM organization_memberships WHERE organization_id='{ORG}' AND account_id='{ident('8080',909)}';")
    replay_frozen=state()
    holder=Holder(f"SELECT id FROM human_accounts WHERE id='{ident('8080',910)}' FOR UPDATE")
    pool=concurrent.futures.ThreadPoolExecutor(1)
    try:
        replay=pool.submit(replay_client.request,root(909),command(),replay_key)
        blocked_by(holder.pid,'human_accounts')
        holder.send(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id='{ident('8080',910)}'; COMMIT; SELECT 'ISSUER_DEMOTED';")
        holder.expect_marker('ISSUER_DEMOTED')
        assert replay.result(timeout=25)[0]==403
    finally:
        holder.close(); pool.shutdown(wait=True,cancel_futures=True)
        sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='{ORG}' AND account_id='{ident('8080',910)}';")
    assert state()==replay_frozen
    assert replay_client.data(root(909)+f'/receipts/{replay_key}')==replay_result
    assert admin.data(root(905)+f'/receipts/{key}')==result
    # Privileged native test operations above touched only native targets and issuer
    # role was restored. Browser bindings/baseline, grants, credentials and runtime stay exact.
    assert preserved()==unchanged
    expected_browser=sql(f"SELECT row_to_json(m)::text FROM organization_memberships m WHERE organization_id='{ORG}' AND account_id<'{ident('8080',900)}' AND account_id<>'{ident('8080',92)}' ORDER BY account_id;")
    original_browser='\n'.join(line for line in browser_before.splitlines() if ident('8080',92) not in line)
    assert expected_browser==original_browser
    print('Native PostgreSQL role probe passed: durable result/hash/replay/ABA/retained history, full audit rollback, peer demotion, reversed global locks, observed Admin writer denial, preserved MEMBER grant decision and no runtime mutation')


if __name__=='__main__':
    if sys.argv[1:]==['--vectors-only']:
        vectors()
    else:
        assert not sys.argv[1:]
        main()
