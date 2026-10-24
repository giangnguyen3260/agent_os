"""Independent disposable PostgreSQL + HTTP + Mailpit invitation probe.
E2E_PASSWORD required; INV_PSQL_COMMAND JSON argv optional; E2E_BASE_URL and
MAILPIT_URL default to loopback. Fails closed; never prints raw mail or tokens.
Run once after member-invitations-ci-fixture.sql, with worker enabled.
"""
import concurrent.futures as futures
import hashlib
import http.cookiejar
import json
import os
from pathlib import Path
import re
import select
import signal
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '90909090-9090-4090-8090-909090909090'
FOREIGN = '91919191-9191-4191-8191-919191919191'
BASE = os.getenv('E2E_BASE_URL', 'http://127.0.0.1:18080')
MAIL = os.getenv('E2E_MAILPIT_URL', os.getenv('MAILPIT_URL', 'http://127.0.0.1:8025'))
ROOT = Path(__file__).resolve().parents[1]
RECEIPT = set('operation organizationId invitationId idempotencyKey revision eventId actorId actorName recordedAt email role expiresAt'.split())
ITEM = set('id email role status revision createdAt expiresAt acceptedAt revokedAt deliveryStatus lastRequestedAt'.split())
START = time.monotonic()

def ident(n):
    return f'90900000-0000-4000-8000-{n:012d}'

def path(org=ORG):
    return f'/api/v1/organizations/{org}/invitations'

def sql(query, timeout=8):
    command = json.loads(os.environ['INV_PSQL_COMMAND']) if os.getenv('INV_PSQL_COMMAND') else [
        'docker','compose','exec','-T','postgres','sh','-c',
        'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
    return subprocess.check_output(command, input=query, text=True, cwd=ROOT, timeout=timeout).strip()

class Client:
    def __init__(self, who=None):
        self.http = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.data('/api/v1/auth/csrf')
        if who:
            code, _ = self.request('/api/v1/auth/login', urllib.parse.urlencode({
                'username': f'invitation-{who}@example.test', 'password': os.environ['E2E_PASSWORD']}).encode(),
                content='application/x-www-form-urlencoded')
            assert code == 200, ('login', who, code)
            self.csrf = self.data('/api/v1/auth/csrf')

    def request(self, target, body=None, key=None, csrf=True, content='application/json'):
        headers = {'Content-Type': content}
        if key: headers['Idempotency-Key'] = key
        if body is not None and csrf: headers[self.csrf['headerName']] = self.csrf['token']
        if isinstance(body, dict): body = json.dumps(body, ensure_ascii=True).encode()
        try:
            response = self.http.open(urllib.request.Request(BASE+target, body, headers), timeout=12)
        except urllib.error.HTTPError as error: response = error
        with response:
            raw = response.read().decode()
            # Never include response values in assertion diagnostics: may contain secrets.
            assert not any(x in raw for x in ['password_hash','tokenDigest','token_digest'])
            return response.status, json.loads(raw) if raw else None

    def data(self, target):
        code, envelope = self.request(target)
        assert code == 200, ('GET', target, code)
        return envelope['data']

def post(client, target, body, expected=201, key=None):
    key = key or str(uuid.uuid4())
    code, envelope = client.request(target, body, key)
    assert code == expected, ('POST', target, code, expected)
    value=envelope['data']
    if isinstance(value,dict) and set(value)==RECEIPT:
        expected_hash=fingerprint(value['operation'],
            email=value['email'] if value['operation']=='CREATE_INVITATION' else None,
            expected=body.get('expectedRevision'),
            invitation=None if value['operation']=='CREATE_INVITATION' else value['invitationId'],
            actor=value['actorId'],org=value['organizationId'])
        assert sql(f"SELECT fingerprint FROM invitation_receipts WHERE organization_id='{value['organizationId']}' AND actor_id='{value['actorId']}' AND idempotency_key='{key}';")==expected_hash
    return value, key

def create(admin, email, org=ORG):
    receipt, key = post(admin, path(org), {'email': email,'acknowledgement': True})
    assert set(receipt) == RECEIPT
    assert receipt['email'] == email.strip().lower() and receipt['role'] == 'MEMBER'
    assert receipt['revision'] == 1 and receipt['idempotencyKey'] == key
    assert admin.data(path(org)+'/receipts/'+key) == receipt
    expected=fingerprint('CREATE_INVITATION',email.strip().lower(),actor=receipt['actorId'],org=org)
    assert sql(f"SELECT fingerprint FROM invitation_receipts WHERE organization_id='{org}' AND actor_id='{receipt['actorId']}' AND idempotency_key='{key}';")==expected
    return receipt

def inspect(client, token):
    return client.request('/api/v1/auth/invitations/inspect', {'token': token})

def accept(client, token, new=False):
    return client.request('/api/v1/auth/invitations/accept', {'token': token,
        'displayName': 'Invitation Native New' if new else None,
        'password': 'Native invitation pass 2026!' if new else None})

def mail_token(email, excluded=()):
    deadline = time.monotonic()+18
    while time.monotonic()<deadline:
        with urllib.request.urlopen(MAIL+'/api/v1/search?query='+urllib.parse.quote('to:'+email), timeout=3) as r:
            messages = json.load(r).get('messages', [])
        for message in messages:
            with urllib.request.urlopen(MAIL+'/api/v1/message/'+message['ID'], timeout=3) as r:
                detail = json.load(r)
            tokens = re.findall(r'/invitation#(?:token=)?([A-Za-z0-9_-]{43})(?![A-Za-z0-9_-])', detail.get('Text','')+' '+detail.get('HTML',''))
            for token in tokens:
                if token not in excluded:
                    digest=hashlib.sha256(token.encode()).hexdigest()
                    if sql(f"SELECT count(*) FROM organization_invitations WHERE token_digest='{digest}';")=='1': return token
        time.sleep(.15)
    raise AssertionError('Isolated SMTP invitation did not arrive within 18 seconds')

def context(admin, receipt):
    value = admin.data(path(receipt['organizationId'])+'/'+receipt['invitationId']+'/context')
    assert set(value) == {'organizationId','checkedAt','invitation','canResend','canRevoke'}
    assert set(value['invitation']) == ITEM
    return value['invitation']

class Holder:
    def __init__(self, statement):
        self.name = 'invitation-probe-'+uuid.uuid4().hex
        command = json.loads(os.environ['INV_PSQL_COMMAND']) if os.getenv('INV_PSQL_COMMAND') else [
            'docker','compose','exec','-T','postgres','sh','-c',
            'psql -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
        self.process = subprocess.Popen(command,cwd=ROOT,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
        try:
            self.send(f"SET application_name='{self.name}';\n\\o /dev/null\nBEGIN; {statement};\n\\o\nSELECT 'LOCKED:'||pg_backend_pid();")
            assert select.select([self.process.stdout],[],[],8)[0], 'Holder did not acquire lock'
            marker = self.process.stdout.readline().strip()
            assert marker.startswith('LOCKED:')
            self.pid = int(marker.split(':')[1])
        except BaseException:
            self.close()
            raise
    def send(self, statement):
        self.process.stdin.write(statement+'\n'); self.process.stdin.flush()
    def marker(self, statement):
        self.send(statement)
        assert select.select([self.process.stdout],[],[],8)[0], 'Transaction marker timed out'
        return self.process.stdout.readline().strip()

    def release_bound(self):
        # Database clock immediately before COMMIT is a necessary lower bound
        # for release. Avoid Python/DB clock skew and post-COMMIT scheduling races.
        marker=self.marker("SELECT 'RELEASE:'||clock_timestamp(); COMMIT;")
        assert marker.startswith('RELEASE:')
        return marker.split('RELEASE:',1)[1]

    def close(self):
        if self.process.poll() is None:
            self.send('ROLLBACK;\n\\q')
            try: self.process.communicate(timeout=3)
            except subprocess.TimeoutExpired:
                sql(f"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='{self.name}'")
                self.process.kill(); self.process.communicate(timeout=2)

def observed(holder, relation, count=1):
    deadline = time.monotonic()+8
    while time.monotonic()<deadline:
        value = sql(f"WITH RECURSIVE e AS (SELECT pid,unnest(pg_blocking_pids(pid)) blocker FROM pg_stat_activity), c AS (SELECT * FROM e UNION SELECT c.pid,e.blocker FROM c JOIN e ON c.blocker=e.pid) SELECT count(DISTINCT a.pid) FROM c JOIN pg_stat_activity a ON a.pid=c.pid WHERE c.blocker={holder.pid} AND a.wait_event_type='Lock' AND a.query ILIKE '%{relation}%';")
        if int(value)>=count: return
    raise AssertionError('Required PostgreSQL waiter not observed: '+relation)

def snapshot(email):
    # Stable business state; excludes session access timestamps and worker delivery metadata.
    return sql(f"SELECT row_to_json(h)::text FROM human_accounts h WHERE email='{email}'; SELECT row_to_json(m)::text FROM organization_memberships m JOIN human_accounts h ON h.id=m.account_id WHERE h.email='{email}' ORDER BY organization_id; SELECT row_to_json(l)::text FROM member_role_ledgers l JOIN human_accounts h ON h.id=l.account_id WHERE h.email='{email}' ORDER BY organization_id;")

def fingerprint(operation, email=None, expected=None, invitation=None, actor=ident(1), org=ORG):
    value={'acknowledgement':True,'actorId':actor,'domain':'MEMBER_INVITATIONS',
           'email':email,'expectedRevision':expected,'invitationId':invitation,
           'operation':operation,'organizationId':org,'version':1}
    return hashlib.sha256(json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':')).encode()).hexdigest()

def vectors():
    invitation='90910000-0000-4000-8000-000000000101'
    assert fingerprint('CREATE_INVITATION','invitee@example.test')=='c651c65696a23ed4e1e2c62e195d3d34a9c42bcefb9cd19a6612d4184209d6bd'
    assert fingerprint('RESEND_INVITATION',expected=1,invitation=invitation)=='ff65bf6d78ea6d5a116bc50ac85e767281a78b60aa53af29697f48028b3e7279'
    assert fingerprint('REVOKE_INVITATION',expected=2,invitation=invitation)=='41980eca49a300e04909063913b2757609beea5e555961501c0796f7b1ef42c8'
    print('PASS three literal canonical UTF8 SHA256 vectors (local serialization only)')

def outage_command(name):
    # Explicit argv only, never a shell. CI must dedicate this SMTP service to
    # this sequential probe; no shared mailbox chaos or production switch.
    value = json.loads(os.environ[name])
    assert isinstance(value, list) and value and all(isinstance(v, str) and v for v in value), name
    return value


def mail_ready(email):
    # Observable HTTP recovery on the same recipient-search API used by
    # mail_token. This is not proof of SMTP readiness; real delivery remains
    # mandatory below. Never print the response or exception (URL has email).
    end = time.monotonic()+8
    while time.monotonic() < end:
        try:
            with urllib.request.urlopen(MAIL+'/api/v1/search?query='+urllib.parse.quote('to:'+email), timeout=2) as response:
                value = json.load(response)
                if response.status == 200 and isinstance(value, dict) and isinstance(value.get('messages'), list):
                    return
        except (urllib.error.URLError, TimeoutError, ValueError, OSError):
            pass
        time.sleep(.1)
    raise AssertionError('Mailpit HTTP readiness not observed within 8s')

def replacement_job_diagnostic(invitation):
    # Only the expected generation's enum and bounded counter can be emitted.
    # No row dumps, invitation identifiers, addresses, capabilities or errors.
    try:
        row = sql(f"SELECT state||'|'||attempts FROM invitation_mail_jobs WHERE invitation_id='{invitation}' AND generation=3;")
        parts = row.split('|')
        if len(parts) == 2 and parts[0] in ('QUEUED','SENT','FAILED','CANCELLED') and parts[1] in ('0','1','2','3','4','5'):
            print('Replacement SMTP job generation=3 state='+parts[0]+' attempts='+parts[1], flush=True)
            return
    except Exception:
        pass
    print('Replacement SMTP job diagnostic unavailable', flush=True)

def smtp_outage(admin, anonymous):
    stop = outage_command('INV_SMTP_STOP_COMMAND')
    start = outage_command('INV_SMTP_START_COMMAND')
    r = create(admin, 'invitation-native-smtp-outage@example.test')
    old = mail_token(r['email'])
    digest = hashlib.sha256(old.encode()).hexdigest()
    target = path()+'/'+r['invitationId']
    expiry = sql(f"SELECT expires_at FROM organization_invitations WHERE id='{r['invitationId']}';")
    sql(f"UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='{r['invitationId']}';")
    try:
        subprocess.run(stop, cwd=ROOT, check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        renewed, _ = post(admin, target+'/resend', {'expectedRevision':1,'acknowledgement':True})
        assert renewed['expiresAt'] == r['expiresAt']
        end = time.monotonic()+45
        seen_retry = False
        while True:
            row = sql(f"SELECT state||'|'||attempts FROM invitation_mail_jobs WHERE invitation_id='{r['invitationId']}' AND generation=2;")
            state, attempts = row.split('|'); attempts = int(attempts)
            assert 0 <= attempts <= 5
            assert sql(f"SELECT token_digest FROM organization_invitations WHERE id='{r['invitationId']}';") == digest
            assert sql(f"SELECT expires_at FROM organization_invitations WHERE id='{r['invitationId']}';") == expiry
            if state == 'QUEUED' and attempts > 0:
                seen_retry = True
                assert attempts < 5 and inspect(anonymous, old)[0] == 200
            if state == 'FAILED':
                assert attempts == 5 and seen_retry, 'Must observe actual retry before exhaustion'
                break
            assert state == 'QUEUED' and time.monotonic() < end, 'SMTP exhaustion not observed within 45s; configure CI retry delay 1s'
            time.sleep(.1)
        assert context(admin, r)['deliveryStatus'] == 'FAILED'
        assert inspect(anonymous, old)[0] == 200
    finally:
        # Attempt recovery even if stopping failed halfway or any oracle fails.
        subprocess.run(start, cwd=ROOT, check=True, timeout=10, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    mail_ready(r['email'])
    # FAILED is terminal: recovery alone must not schedule a sixth attempt.
    time.sleep(2)
    assert sql(f"SELECT state||'|'||attempts FROM invitation_mail_jobs WHERE invitation_id='{r['invitationId']}' AND generation=2;") == 'FAILED|5'
    assert inspect(anonymous, old)[0] == 200
    sql(f"UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='{r['invitationId']}';")
    renewed, _ = post(admin, target+'/resend', {'expectedRevision':2,'acknowledgement':True})
    assert renewed['expiresAt'] == r['expiresAt']
    try:
        replacement = mail_token(r['email'], (old,))
    except Exception:
        replacement_job_diagnostic(r['invitationId'])
        raise
    assert inspect(anonymous, old)[0] == 400 and inspect(anonymous, replacement)[0] == 200
    assert sql(f"SELECT state||'|'||attempts FROM invitation_mail_jobs WHERE invitation_id='{r['invitationId']}' AND generation=2;") == 'FAILED|5'
    assert sql(f"SELECT expires_at FROM organization_invitations WHERE id='{r['invitationId']}';") == expiry
    print('PASS real SMTP outage, observed retry, five-attempt exhaustion, old digest/fixed expiry, explicit resend recovery')

def main():
    vectors()
    # Fail before any mutation when exclusive SMTP lifecycle controls are absent.
    outage_command('INV_SMTP_STOP_COMMAND'); outage_command('INV_SMTP_START_COMMAND')
    assert sql('SHOW server_version_num;').isdigit(), 'Actual PostgreSQL required'
    admin, member, anonymous = Client('admin'), Client('member'), Client()
    assert anonymous.request(path())[0] == 401
    assert member.request(path())[0] == 403
    assert admin.request(path(), {'email':'invitation-wire@example.test','acknowledgement':True}, str(uuid.uuid4()), csrf=False)[0] == 403
    for suffix in ['?status=BOGUS','?page=-1','?page=0&page=1','?extra=1']:
        assert admin.request(path()+suffix)[0] == 400
    for body in [{'email':'invalid','acknowledgement':True}, {'email':'invitation-wire@example.test','acknowledgement':False},
                 {'email':'invitation-wire@example.test','acknowledgement':True,'role':'ADMIN'},
                 b'{"email":"invitation-wire@example.test","email":"invitation-other@example.test","acknowledgement":true}']:
        assert admin.request(path(), body, str(uuid.uuid4()))[0] == 400
    for invalid in ['.bad@example.test','bad.@example.test','bad..dot@example.test',
                    'a,b@example.test','a;b@example.test','a<b@example.test','a>b@example.test',
                    'a:b@example.test','"a"@example.test','a\\b@example.test','a[b]@example.test',
                    'x'*65+'@example.test','a@'+'x'*64+'.test','a@-bad.test','a@bad-.test',
                    'a@bad..test','é@example.test','a@example.test,other@example.test']:
        assert admin.request(path(),{'email':invalid,'acknowledgement':True},str(uuid.uuid4()))[0]==400
    normalized=create(admin,'  INVITATION-NATIVE-NORMALIZED@EXAMPLE.TEST  ')
    assert normalized['email']=='invitation-native-normalized@example.test'
    assert inspect(anonymous, 'A'*43)[0] == 400
    r = create(admin,'invitation-native-new@example.test')
    target = path()+'/'+r['invitationId']
    assert admin.request(path(FOREIGN)+'/'+r['invitationId']+'/context')[0] == 404
    assert post(admin,path(),{'email':r['email'],'acknowledgement':True},200,r['idempotencyKey'])[0] == r
    assert admin.request(path(),{'email':r['email'],'acknowledgement':True},str(uuid.uuid4()))[0] == 409
    token = mail_token(r['email'])
    digest = hashlib.sha256(token.encode()).hexdigest()
    assert sql(f"SELECT token_digest FROM organization_invitations WHERE id='{r['invitationId']}';") == digest
    # Every invitation persistence row is examined, never output: capability must occur nowhere.
    for table in ['organization_invitations','invitation_mail_jobs','invitation_events','invitation_receipts','invitation_email_locks','audit_events']:
        assert token not in sql(f'SELECT row_to_json(x)::text FROM {table} x;')
    code, envelope = inspect(anonymous,token)
    assert code == 200 and envelope['data']['state']=='NEW_ACCOUNT'
    assert set(envelope['data']) == set('state organizationId organizationName emailHint role expiresAt'.split())
    assert r['email'] not in json.dumps(envelope)
    assert anonymous.request('/api/v1/auth/invitations/inspect',{'token':token},csrf=False)[0]==403
    assert anonymous.request('/api/v1/auth/invitations/accept',{'token':token,'displayName':'Valid Name','password':'Valid password 2026!'},csrf=False)[0]==403
    # Reject code-point/UTF8/control boundaries without consuming the capability.
    for name,password in [('', 'Valid password 2026!'),('x'*256,'Valid password 2026!'),
                          ('bad\x7fname','Valid password 2026!'),('bad\ud800name','Valid password 2026!'),
                          ('Valid Name','x'*13),('Valid Name','😀'*19),('Valid Name','x'*73)]:
        code,_=anonymous.request('/api/v1/auth/invitations/accept',{'token':token,'displayName':name,'password':password})
        assert code==400
        assert inspect(anonymous,token)[0]==200
    before = context(admin,r)
    assert before['deliveryStatus']=='SENT'
    assert admin.request(target+'/resend',{'expectedRevision':1,'acknowledgement':True},str(uuid.uuid4()))[0] == 429
    assert accept(anonymous,token,True)[0] == 200
    assert accept(anonymous,token,True)[0] == 400
    assert context(admin,r)['status']=='ACCEPTED'
    assert sql("SELECT count(*) FROM human_accounts WHERE email='invitation-native-new@example.test';") == '1'
    assert sql(f"SELECT revision FROM member_role_ledgers WHERE organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='invitation-native-new@example.test');") == '1'
    print('PASS wire, authority, exact receipt replay, SMTP new-account, digest-only, one-time join')

    for n, expected in [(905,200),(906,409)]:
        email=f'invitation-existing-{n}@example.test'
        r=create(admin,email); token=mail_token(email); recipient=Client('existing-'+str(n))
        assert inspect(anonymous,token)[1]['data']['state']=='SIGN_IN'
        assert inspect(admin,token)[1]['data']['state']=='MISMATCH'
        assert accept(admin,token)[0]==403
        assert accept(anonymous,token)[0]==401
        frozen=snapshot(email)
        assert accept(recipient,token)[0]==expected
        if expected==409: assert snapshot(email)==frozen
        else:
            assert sql(f"SELECT revision FROM member_role_ledgers WHERE organization_id='{ORG}' AND account_id='{ident(n)}';")=='8'
            assert sql(f"SELECT row_to_json(h)::text FROM human_accounts h WHERE id='{ident(n)}';")==frozen.splitlines()[0]
    # Preserve existing ADMIN and ledger, including all credentials and foreign memberships.
    r=create(admin,'invitation-existing-901@example.test'); token=mail_token(r['email']); recipient=Client('existing-901')
    frozen=snapshot(r['email']); code,env=accept(recipient,token)
    assert code==200 and env['data']=={'organizationId':ORG,'role':'ADMIN','requiresLogin':False,'alreadyMember':True}
    assert snapshot(r['email'])==frozen
    print('PASS real SMTP existing-account, mismatch, no overwrite, retained ledger join and overflow')

    r=create(admin,'invitation-disabled@example.test'); token=mail_token(r['email'])
    for client in (Client(),admin):
        code,envelope=inspect(client,token)
        assert code==400 and envelope['error']['code']=='INVALID_INVITATION'
        code,envelope=accept(client,token,True)
        assert code==400 and envelope['error']['code']=='INVALID_INVITATION'
    assert sql("SELECT count(*) FROM human_accounts WHERE email='invitation-disabled@example.test';")=='1'
    print('PASS disabled recipient generic denial regardless of caller, no recreation')

    # Organization mutex: observe HTTP waiter, then let exactly one same-email create win.
    holder=Holder(f"SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(2) as pool:
            jobs=[pool.submit(Client('existing-'+str(n)).request,path(),{'email':'invitation-native-race@example.test','acknowledgement':True},str(uuid.uuid4())) for n in (901,902)]
            observed(holder,'organizations',2); holder.close()
            assert sorted(j.result()[0] for j in jobs)==[201,409]
    finally: holder.close()
    # Revoke versus acceptance: shared org lock forces overlap; only one terminal result.
    r=create(admin,'invitation-native-revoke-race@example.test'); token=mail_token(r['email'])
    holder=Holder(f"SELECT id FROM organizations WHERE id='{ORG}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(2) as pool:
            a=pool.submit(accept,Client(),token,True)
            b=pool.submit(Client('existing-901').request,path()+'/'+r['invitationId']+'/revoke',{'expectedRevision':1,'acknowledgement':True},str(uuid.uuid4()))
            observed(holder,'organizations',2); holder.close(); codes=(a.result()[0],b.result()[0])
            assert codes in [(200,409),(400,201)], codes
    finally: holder.close()
    print('PASS observed PostgreSQL org lock and create/revoke-accept races')
    # Global email mutex across organizations: both creators resolve absence only
    # after the shared digest lock. Tokens come from real independent SMTP jobs.
    email='invitation-native-global@example.test'
    left=create(admin,email); right=create(admin,email,FOREIGN)
    first=mail_token(email)
    second=mail_token(email,(first,))
    # Identify each capability without exposing either value.
    tokens={inspect(Client(),t)[1]['data']['organizationId']:t for t in (first,second)}
    email_hash=hashlib.sha256(email.encode()).hexdigest()
    sql(f"INSERT INTO invitation_email_locks(email_digest) VALUES ('{email_hash}') ON CONFLICT DO NOTHING;")
    holder=Holder(f"SELECT email_digest FROM invitation_email_locks WHERE email_digest='{email_hash}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(2) as pool:
            jobs=[pool.submit(accept,Client(),tokens[o],True) for o in (ORG,FOREIGN)]
            observed(holder,'invitation_email_locks',2); holder.close()
            codes=sorted(j.result()[0] for j in jobs)
            assert codes==[200,401], codes
    finally: holder.close()
    assert sql(f"SELECT count(*) FROM human_accounts WHERE email='{email}';")=='1'
    print('PASS observed global email mutex and same-email cross-org creation')

    # Sorted global human locks: issuer 902 > recipient 901; lower UUID must
    # block first. The higher holder must see no waiter until the lower releases.
    high_admin=Client('existing-902')
    r=create(high_admin,'invitation-existing-901@example.test',FOREIGN)
    token=mail_token(r['email'])
    sorted_recipient=Client('existing-901')
    low=Holder(f"SELECT id FROM human_accounts WHERE id='{ident(901)}' FOR UPDATE")
    high=Holder(f"SELECT id FROM human_accounts WHERE id='{ident(902)}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(1) as pool:
            job=pool.submit(accept,sorted_recipient,token)
            observed(low,'human_accounts')
            assert sql(f"SELECT count(*) FROM pg_stat_activity WHERE {high.pid}=ANY(pg_blocking_pids(pid));")=='0'
            low.close(); observed(high,'human_accounts'); high.close()
            assert job.result()[0]==200
    finally: low.close(); high.close()
    print('PASS observed ascending global human lock order across reversed issuer/recipient')

    # Audit failures roll back account, join, capability consumption, revision and
    # restricted event together; NOT VALID leaves existing events untouched.
    r=create(admin,'invitation-native-audit@example.test'); token=mail_token(r['email'])
    before=context(admin,r)
    sql(f"ALTER TABLE audit_events ADD CONSTRAINT invitation_probe_audit CHECK (organization_id<>'{ORG}' OR event_type<>'ACCEPT_INVITATION') NOT VALID;")
    try:
        assert accept(Client(),token,True)[0]>=500
        assert context(admin,r)==before
        assert sql("SELECT count(*) FROM human_accounts WHERE email='invitation-native-audit@example.test';")=='0'
        assert sql(f"SELECT count(*) FROM invitation_events WHERE invitation_id='{r['invitationId']}' AND operation='ACCEPT_INVITATION';")=='0'
        assert inspect(Client(),token)[0]==200
    finally: sql('ALTER TABLE audit_events DROP CONSTRAINT invitation_probe_audit;')
    assert accept(Client(),token,True)[0]==200
    # Management rollback independently proves no orphan invitation/receipt/job.
    sql(f"ALTER TABLE audit_events ADD CONSTRAINT invitation_probe_audit CHECK (organization_id<>'{ORG}' OR event_type<>'CREATE_INVITATION') NOT VALID;")
    key=str(uuid.uuid4())
    try:
        assert admin.request(path(),{'email':'invitation-native-audit-create@example.test','acknowledgement':True},key)[0]>=500
        assert admin.data(path()+'/receipts/'+key) is None
        assert sql("SELECT count(*) FROM organization_invitations WHERE email='invitation-native-audit-create@example.test';")=='0'
    finally: sql('ALTER TABLE audit_events DROP CONSTRAINT invitation_probe_audit;')
    print('PASS acceptance and management safe-audit atomic rollback')

    # Critical stale-candidate issuer barrier. B's real resend takes org before
    # waiting on B human; accept reads old digest/issuer then demonstrably waits
    # on that org. After resend commits, accept must lock latest B, not old A.
    r=create(admin,'invitation-existing-907@example.test'); token=mail_token(r['email'])
    sql(f"INSERT INTO member_role_ledgers(organization_id,account_id,revision) VALUES ('{ORG}','{ident(907)}',11); UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='{r['invitationId']}';")
    # Defer replacement SMTP so acceptance continues with the old delivered
    # capability explicitly reauthorized by B. Trigger scope is this ID only.
    sql(f"CREATE FUNCTION invitation_probe_defer() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.invitation_id='{r['invitationId']}'::uuid THEN NEW.available_at=current_timestamp+interval '1 hour'; END IF; RETURN NEW; END $$; CREATE TRIGGER invitation_probe_defer BEFORE INSERT ON invitation_mail_jobs FOR EACH ROW EXECUTE FUNCTION invitation_probe_defer();")
    recipient907=Client('existing-907'); admin_b=Client('existing-902')
    b=Holder(f"SELECT id FROM human_accounts WHERE id='{ident(902)}' FOR UPDATE")
    ledger=Holder(f"SELECT revision FROM member_role_ledgers WHERE organization_id='{ORG}' AND account_id='{ident(907)}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(2) as pool:
            resend=pool.submit(admin_b.request,path()+'/'+r['invitationId']+'/resend',{'expectedRevision':1,'acknowledgement':True},str(uuid.uuid4()))
            observed(b,'human_accounts')
            accepting=pool.submit(accept,recipient907,token)
            observed(b,'organizations')
            b.close(); assert resend.result()[0]==201
            observed(ledger,'member_role_ledgers')
            # NOWAIT probes acquire no business changes. They establish B is
            # locked while A is free, rather than inferring a lock from outcomes.
            sql(f"DO $$ BEGIN BEGIN PERFORM id FROM human_accounts WHERE id='{ident(902)}' FOR UPDATE NOWAIT; RAISE EXCEPTION 'latest issuer human was not locked'; EXCEPTION WHEN lock_not_available THEN NULL; END; END $$;")
            sql(f"BEGIN; SELECT id FROM human_accounts WHERE id='{ident(1)}' FOR UPDATE NOWAIT; ROLLBACK;")
            released=ledger.release_bound()
            assert accepting.result()[0]==200
            assert sql(f"SELECT recorded_at>='{released}'::timestamptz FROM invitation_events WHERE invitation_id='{r['invitationId']}' AND operation='ACCEPT_INVITATION';")=='t'
            assert sql(f"SELECT revision FROM member_role_ledgers WHERE organization_id='{ORG}' AND account_id='{ident(907)}';")=='12'
    finally:
        b.close(); ledger.close()
        sql('DROP TRIGGER invitation_probe_defer ON invitation_mail_jobs; DROP FUNCTION invitation_probe_defer();')
    print('PASS observed old candidate org wait, real B resend, latest issuer locks and final ledger-time record')

    # Natural expiration during final retained-ledger wait. Expiry is installed
    # before HTTP acceptance starts; no locked business row is changed afterward.
    r=create(admin,'invitation-existing-908@example.test'); token=mail_token(r['email'])
    recipient908=Client('existing-908')
    sql(f"INSERT INTO member_role_ledgers(organization_id,account_id,revision) VALUES ('{ORG}','{ident(908)}',17); UPDATE organization_invitations SET expires_at=clock_timestamp()+interval '5 seconds' WHERE id='{r['invitationId']}';")
    before=snapshot(r['email'])
    ledger=Holder(f"SELECT revision FROM member_role_ledgers WHERE organization_id='{ORG}' AND account_id='{ident(908)}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(1) as pool:
            accepting=pool.submit(accept,recipient908,token)
            observed(ledger,'member_role_ledgers')
            end=time.monotonic()+9
            while sql(f"SELECT expires_at<=clock_timestamp() FROM organization_invitations WHERE id='{r['invitationId']}';")!='t':
                assert time.monotonic()<end, 'Database expiry clock did not advance'
                time.sleep(.05)
            ledger.close(); code,envelope=accepting.result()
            assert code==400 and envelope['error']['code']=='INVALID_INVITATION'
    finally: ledger.close()
    assert snapshot(r['email'])==before
    assert sql(f"SELECT count(*) FROM invitation_events WHERE invitation_id='{r['invitationId']}' AND operation='ACCEPT_INVITATION';")=='0'
    assert context(admin,r)['status']=='EXPIRED'
    print('PASS observed retained-ledger wait expires naturally, generic rejection and no join')

    # Management must check time after its invitation row lock too. No change
    # to expires_at is made while the writer holds org/human locks.
    r=create(admin,'invitation-native-management-wait@example.test'); mail_token(r['email'])
    sql(f"UPDATE organization_invitations SET expires_at=clock_timestamp()+interval '5 seconds' WHERE id='{r['invitationId']}';")
    invitation=Holder(f"SELECT id FROM organization_invitations WHERE id='{r['invitationId']}' FOR UPDATE")
    try:
        with futures.ThreadPoolExecutor(1) as pool:
            revoking=pool.submit(admin.request,path()+'/'+r['invitationId']+'/revoke',{'expectedRevision':1,'acknowledgement':True},str(uuid.uuid4()))
            observed(invitation,'organization_invitations')
            end=time.monotonic()+9
            while sql(f"SELECT expires_at<=clock_timestamp() FROM organization_invitations WHERE id='{r['invitationId']}';")!='t':
                assert time.monotonic()<end
                time.sleep(.05)
            invitation.close(); assert revoking.result()[0]==409
    finally: invitation.close()
    assert sql(f"SELECT count(*) FROM invitation_events WHERE invitation_id='{r['invitationId']}' AND operation='REVOKE_INVITATION';")=='0'
    print('PASS management expiry rechecked after observed invitation row wait')

    # Invitation revision overflow differs from role-ledger overflow and must
    # roll back even the newly generated human and password hash.
    r=create(admin,'invitation-native-revision-max@example.test'); token=mail_token(r['email'])
    sql(f"UPDATE organization_invitations SET revision=2147483647 WHERE id='{r['invitationId']}';")
    assert accept(Client(),token,True)[0]==409
    assert sql("SELECT count(*) FROM human_accounts WHERE email='invitation-native-revision-max@example.test';")=='0'
    assert inspect(Client(),token)[0]==200
    assert sql(f"SELECT count(*) FROM invitation_events WHERE invitation_id='{r['invitationId']}' AND operation='ACCEPT_INVITATION';")=='0'
    print('PASS invitation revision MAX conflicts with account/join/event rollback')

    # Delay only this native recipient's next queue in PostgreSQL: prove resend
    # itself retains the delivered digest, independent of worker scheduling speed.
    r=create(admin,'invitation-native-oldlink@example.test'); old=mail_token(r['email'])
    sql(f"UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='{r['invitationId']}';")
    sql(f"CREATE FUNCTION invitation_probe_defer() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.invitation_id='{r['invitationId']}'::uuid THEN NEW.available_at=current_timestamp+interval '1 hour'; END IF; RETURN NEW; END $$; CREATE TRIGGER invitation_probe_defer BEFORE INSERT ON invitation_mail_jobs FOR EACH ROW EXECUTE FUNCTION invitation_probe_defer();")
    try:
        renewed,_=post(admin,path()+'/'+r['invitationId']+'/resend',{'expectedRevision':1,'acknowledgement':True})
        assert context(admin,r)['deliveryStatus']=='QUEUED'
        assert inspect(Client(),old)[0]==200
        assert renewed['expiresAt']==r['expiresAt']
    finally:
        sql('DROP TRIGGER invitation_probe_defer ON invitation_mail_jobs; DROP FUNCTION invitation_probe_defer();')
    sql(f"UPDATE invitation_mail_jobs SET available_at=current_timestamp WHERE invitation_id='{r['invitationId']}' AND state='QUEUED';")
    replacement=mail_token(r['email'],(old,))
    assert inspect(Client(),old)[0]==400 and inspect(Client(),replacement)[0]==200
    print('PASS queued resend preserves delivered capability until replacement SMTP ACK')

    # Issuer loss invalidates inspect and accept; another ADMIN explicitly
    # reauthorizes the pending invitation without extending its original expiry.
    r=create(high_admin,'invitation-native-issuer@example.test'); token=mail_token(r['email'])
    sql(f"UPDATE organization_memberships SET role='MEMBER' WHERE organization_id='{ORG}' AND account_id='{ident(902)}';")
    try:
        assert inspect(Client(),token)[0]==400
        assert accept(Client(),token,True)[0]==400
        sql(f"UPDATE organization_invitations SET last_requested_at=last_requested_at-interval '61 seconds' WHERE id='{r['invitationId']}';")
        renewed,_=post(admin,path()+'/'+r['invitationId']+'/resend',{'expectedRevision':1,'acknowledgement':True})
        assert renewed['expiresAt']==r['expiresAt']
        replacement=mail_token(r['email'],(token,))
        assert inspect(Client(),replacement)[0]==200
        assert inspect(Client(),token)[0]==400
        revoked,key=post(admin,path()+'/'+r['invitationId']+'/revoke',{'expectedRevision':2,'acknowledgement':True})
        assert revoked['revision']==3
        assert inspect(Client(),replacement)[0]==400
        assert admin.data(path()+'/receipts/'+renewed['idempotencyKey'])==renewed
        assert admin.data(path()+'/receipts/'+str(uuid.uuid4())) is None
        assert Client('existing-901').data(path()+'/receipts/'+key) is None
    finally:
        sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE organization_id='{ORG}' AND account_id='{ident(902)}';")
    print('PASS issuer loss, explicit resend reauthorization, fixed expiry, revoke and historical receipts')
    smtp_outage(admin, anonymous)
    assert time.monotonic()-START<295
    print('Native invitation probe completed; additional gates listed in QC review must also pass.')

if __name__=='__main__':
    def deadline(signum, frame):
        # Leave four seconds for finally cleanup, then enforce <5 minute process
        # lifetime even if an executor is unwinding an in-flight HTTP request.
        signal.signal(signal.SIGALRM,lambda *_: os._exit(124))
        signal.alarm(4)
        raise TimeoutError('Native probe exceeded 295-second deadline')
    signal.signal(signal.SIGALRM,deadline); signal.alarm(295)
    if '--vectors-only' in __import__('sys').argv: vectors()
    else: main()
