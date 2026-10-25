"""Real HTTP/PostgreSQL acceptance; never import to execute. CI applies fixture first.
Bounded to 299 seconds; no mail, mocked HTTP, seed credentials or synthetic PASS.
Fingerprint oracle is server opaque: external assigned Agent updated_at/config revision
and Team updated_at must invalidate it without changing settings revision.
"""
import concurrent.futures
import http.cookiejar
import json
import os
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '50600000-0000-4000-8000-000000000001'
TEAM = '50610000-0000-4000-8000-000000000101'
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/settings'
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql(query):
    return subprocess.check_output(PSQL, input=query, text=True, timeout=15).strip()


class Client:
    def __init__(self, kind='admin'):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        h = {body['data']['headerName']: body['data']['token'], 'Content-Type': 'application/x-www-form-urlencoded'}
        assert self.request('/api/v1/auth/login', urllib.parse.urlencode({
            'username': f'settings-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
        }).encode(), h)[0] == 200
        status, body = self.request('/api/v1/auth/csrf')
        assert status == 200
        self.headers = {body['data']['headerName']: body['data']['token'], 'Content-Type': 'application/json'}

    def request(self, path, body=None, headers=None, method=None):
        try:
            with self.opener.open(urllib.request.Request(BASE + path, data=body, headers=headers or {}, method=method), timeout=20) as r:
                return r.status, json.load(r)
        except urllib.error.HTTPError as e:
            return e.code, json.load(e)

    def context(self, path=ROOT):
        status, body = self.request(path)
        assert status == 200, (status, body)
        return body['data']

    def save(self, body, key=None, path=ROOT):
        return self.request(path, json.dumps(body).encode(), dict(self.headers, **{'Idempotency-Key': key or str(uuid.uuid4())}))

    def create(self, path, body, key):
        return self.request(path, json.dumps(body).encode(),
                            dict(self.headers, **{'Idempotency-Key': key}))


def command(context, **changes):
    body = {k: context['team'][k] for k in ('name', 'purpose', 'description', 'managerAgentId', 'agentIds', 'intake', 'concurrency', 'monthlyBudget')}
    body.update(expectedRevision=context['revision'], expectedFingerprint=context['fingerprint'], acknowledgement=True)
    body.update(changes)
    return body


def error(result, status, code):
    assert result[0] == status, result
    assert result[1]['error']['code'] == code, result


def race(clients, attempts):
    barrier = threading.Barrier(len(attempts))
    def run(pair):
        client, (body, key, path) = pair
        barrier.wait(timeout=5)
        return client.save(body, key, path)
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(attempts)) as pool:
        return list(pool.map(run, zip(clients, attempts)))


HISTORY_TABLES = ('agent_draft_config', 'agent_configuration_versions', 'agent_configuration_receipts',
                  'agent_create_receipts', 'team_create_receipts', 'approval_grants', 'approval_grant_ledgers')


def history_rows():
    # One psql round trip, full original row text (including numeric scale).
    entries = []
    for table in HISTORY_TABLES:
        entries.append(f"'{table}',(SELECT coalesce(json_agg(row_to_json(x)::text ORDER BY row_to_json(x)::text),'[]') FROM {table} x)")
    return json.loads(sql('SELECT json_build_object(' + ','.join(entries) + ');'))


def snapshot():
    # Full immutable history/grants, not count-only assertions. Sessions naturally
    # update last-access; compare principal, session identity and credential version.
    identity = sql("SELECT id,enabled,credential_version FROM human_accounts WHERE email='settings-native-admin@example.test'; SELECT primary_id,session_id,principal_name FROM spring_session WHERE principal_name='settings-native-admin@example.test' ORDER BY primary_id;")
    return {'history': history_rows(), 'identity': identity}


def assert_history_delta(before, after, allowed):
    """Account explicitly for external creates; never discard an old-row change."""
    added = {}
    for table in HISTORY_TABLES:
        old, new = before[table], after[table]
        assert set(old) <= set(new), f'External writer changed existing {table} history'
        additions = sorted(set(new) - set(old))
        assert len(additions) == allowed.get(table, 0), f'Unexpected {table} history delta'
        added[table] = [json.loads(row) for row in additions]
    return added


def org_rows(table):
    assert table in ('teams', 'agents', 'team_settings_ledgers', 'team_settings_events',
                     'team_settings_receipts', 'audit_events')
    return json.loads(sql(f"SELECT coalesce(json_agg(row_to_json(x) ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE organization_id='{ORG}';"))


def journal():
    tables = ('team_settings_ledgers', 'team_settings_events', 'team_settings_receipts', 'audit_events')
    entries = [f"'{table}',(SELECT coalesce(json_agg(row_to_json(x)::text ORDER BY row_to_json(x)::text),'[]') FROM {table} x WHERE organization_id='{ORG}')" for table in tables]
    return json.loads(sql('SELECT json_build_object(' + ','.join(entries) + ');'))


def wait_for_lock(holder_pid=None, minimum=1, table='organizations'):
    assert table in ('organizations', 'agents')
    deadline = time.monotonic() + 8
    while time.monotonic() < deadline:
        # Follow the actual holder's blocking graph, including queued row waiters.
        graph = '' if holder_pid is None else f"""WITH RECURSIVE blocked(pid) AS (
SELECT pid FROM pg_stat_activity WHERE {int(holder_pid)}=ANY(pg_blocking_pids(pid))
UNION SELECT a.pid FROM pg_stat_activity a JOIN blocked b ON b.pid=ANY(pg_blocking_pids(a.pid))) """
        scope = '' if holder_pid is None else ' AND pid IN (SELECT pid FROM blocked)'
        waiting = sql(graph + f"SELECT pid FROM pg_stat_activity WHERE wait_event_type='Lock' AND query ILIKE '%{table}%' AND pid<>pg_backend_pid(){scope} ORDER BY pid;")
        pids = [int(pid) for pid in waiting.splitlines() if pid]
        if len(pids) >= minimum:
            return pids
        time.sleep(.05)
    raise AssertionError(f'Expected {minimum} HTTP writer(s) waiting behind {table} holder')


def held_row(table, row_id, action):
    assert table in ('organizations', 'agents')
    # Persistent psql transaction, explicit handshake, no timing-only race claim.
    proc = subprocess.Popen(PSQL, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, text=True)
    try:
        proc.stdin.write(f"BEGIN; SELECT id FROM {table} WHERE id='{row_id}' FOR UPDATE; SELECT pg_backend_pid();\n")
        proc.stdin.flush()
        assert proc.stdout.readline().strip() == row_id
        proc.qc_backend_pid = int(proc.stdout.readline().strip())
        return action(proc)
    finally:
        if proc.poll() is None:
            proc.stdin.write('ROLLBACK;\n' + chr(92) + 'q\n')
            proc.stdin.flush()
        proc.communicate(timeout=10)


def held_org(action):
    return held_row('organizations', ORG, action)


def release_holder(proc, mutation=''):
    proc.stdin.write(mutation + ' COMMIT;\n')
    proc.stdin.flush()


def agent_nowait(agent_id, blocked):
    result = subprocess.run(PSQL, input='\\set VERBOSITY sqlstate\n' +
        f"BEGIN; SELECT id FROM agents WHERE id='{agent_id}' FOR UPDATE NOWAIT; ROLLBACK;\n",
        text=True, capture_output=True, timeout=5)
    if blocked:
        assert result.returncode != 0 and '55P03' in result.stderr, 'Expected writer-held Agent lock'
    else:
        assert result.returncode == 0 and result.stdout.strip() == agent_id, 'Writer locked a later Agent before the first'


def state_snapshot():
    return sql(f"""SELECT row_to_json(x) FROM teams x WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(x) FROM agents x WHERE organization_id='{ORG}' ORDER BY id;
SELECT row_to_json(x) FROM team_settings_ledgers x WHERE organization_id='{ORG}' ORDER BY team_id;
SELECT row_to_json(x) FROM team_settings_events x WHERE organization_id='{ORG}' ORDER BY team_id,revision;
SELECT row_to_json(x) FROM team_settings_receipts x WHERE organization_id='{ORG}' ORDER BY idempotency_key;
SELECT row_to_json(x) FROM audit_events x WHERE organization_id='{ORG}' ORDER BY id;""")


def team_create_claim(a, b):
    """NewTeamDraft and settings compete for the SAME existing unassigned Agent."""
    shared, create_only, settings_only = [f'50620000-0000-4000-8000-{n:012d}' for n in (1010, 1011, 1012)]
    before_agents = {row['id']: row for row in org_rows('agents')}
    assert all(before_agents[agent]['team_id'] is None for agent in (shared, create_only, settings_only))
    before_teams = {row['id']: row for row in org_rows('teams')}
    before_journal, before_history = journal(), snapshot()
    reviewed = a.context()
    settings_key, create_key = str(uuid.uuid4()), str(uuid.uuid4())
    settings_body = command(reviewed, agentIds=sorted(reviewed['team']['agentIds'] + [shared, settings_only]))
    create_body = {'name': 'Native competing NewTeamDraft', 'purpose': 'Atomic existing Agent claim',
                   'description': None, 'managerAgentId': None, 'agentIds': [shared, create_only],
                   'intake': 'MANUAL', 'concurrency': 2, 'monthlyBudget': 15}
    barrier = threading.Barrier(2)

    def settings():
        barrier.wait(timeout=5)
        return a.save(settings_body, settings_key)

    def create():
        barrier.wait(timeout=5)
        return b.create(f'/api/v1/organizations/{ORG}/teams', create_body, create_key)

    def compete(proc):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(settings), pool.submit(create)]
            try:
                wait_for_lock(proc.qc_backend_pid, minimum=2)
            finally:
                release_holder(proc)
            return [future.result(timeout=22) for future in futures]

    settings_result, create_result = held_org(compete)
    assert sorted([settings_result[0], create_result[0]]) == [201, 409], (settings_result, create_result)
    settings_won = settings_result[0] == 201
    if settings_won:
        error(create_result, 409, 'TEAM_SETUP_CONFLICT')
        winning_team = TEAM
    else:
        error(settings_result, 409, 'TEAM_SETTINGS_BLOCKED')
        winning_team = create_result[1]['data']['id']
        assert winning_team not in before_teams
    after_agents = {row['id']: row for row in org_rows('agents')}
    assert set(after_agents) == set(before_agents), 'Claim race must not create/delete Agents'
    moved = {shared, settings_only if settings_won else create_only}
    for agent_id, old in before_agents.items():
        new = after_agents[agent_id]
        if agent_id in moved:
            assert new['team_id'] == winning_team
            assert {k: v for k, v in new.items() if k not in ('team_id', 'updated_at')} == {
                k: v for k, v in old.items() if k not in ('team_id', 'updated_at')}
        else:
            assert new == old, 'Loser partially claimed or changed an Agent'
    after_teams = {row['id']: row for row in org_rows('teams')}
    assert set(after_teams) - set(before_teams) == (set() if settings_won else {winning_team})
    assert set(before_teams) <= set(after_teams)
    for team_id, old in before_teams.items():
        if team_id != TEAM or not settings_won:
            assert after_teams[team_id] == old, 'Losing transaction partially changed a Team'
    after_history = snapshot()
    assert after_history['identity'] == before_history['identity']
    additions = assert_history_delta(before_history['history'], after_history['history'],
                                     {'team_create_receipts': 0 if settings_won else 1})
    if not settings_won:
        created_receipt = additions['team_create_receipts'][0]
        assert created_receipt['idempotency_key'] == create_key
        assert json.loads(created_receipt['original_response']) == create_result[1]['data']
        assert journal() == before_journal, 'Losing settings write left ledger/event/receipt/audit state'
    else:
        saved = settings_result[1]['data']
        assert saved['revision'] == reviewed['revision'] + 1
        assert saved['after']['agentIds'] == settings_body['agentIds']
        after_journal = journal()
        for table in ('team_settings_events', 'team_settings_receipts', 'audit_events'):
            assert set(before_journal[table]) <= set(after_journal[table])
            assert len(after_journal[table]) == len(before_journal[table]) + 1
        receipts = [row for row in org_rows('team_settings_receipts') if row['idempotency_key'] == settings_key]
        assert len(receipts) == 1 and json.loads(receipts[0]['original_response']) == saved
        assert not any(row['idempotency_key'] == create_key for row in org_rows('team_settings_receipts'))
    assert a.request(ROOT + '/receipts/' + settings_key)[1]['data'] == (
        settings_result[1]['data'] if settings_won else None)
    # Subsequent settings-only check starts after the explicitly verified create delta.
    immutable = after_history
    assert snapshot() == immutable, 'Receipt lookup changed external create history'
    error(a.save(command(a.context())), 409, 'TEAM_SETTINGS_NO_CHANGES')
    assert snapshot() == immutable
    return immutable


def agent_attachment_wait(a, b):
    reviewed = a.context()
    before_history, before_journal = snapshot(), journal()
    before_teams = org_rows('teams')
    before_agents = {row['id']: row for row in org_rows('agents')}
    create_key, settings_key = str(uuid.uuid4()), str(uuid.uuid4())
    draft = {'name': 'Native cooperative attached Agent', 'role': 'Reviewer', 'teamId': TEAM,
             'description': 'Real HTTP roster source', 'responsibilities': 'Review changes',
             'expectedInput': 'Change specification', 'outputFormat': 'Findings',
             'acceptanceCriteria': 'Evidence', 'model': None, 'skills': None, 'tools': None, 'limits': None}

    def queued(proc):
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            creator = pool.submit(b.create, f'/api/v1/organizations/{ORG}/agents', draft, create_key)
            try:
                # Observe creator queued FIRST, before settings is even submitted.
                first_waiters = wait_for_lock(proc.qc_backend_pid)
                stale = pool.submit(a.save, command(reviewed, purpose='Must reject attached roster'), settings_key)
                all_waiters = wait_for_lock(proc.qc_backend_pid, minimum=2)
                assert set(first_waiters) <= set(all_waiters)
            finally:
                release_holder(proc)
            created = creator.result(timeout=22)
            rejected = stale.result(timeout=22)
            return created, rejected

    created, rejected = held_org(queued)
    assert created[0] == 201, created
    error(rejected, 409, 'TEAM_SETTINGS_CONFLICT')
    agent = created[1]['data']
    assert agent['teamId'] == TEAM and agent['level'] == 'MID' and agent['status'] == 'DRAFT'
    current = a.context()
    assert current['revision'] == reviewed['revision']
    assert current['fingerprint'] != reviewed['fingerprint']
    assert current['memberTotal'] == reviewed['memberTotal'] + 1
    assert agent['id'] in current['team']['agentIds']
    assert org_rows('teams') == before_teams and journal() == before_journal
    after_agents = {row['id']: row for row in org_rows('agents')}
    assert set(after_agents) - set(before_agents) == {agent['id']}
    assert all(after_agents[agent_id] == old for agent_id, old in before_agents.items())
    after_history = snapshot()
    assert after_history['identity'] == before_history['identity']
    additions = assert_history_delta(before_history['history'], after_history['history'], {
        'agent_draft_config': 1, 'agent_configuration_versions': 1, 'agent_create_receipts': 1})
    for table in ('agent_draft_config', 'agent_configuration_versions'):
        assert additions[table][0]['agent_id'] == agent['id']
    create_receipt = additions['agent_create_receipts'][0]
    assert create_receipt['idempotency_key'] == create_key
    assert json.loads(create_receipt['original_response']) == agent
    assert a.request(ROOT + '/receipts/' + settings_key)[1]['data'] is None
    immutable = after_history
    assert snapshot() == immutable, 'Receipt lookup changed external attachment history'
    error(a.save(command(reviewed, purpose='Still stale after real attachment')), 409, 'TEAM_SETTINGS_CONFLICT')
    assert snapshot() == immutable, 'Rejected settings retry changed the external create history'
    return immutable


def post_wait_account_change(kind):
    assert kind in ('disable', 'credential-version')
    # A new independently authenticated session for EACH barrier; never reuse a
    # session invalidated by a preceding vector or let login hide a preauth denial.
    client = Client('other')
    account = "email='settings-native-other@example.test'"
    original = json.loads(sql(f"SELECT json_build_object('enabled',enabled,'version',credential_version) FROM human_accounts WHERE {account};"))
    reviewed = client.context()
    before, immutable = state_snapshot(), snapshot()
    sessions = sql("SELECT primary_id,session_id,principal_name FROM spring_session WHERE principal_name='settings-native-other@example.test' ORDER BY primary_id;")
    mutation = 'enabled=false' if kind == 'disable' else 'credential_version=credential_version+1'

    def deny_after_wait(proc):
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending = pool.submit(client.save, command(reviewed, name='Must not save after ' + kind))
            try:
                wait_for_lock(proc.qc_backend_pid)
            finally:
                release_holder(proc, f'UPDATE human_accounts SET {mutation} WHERE {account};')
            error(pending.result(timeout=22), 403, 'FORBIDDEN')

    try:
        held_org(deny_after_wait)
        assert state_snapshot() == before and snapshot() == immutable
        assert sql("SELECT primary_id,session_id,principal_name FROM spring_session WHERE principal_name='settings-native-other@example.test' ORDER BY primary_id;") == sessions
    finally:
        sql(f"UPDATE human_accounts SET enabled={'true' if original['enabled'] else 'false'},credential_version={int(original['version'])} WHERE {account};")
    assert json.loads(sql(f"SELECT json_build_object('enabled',enabled,'version',credential_version) FROM human_accounts WHERE {account};")) == original


def main():
    signal.signal(signal.SIGALRM, lambda *_: (_ for _ in ()).throw(TimeoutError('299s probe bound')))
    signal.alarm(299)
    a, b = Client(), Client()
    immutable = snapshot()
    c = a.context()
    assert c['revision'] == 0 and c['memberTotal'] == 3
    assert set(c) == {'organizationId', 'teamId', 'checkedAt', 'revision', 'fingerprint', 'team', 'members', 'memberTotal', 'canEdit', 'canChangeMembership', 'blockers'}
    assert len(c['fingerprint']) == 64
    assert sql(f"SELECT count(*) FROM team_settings_ledgers WHERE organization_id='{ORG}';") == '0'
    candidates = []
    for page in range(2):
        status, body = a.request(ROOT + f'/candidates?page={page}')
        assert status == 200
        assert body['data']['total'] == 27 and body['data']['size'] == 20
        candidates += body['data']['items']
    assert len({x['id'] for x in candidates}) == 27
    assert Client('member').request(ROOT)[0] == 403
    error(a.save(command(c)), 409, 'TEAM_SETTINGS_NO_CHANGES')
    for changes in ({'concurrency': '4'}, {'concurrency': 1.5}, {'monthlyBudget': '1'},
                    {'monthlyBudget': 1.001}, {'name': '\u0000'}, {'acknowledgement': False},
                    {'agentIds': [None]}, {'extra': True}, {'expectedRevision': 2147483648}):
        error(a.save(command(c, **changes)), 400, 'BAD_REQUEST')
    for query in ('?page=-1', '?page=0&page=1', '?unknown=1'):
        error(a.request(ROOT + '/candidates' + query), 400, 'BAD_REQUEST')
    # Raw malformed wire is sent as bytes, never normalized by the client.
    valid = command(c, name='Wire rejection')
    raw = json.dumps(valid)
    h = dict(a.headers, **{'Idempotency-Key': str(uuid.uuid4())})
    for wire in [raw[:-1] + ',"name":"duplicate"}', json.dumps({k: v for k, v in valid.items() if k != 'acknowledgement'}),
                 raw.replace('"expectedRevision": 0', '"expectedRevision": 0.5'),
                 raw.replace('"name": "Wire rejection"', '"name": "' + 'x'*33000 + '"')]:
        assert a.request(ROOT, wire.encode(), h)[0] == 400
    assert a.request(ROOT, raw.encode(), {'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})[0] == 403
    for invalid_key in ('invalid', str(uuid.uuid4()).upper()):
        assert a.request(ROOT, raw.encode(), dict(a.headers, **{'Idempotency-Key': invalid_key}))[0] == 400
    foreign = ROOT.replace(TEAM, '50610000-0000-4000-8000-000000000501')
    assert a.request(foreign)[0] == 404
    assert a.save(valid, path=foreign)[0] == 404
    body = command(c, name='Native settings committed', concurrency=7, monthlyBudget=12.50)
    key = str(uuid.uuid4())
    results = race([a, b], [(body, key, ROOT)] * 2)
    assert sorted(x[0] for x in results) == [200, 201], results
    receipt = results[0][1]['data']
    assert receipt == results[1][1]['data'] and receipt['revision'] == 1
    assert set(receipt) == {'operation', 'organizationId', 'teamId', 'idempotencyKey', 'revision', 'eventId', 'actorId', 'actorName', 'recordedAt', 'before', 'after'}
    state_keys = {'name', 'purpose', 'description', 'status', 'managerAgentId', 'agentIds', 'intake', 'concurrency', 'monthlyBudget', 'currency'}
    assert set(receipt['before']) == state_keys and set(receipt['after']) == state_keys
    assert receipt['operation'] == 'UPDATE_TEAM_SETTINGS'
    audit = json.loads(sql(f"SELECT payload FROM audit_events WHERE organization_id='{ORG}' AND event_type='TEAM_SETTINGS_UPDATED';"))
    assert audit == {'eventId': receipt['eventId'], 'revision': 1}
    assert sql(f"SELECT count(*) FROM team_settings_events WHERE organization_id='{ORG}' AND team_id='{TEAM}';") == '1'
    assert sql(f"SELECT count(*) FROM team_settings_receipts WHERE organization_id='{ORG}' AND team_id='{TEAM}';") == '1'
    assert sql(f"SELECT created_at=(SELECT recorded_at FROM team_settings_events WHERE event_id='{receipt['eventId']}') FROM audit_events WHERE organization_id='{ORG}' AND event_type='TEAM_SETTINGS_UPDATED';") == 't'
    c = a.context()
    assert c['team']['name'] == body['name'] and c['team']['concurrency'] == 7
    results = race([a, b], [(command(c, purpose='Race A'), str(uuid.uuid4()), ROOT),
                           (command(c, purpose='Race B'), str(uuid.uuid4()), ROOT)])
    assert sorted(x[0] for x in results) == [201, 409], results
    error(next(x for x in results if x[0] == 409), 409, 'TEAM_SETTINGS_CONFLICT')
    replay = a.save(body, key)
    assert replay[0] == 200 and replay[1]['data'] == receipt
    error(a.save(dict(body, name='Different'), key), 409, 'IDEMPOTENCY_CONFLICT')
    assert a.request(ROOT + '/receipts/' + key)[1]['data'] == receipt
    # External writer fingerprint without settings-ledger update.
    c = a.context()
    sql(f"UPDATE agents SET configuration_revision=configuration_revision+1,updated_at=clock_timestamp() WHERE team_id='{TEAM}' AND name='Settings Agent 001';")
    changed = a.context()
    assert changed['revision'] == c['revision'] and changed['fingerprint'] != c['fingerprint']
    error(a.save(command(c, name='Stale external source')), 409, 'TEAM_SETTINGS_CONFLICT')
    # Real Agent Studio HTTP writer must also invalidate a reviewed fingerprint.
    studio_path = f'/api/v1/organizations/{ORG}/agents/50620000-0000-4000-8000-000000001002/configuration'
    status, studio = a.request(studio_path)
    assert status == 200
    studio = studio['data']
    fields = ['responsibilities', 'expectedInput', 'outputFormat', 'acceptanceCriteria', 'model', 'skills', 'tools', 'limits']
    studio_body = {field: studio['agent'].get(field) for field in fields}
    studio_body.update(expectedRevision=studio['revision'], responsibilities='Real HTTP external Studio edit',
                       concurrency=2, maxTokensPerRun=1000, monthlyBudget=10)
    reviewed = a.context()
    assert snapshot() == immutable, 'Settings changed history before Studio writer'
    studio_key = str(uuid.uuid4())
    status, studio_saved = a.request(studio_path, json.dumps(studio_body).encode(),
                                    dict(a.headers, **{'Idempotency-Key': studio_key}), 'PUT')
    assert status == 200
    changed = a.context()
    assert changed['revision'] == reviewed['revision'] and changed['fingerprint'] != reviewed['fingerprint']
    error(a.save(command(reviewed, purpose='Stale real Studio review')), 409, 'TEAM_SETTINGS_CONFLICT')
    # Admit exactly this Studio config update and its two identified history rows.
    after_studio = snapshot()
    assert after_studio['identity'] == immutable['identity']
    old_config = {json.loads(row)['agent_id']: json.loads(row) for row in immutable['history']['agent_draft_config']}
    new_config = {json.loads(row)['agent_id']: json.loads(row) for row in after_studio['history']['agent_draft_config']}
    expected_config = {agent_id: dict(row) for agent_id, row in old_config.items()}
    expected_config['50620000-0000-4000-8000-000000001002'].update(
        {('max_tokens_per_run' if field == 'maxTokensPerRun' else
          'monthly_budget' if field == 'monthlyBudget' else field): studio_body[field]
         for field in ('responsibilities', 'model', 'skills', 'tools', 'limits', 'concurrency', 'maxTokensPerRun', 'monthlyBudget')})
    assert new_config == expected_config, 'Unexpected Studio or settings config mutation'
    additions = assert_history_delta(
        dict(immutable['history'], agent_draft_config=[]),
        dict(after_studio['history'], agent_draft_config=[]),
        {'agent_configuration_versions': 1, 'agent_configuration_receipts': 1})
    for table in ('agent_configuration_versions', 'agent_configuration_receipts'):
        row = additions[table][0]
        assert row['agent_id'] == '50620000-0000-4000-8000-000000001002'
        assert json.loads(row['original_response']) == studio_saved['data']
    assert additions['agent_configuration_receipts'][0]['idempotency_key'] == studio_key
    immutable = after_studio
    # Historical attribution removal remains blocked, without deleting history.
    blocked = next(x for x in changed['members'] if x['name'] == 'Settings Agent 003')
    assert not blocked['canRemove'] and blocked['removalBlockers']
    error(a.save(command(changed, agentIds=[x for x in changed['team']['agentIds'] if x != blocked['id']])), 409, 'TEAM_SETTINGS_BLOCKED')
    # Successful detach is not blocked by mere configuration history existence.
    removable = next(x for x in changed['members'] if x['name'] == 'Settings Agent 002')
    desired = [x for x in changed['team']['agentIds'] if x != removable['id']]
    assert a.save(command(changed, agentIds=desired))[0] == 201
    assert sql(f"SELECT team_id IS NULL FROM agents WHERE id='{removable['id']}';") == 't'
    # Null and ineligible unchanged manager are grandfathered; delta is not.
    for team_number, state in ((102, 'ACTIVE'), (104, 'DRAFT')):
        path = ROOT.replace(TEAM, f'50610000-0000-4000-8000-{team_number:012d}')
        current = a.context(path)
        assert current['team']['managerAgentId'] is None
        desired = command(current, purpose='Complete nullable legacy', intake='MANUAL', concurrency=2, monthlyBudget=0)
        assert a.save(desired, path=path)[0] == 201
        if state == 'ACTIVE':
            error(a.save(command(a.context(path), agentIds=[candidates[-1]['id']]), path=path), 409, 'TEAM_SETTINGS_BLOCKED')
    sql(f"UPDATE agents SET status='SUSPENDED' WHERE id=(SELECT manager_agent_id FROM teams WHERE id='{TEAM}');")
    c = a.context()
    assert a.save(command(c, purpose='Retain suspended manager'))[0] == 201
    assert a.context()['team']['managerAgentId'] == c['team']['managerAgentId']
    assert sql(f"SELECT status FROM agents WHERE id='{c['team']['managerAgentId']}';") == 'SUSPENDED'
    sql(f"UPDATE agents SET status='DRAFT' WHERE id='{c['team']['managerAgentId']}';")
    # Historical task/requester/AGENT audit and global incoming/outgoing reporting.
    agent = blocked['id']
    mission = sql(f"SELECT id FROM missions WHERE manager_agent_id='{agent}' LIMIT 1;")
    sql(f"UPDATE missions SET manager_agent_id=NULL WHERE id='{mission}';")
    checks = [
        (f"INSERT INTO tasks(id,organization_id,team_id,mission_id,title,status,owner_agent_id) VALUES ('50640000-0000-4000-8000-000000000001','{ORG}','{TEAM}','{mission}','Terminal owner','COMPLETED','{agent}');",
         "DELETE FROM tasks WHERE id='50640000-0000-4000-8000-000000000001';"),
        (f"INSERT INTO approval_requests(id,organization_id,team_id,requested_by_agent_id,title,action_type,risk_level,status) VALUES ('50650000-0000-4000-8000-000000000001','{ORG}','{TEAM}','{agent}','Terminal requester','QC','LOW','EXPIRED');",
         "DELETE FROM approval_requests WHERE id='50650000-0000-4000-8000-000000000001';"),
        (f"INSERT INTO audit_events(id,organization_id,actor_type,actor_id,event_type,payload,created_at) VALUES ('50660000-0000-4000-8000-000000000001','{ORG}','AGENT','{agent}','QC_HISTORY','PRIVATE',now());",
         "DELETE FROM audit_events WHERE id='50660000-0000-4000-8000-000000000001';"),
        (f"UPDATE agents SET reports_to_agent_id='{agent}' WHERE id='50620000-0000-4000-8000-000000005030';",
         "UPDATE agents SET reports_to_agent_id=NULL WHERE id='50620000-0000-4000-8000-000000005030';"),
        (f"UPDATE agents SET reports_to_agent_id='50620000-0000-4000-8000-000000005030' WHERE id='{agent}';",
         f"UPDATE agents SET reports_to_agent_id=NULL WHERE id='{agent}';")]
    try:
        for setup, cleanup in checks:
            sql(setup)
            try:
                c = a.context()
                m = next(x for x in c['members'] if x['id'] == agent)
                assert not m['canRemove'] and m['removalBlockers']
                before = state_snapshot()
                error(a.save(command(c, agentIds=[x for x in c['team']['agentIds'] if x != agent])), 409, 'TEAM_SETTINGS_BLOCKED')
                assert state_snapshot() == before
            finally:
                sql(cleanup)
    finally:
        sql(f"UPDATE missions SET manager_agent_id='{agent}' WHERE id='{mission}';")
    # Two teams conditionally claim the same unassigned Agent.
    c = a.context()
    other_path = ROOT.replace(TEAM, '50610000-0000-4000-8000-000000000105')
    other = b.context(other_path)
    claim = candidates[0]['id']
    results = race([a, b], [
        (command(c, agentIds=c['team']['agentIds']+[claim]), str(uuid.uuid4()), ROOT),
        (command(other, agentIds=[claim]), str(uuid.uuid4()), other_path)])
    assert sorted(x[0] for x in results) == [201, 409], results
    assert sql(f"SELECT count(*) FROM agents WHERE id='{claim}' AND team_id IS NOT NULL;") == '1'
    # Preserve the settings-only oracle before admitting any external history.
    # Each helper verifies every old row and only its own identified additions;
    # return that verified snapshot rather than blindly resetting the baseline.
    assert snapshot() == immutable, 'Settings mutated unrelated config/history/grants before external creates'
    immutable = team_create_claim(a, b)
    assert snapshot() == immutable
    immutable = agent_attachment_wait(a, b)
    assert snapshot() == immutable
    # Each vector authenticates independently and restores the exact account
    # values in finally; neither vector reuses the invalidated session.
    post_wait_account_change('disable')
    post_wait_account_change('credential-version')
    assert snapshot() == immutable
    # Current authority must be read after a proven organization lock wait.
    c = a.context()
    membership = f"organization_id='{ORG}' AND account_id=(SELECT id FROM human_accounts WHERE email='settings-native-admin@example.test')"
    def revoke_while_waiting(proc):
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            future = pool.submit(a.save, command(c, name='Must not commit after wait'))
            wait_for_lock()
            proc.stdin.write(f"UPDATE organization_memberships SET role='MEMBER' WHERE {membership}; COMMIT;\n")
            proc.stdin.flush()
            assert future.result(timeout=25)[0] == 403
    before = state_snapshot()
    try:
        held_org(revoke_while_waiting)
        assert state_snapshot() == before
    finally:
        sql(f"UPDATE organization_memberships SET role='ADMIN' WHERE {membership};")
    # A scoped audit fault must roll back config, assignments, ledger, events, receipts.
    sql(f"""CREATE FUNCTION qc_settings_audit_fault() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN IF NEW.organization_id='{ORG}'::uuid AND NEW.event_type='TEAM_SETTINGS_UPDATED'
THEN RAISE EXCEPTION 'QC scoped audit fault'; END IF; RETURN NEW; END $$;
CREATE TRIGGER qc_settings_audit_fault BEFORE INSERT ON audit_events FOR EACH ROW EXECUTE FUNCTION qc_settings_audit_fault();""")
    try:
        c = a.context()
        before = state_snapshot()
        assert a.save(command(c, description='Rollback this configuration'))[0] >= 500
        assert state_snapshot() == before
    finally:
        sql('DROP TRIGGER qc_settings_audit_fault ON audit_events; DROP FUNCTION qc_settings_audit_fault();')
    # Receipt GET is actor/org/team/key lookup, not a submitted body hash.
    assert Client('other').request(ROOT + '/receipts/' + key)[1]['data'] is None
    archive = "UPDATE teams SET status='ARCHIVED' WHERE id='" + TEAM + "';"
    try:
        sql(archive)
        assert a.save(body, key)[1]['data'] == receipt
        assert a.request(ROOT + '/receipts/' + key)[1]['data'] == receipt
        error(a.save(command(a.context(), purpose='Archived blocked')), 409, 'TEAM_SETTINGS_BLOCKED')
    finally:
        sql(f"UPDATE teams SET status='DRAFT' WHERE id='{TEAM}';")
    # Above-bound real set: total is exact, projection is first 201, writes blocked.
    oversized = ROOT.replace(TEAM, '50610000-0000-4000-8000-000000000105')
    oversized_id = '50610000-0000-4000-8000-000000000105'
    try:
        sql(f"""INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('50690000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'{ORG}','{oversized_id}',
'Oversize '||n,'QC','MID','DRAFT','SUGGEST',now(),now() FROM generate_series(1,202) n;""")
        over = a.context(oversized)
        assert over['memberTotal'] >= 202 and len(over['members']) == 201
        assert not over['canEdit'] and 'TEAM_SIZE_UNSUPPORTED' in over['blockers']
        # A bounded syntactically valid command still cannot mutate oversized source.
        cmd = command(over, name='Oversize rejected', agentIds=[], managerAgentId=None)
        error(a.save(cmd, path=oversized), 409, 'TEAM_SETTINGS_BLOCKED')
    finally:
        sql("DELETE FROM agents WHERE id::text LIKE '50690000-%';")
    # Legal 200 -> disjoint200 replacement exercises the full400 union path.
    replacement_team = '50610000-0000-4000-8000-000000000106'
    replacement_path = ROOT.replace(TEAM, replacement_team)
    sql(f"""INSERT INTO teams(id,organization_id,name,purpose,status,intake,concurrency,monthly_budget,currency,created_at,updated_at)
VALUES ('{replacement_team}','{ORG}','Union400','Bounded union','DRAFT','MANUAL',1,0,'USD',now(),now());
INSERT INTO agents(id,organization_id,team_id,name,role,level,status,autonomy_level,created_at,updated_at)
SELECT ('506a0000-0000-4000-8000-'||lpad(n::text,12,'0'))::uuid,'{ORG}',
CASE WHEN n<=200 THEN '{replacement_team}'::uuid ELSE NULL END,
'Union '||n,'QC','MID','DRAFT','SUGGEST',now(),now() FROM generate_series(1,400) n;""")
    union = a.context(replacement_path)
    assert union['memberTotal'] == 200 and len(union['members']) == 200 and union['canEdit']
    desired = [f'506a0000-0000-4000-8000-{n:012d}' for n in range(201,401)]
    assert a.save(command(union, agentIds=desired), path=replacement_path)[0] == 201
    assert a.context(replacement_path)['team']['agentIds'] == desired
    assert sql(f"SELECT count(*) FROM agents WHERE team_id='{replacement_team}';") == '200'
    # Sorted acquisition order still requires source/DB trace review; successful
    # full-union mutation is not by itself a proof of lock ordering.
    # Boundary is revision INT_MAX; exact historical replay still bypasses it.
    original_revision = a.context()['revision']
    try:
        sql(f"UPDATE team_settings_ledgers SET revision=2147483647 WHERE organization_id='{ORG}' AND team_id='{TEAM}';")
        error(a.save(command(a.context(), purpose='Exhausted')), 409, 'TEAM_SETTINGS_REVISION_EXHAUSTED')
        assert a.save(body, key)[1]['data'] == receipt
    finally:
        sql(f"UPDATE team_settings_ledgers SET revision={original_revision} WHERE organization_id='{ORG}' AND team_id='{TEAM}';")
    assert immutable == snapshot(), 'Settings mutated unrelated config/history/grants'
    print('PASS: executed real HTTP/PostgreSQL settings cases (see QC matrix for unexecuted gaps)')


if __name__ == '__main__':
    main()
