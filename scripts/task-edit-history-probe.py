"""Task history independent real HTTP/PostgreSQL QC; apply history fixture first.
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
    ('52000000', 1), ('52010000', 101), ('52020000', 101), ('52030000', 1001)))
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
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','human_accounts','organization_memberships','authentication_audit','authentication_attempts') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
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
            'username': f'task-history-native-{kind}@example.test', 'password': os.environ['E2E_PASSWORD']
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


PAGE_KEYS = {'organizationId', 'teamId', 'missionId', 'taskId', 'page', 'pageSize', 'total', 'items'}
ITEM_KEYS = {'eventId', 'revision', 'committedAt', 'actorName', 'before', 'after'}


def history(client, suffix='?page=0', root=ROOT):
    result = reject(lambda: client.request(root + '/history' + suffix), 200)
    data = result[1]['data']
    assert set(data) == PAGE_KEYS
    assert data['pageSize'] == 20 and type(data['total']) is int
    assert [data[k] for k in ('organizationId', 'teamId', 'missionId', 'taskId')] == [ORG, TEAM, MISSION, TASK]
    for item in data['items']:
        assert set(item) == ITEM_KEYS
        assert set(item['before']) == VALUES and set(item['after']) == VALUES
        assert type(item['revision']) is int and item['revision'] > 0
        assert isinstance(item['actorName'], str) and '@' not in item['actorName']
        instant(item['committedAt'])
    return data


def main():
    admin, member = Client(), Client('member')
    first, second = history(admin), history(admin, '?page=1')
    assert first['total'] == second['total'] == 25
    assert [x['revision'] for x in first['items']] == list(range(25, 5, -1))
    assert [x['revision'] for x in second['items']] == list(range(5, 0, -1))
    assert not set(x['eventId'] for x in first['items']) & set(x['eventId'] for x in second['items'])
    assert second['items'][-1]['before']['description'] is None
    assert history(admin, '?page=2')['items'] == []
    empty_root = ROOT.replace(TASK, ident('52030000', 1002))
    empty = reject(lambda: admin.request(empty_root + '/history'), 200)[1]['data']
    assert empty['total'] == 0 and empty['items'] == []
    for query in ('?page=-1', '?page=01', '?page=1.0', '?page=100001', '?page=0&page=1', '?size=20', '?page=', '?page=+1'):
        reject(lambda q=query: admin.request(ROOT + '/history' + q), 400)
    reject(lambda: member.request(ROOT + '/history'), 403)
    reject(lambda: admin.request(ROOT.replace(TEAM, ident('52010000', 102)) + '/history'), 404)
    reject(lambda: admin.request(ROOT.replace(ORG, ident('52000000', 5)) + '/history'), 403)
    # Real command creates provenance: list must return exactly its immutable receipt.
    context = admin.context()
    result = admin.save(desired(context))
    assert result[0] == 201
    receipt = result[1]['data']
    latest = history(admin)
    assert latest['total'] == 26
    event = latest['items'][0]
    for field in ('eventId', 'revision', 'committedAt', 'before', 'after'):
        assert event[field] == receipt[field]
    account = sql("SELECT id FROM human_accounts WHERE email='task-history-native-admin@example.test';")
    def deny_account(change, restore):
        sql(change)
        try:
            reject(lambda: admin.request(ROOT + '/history'), 403)
        finally:
            sql(restore)
    deny_account(f"UPDATE human_accounts SET enabled=false WHERE id='{account}';", f"UPDATE human_accounts SET enabled=true WHERE id='{account}';")
    # Retired/paused states do not erase authorized provenance access.
    sql(f"UPDATE missions SET task_intake_paused=true WHERE id='{MISSION}';")
    try:
        assert history(admin)['total'] == 26
    finally:
        sql(f"UPDATE missions SET task_intake_paused=false WHERE id='{MISSION}';")
    print('PASS: task edit history real persisted command, exact nullable projection, pagination, fresh ADMIN/scope and no-write reads')


if __name__ == '__main__':
    main()
