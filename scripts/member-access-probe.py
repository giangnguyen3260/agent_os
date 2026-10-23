"""Native HTTP/PostgreSQL probe; disposable member-access fixture only.

E2E_PASSWORD is the existing qc password copied by the fixture. Default DB transport
is Docker Compose; set MEMBER_ACCESS_PSQL_DSN for a native psql connection.
Only synthetic account/membership authorization controls are changed and restored.
No grants, decisions, receipts, execution records or other business data are written.
"""
from datetime import datetime
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import urllib.error
import urllib.parse
import urllib.request

ORG = '50505050-5050-4050-8050-505050505050'
FOREIGN = '51515151-5151-4151-8151-515151515151'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ROOT = f'/api/v1/organizations/{ORG}/members'


def ident(prefix, number):
    return f'{prefix}0000-0000-4000-8000-{number:012d}'


ADMIN = ident('5080', 1)
MEMBER = ident('5080', 2)
DISABLED = ident('5080', 3)


def sql(statement):
    dsn = os.environ.get('MEMBER_ACCESS_PSQL_DSN')
    command = (['psql', dsn, '-X', '-q', '-t', '-A', '-v', 'ON_ERROR_STOP=1'] if dsn else
               ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
                'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'])
    return subprocess.check_output(command, input=statement, text=True,
                                   cwd=Path(__file__).resolve().parents[1], timeout=30).strip()


class Client:
    def __init__(self, role=None):
        self.http = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if role:
            code, body = self.request('/api/v1/auth/csrf')
            assert code == 200
            csrf = body['data']
            form = urllib.parse.urlencode({
                'username': f'member-access-{role}@example.test',
                'password': os.environ['E2E_PASSWORD']}).encode()
            code, _ = self.request('/api/v1/auth/login', form, {
                csrf['headerName']: csrf['token'],
                'Content-Type': 'application/x-www-form-urlencoded'})
            assert code == 200, 'synthetic login failed'

    def request(self, path, data=None, headers=None):
        try:
            response = self.http.open(urllib.request.Request(
                BASE + path, data, headers or {}), timeout=30)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read().decode()
            for secret in ('password', 'credentialVersion', 'credential_version',
                           'sessionId', 'audit', 'FOREIGN_MEMBER_PRIVATE',
                           'FOREIGN_TEAM_PRIVATE', 'FOREIGN_ACTION_PRIVATE'):
                assert secret not in raw, f'private field leaked: {secret}'
            return response.status, json.loads(raw) if raw else None

    def read(self, path):
        code, body = self.request(path)
        assert code == 200, (path, code)
        return body['data']


def business_state():
    # Snapshot every public business table, excluding auth/session infrastructure.
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' "
                 "AND tablename NOT LIKE 'spring_session%' "
                 "AND tablename NOT IN ('authentication_audit','authentication_attempts','password_reset_tokens',"
                 "'flyway_schema_history') ORDER BY tablename;").splitlines()
    return {table: sql(f'SELECT coalesce(jsonb_agg(row_data ORDER BY row_data::text), '
                       f"'[]'::jsonb)::text FROM (SELECT to_jsonb(t) row_data "
                       f'FROM public."{table}" t) s;') for table in tables}


def stamp(value):
    return datetime.fromisoformat(value.replace('Z', '+00:00'))


def check_inventory(client):
    pages = [client.read(ROOT + f'?page={page}') for page in (0, 1)]
    assert [len(p['items']) for p in pages] == [20, 6]
    rows = [row for page in pages for row in page['items']]
    expected = json.loads(sql(f"SELECT json_agg(id ORDER BY lower(display_name),id) "
                              f"FROM human_accounts WHERE id IN (SELECT account_id FROM "
                              f"organization_memberships WHERE organization_id='{ORG}');"))
    assert [row['accountId'] for row in rows] == expected
    for page in pages:
        assert set(page) == {'organizationId', 'generatedAt', 'items', 'page', 'size', 'total'}
        assert page['total'] == 26 and page['size'] == 20 and page['organizationId'] == ORG
        for row in page['items']:
            assert set(row) == {'accountId', 'displayName', 'email', 'role', 'enabled', 'isSelf', 'grantCounts'}
            assert row['isSelf'] == (row['accountId'] == ADMIN)
            at = stamp(page['generatedAt']).isoformat()
            counts = json.loads(sql(f"SELECT json_build_object('unexpired',count(*) FILTER "
                f"(WHERE revoked_at IS NULL AND expires_at>'{at}'), 'expired',count(*) FILTER "
                f"(WHERE revoked_at IS NULL AND expires_at<='{at}'), 'revoked',count(*) FILTER "
                f"(WHERE revoked_at IS NOT NULL)) FROM approval_grants "
                f"WHERE organization_id='{ORG}' AND account_id='{row['accountId']}';"))
            assert row['grantCounts'] == counts
    for literal in ('%', '_', '\\', '\u2003LITERAL\u2003'):
        data = client.read(ROOT + '?' + urllib.parse.urlencode({'query': literal, 'role': 'MEMBER'}))
        assert data['total'] == 1 and data['items'][0]['accountId'] == MEMBER
    assert client.read(ROOT + '?role=ADMIN')['total'] == 1
    assert client.read(ROOT + '?query=absent')['total'] == 0
    assert client.read(ROOT + '?page=100000')['items'] == []


def check_grants(client):
    path = ROOT + f'/{MEMBER}/approval-grants'
    for status, total in [('ALL', 27), ('UNEXPIRED', 24), ('EXPIRED', 1), ('REVOKED', 2)]:
        result = client.read(path + f'?status={status}')
        assert set(result) == {'organizationId', 'generatedAt', 'member', 'items', 'page', 'size', 'total'}
        assert result['total'] == total
        assert set(result['member']) == {'accountId', 'displayName', 'email', 'role', 'enabled', 'isSelf'}
        assert result['member']['accountId'] == MEMBER
        rows = result['items']
        if total > 20:
            rows += client.read(path + f'?status={status}&page=1')['items']
        assert len(rows) == total and len({row['id'] for row in rows}) == total
        assert rows == sorted(rows, key=lambda row: (-stamp(row['expiresAt']).timestamp(), row['id']))
        for row in rows:
            assert set(row) == {'id', 'organizationId', 'accountId', 'teamId', 'teamName',
                                'actionType', 'riskLevel', 'expiresAt', 'revokedAt', 'status'}
            assert row['organizationId'] == ORG and row['accountId'] == MEMBER
            assert row['teamName'] == 'Member Access Archived Team'
            lifecycle = ('REVOKED' if row['revokedAt'] else 'EXPIRED'
                         if stamp(row['expiresAt']) <= stamp(result['generatedAt']) else 'UNEXPIRED')
            assert row['status'] == lifecycle
            assert status == 'ALL' or lifecycle == status
    disabled = client.read(ROOT + f'/{DISABLED}/approval-grants')
    assert disabled['member']['enabled'] is False and disabled['total'] == 1


def check_authorization():
    paths = [ROOT, ROOT + f'/{MEMBER}/approval-grants']
    for client, expected in [(Client(), 401), (Client('member'), 403)]:
        for path in paths:
            assert client.request(path)[0] == expected
    client = Client('admin')
    for path in paths:
        assert client.request(path.replace(ORG, FOREIGN))[0] == 403
    for target in [ident('5080', 99), ident('5080', 999)]:
        assert client.request(ROOT + f'/{target}/approval-grants')[0] == 404
    for query in ['page=-1', 'page=100001', 'page=x', 'role=OWNER', 'query=' + 'x' * 201]:
        assert client.request(ROOT + '?' + query)[0] == 400
    assert client.request(paths[1] + '?status=ACTIVE')[0] == 400
    original = json.loads(sql(f"SELECT json_build_object('enabled',enabled,'version',credential_version,"
                              f"'role',m.role) FROM human_accounts h JOIN organization_memberships m "
                              f"ON m.account_id=h.id WHERE h.id='{ADMIN}' AND m.organization_id='{ORG}';"))
    assert original['enabled'] is True and original['role'] == 'ADMIN'
    try:
        # Fresh sessions precede each change, so stale authenticated state is exercised.
        for statement, expected in [
            (f"UPDATE organization_memberships SET role='MEMBER' WHERE account_id='{ADMIN}' AND organization_id='{ORG}'", 403),
            (f"UPDATE human_accounts SET enabled=false WHERE id='{ADMIN}'", 401),
            (f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{ADMIN}'", 401),
            (f"DELETE FROM organization_memberships WHERE account_id='{ADMIN}' AND organization_id='{ORG}'", 403)]:
            clients = [Client('admin'), Client('admin')]
            sql(statement + ';')
            try:
                for session, path in zip(clients, paths):
                    assert session.request(path)[0] == expected
            finally:
                restore(original)
    finally:
        restore(original)


def restore(original):
    assert isinstance(original['version'], int)
    sql(f"BEGIN; UPDATE human_accounts SET enabled=true,credential_version={original['version']} "
        f"WHERE id='{ADMIN}'; DELETE FROM organization_memberships WHERE account_id='{ADMIN}' "
        f"AND organization_id='{ORG}'; INSERT INTO organization_memberships VALUES "
        f"('{ADMIN}','{ORG}','ADMIN'); COMMIT;")


def main():
    client = Client('admin')
    before = business_state()
    try:
        check_inventory(client)
        check_grants(client)
        check_authorization()
    finally:
        assert business_state() == before, 'persistent business state changed'
    print('PASS: native member inventory/grants, PostgreSQL comparison, fresh authorization, no business effects')


if __name__ == '__main__':
    main()
