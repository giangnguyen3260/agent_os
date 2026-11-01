#!/usr/bin/env python3
"""Disposable Docker PostgreSQL + actual cookie-session provider options acceptance.

Run from repo root AFTER provider-options-ci-fixture.sql, before other policy users.
Requires PROVIDER_OPTIONS_DISPOSABLE=1, E2E_PASSWORD; E2E_EMAIL defaults to
qc@example.test, E2E_BASE_URL defaults to http://127.0.0.1:18080. No deployment use.
Invokes main-owned scripts/provider-policy-admin.py with its default Docker transport.
No cookie, password, SQL result or HTTP body is printed on failure.
"""
import http.cookiejar
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid

ORG = '7f240000-0000-4000-8000-000000000001'
ROOT = f'/api/v1/organizations/{ORG}/provider-connections/options'
ACTOR = '7f240100-0000-4000-8000-000000000001'
STALE = '7f240100-0000-4000-8000-000000000005'
BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080').rstrip('/')
PSQL = ['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -X -q -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']
CLI = [sys.executable, str(Path(__file__).with_name('provider-policy-admin.py'))]


def check(condition, message):
    if not condition:
        raise RuntimeError(message)


def sql(statement):
    result = subprocess.run(PSQL, input=statement, text=True, capture_output=True, timeout=30)
    check(result.returncode == 0, 'Disposable PostgreSQL operation failed')
    return result.stdout.strip()


def native(table, where='true'):
    # table/where are exclusively internal constants, never request inputs.
    return json.loads(sql(f"SELECT coalesce(json_agg(x ORDER BY row_to_json(x)::text),'[]') FROM (SELECT * FROM {table} WHERE {where}) x;"))


def business_snapshot():
    # Real login writes authentication_attempts/authentication_audit and Spring Session.
    # Accounts/memberships remain covered; the isolated version test restores its row.
    tables = sql("SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename NOT IN ('flyway_schema_history','authentication_audit','authentication_attempts','provider_deployment_policy','provider_read_admission') AND tablename NOT LIKE 'spring_session%' ORDER BY tablename;").splitlines()
    return {table: native(table) for table in tables}


def operator(*args, success=True):
    result = subprocess.run(CLI + list(args), capture_output=True, text=True, timeout=60)
    check((result.returncode == 0) == success, 'Operator CLI unexpected exit status')
    if success:
        try:
            return json.loads(result.stdout)
        except ValueError:
            raise RuntimeError('Operator CLI must emit JSON') from None


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


class Client:
    def __init__(self, email=None):
        self.opener = urllib.request.build_opener(
            NoRedirect(), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        if email:
            status, _, raw = self.request('/api/v1/auth/csrf')
            check(status == 200, 'Existing CSRF endpoint failed')
            csrf = json.loads(raw)['data']
            form = urllib.parse.urlencode({'username': email, 'password': os.environ['E2E_PASSWORD']}).encode()
            status, _, _ = self.request('/api/v1/auth/login', 'POST', form,
                {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
            check(status == 200, 'Actual cookie login failed')

    def request(self, path=ROOT, method='GET', body=None, headers=None):
        request = urllib.request.Request(BASE + path, data=body, method=method, headers=headers or {})
        try:
            response = self.opener.open(request, timeout=25)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, response.read()


def response(client, status=200, code=None, path=ROOT, method='GET', body=None, headers=None):
    actual, hdr, raw = client.request(path, method, body, headers)
    check(actual == status, f'Unexpected HTTP status: expected {status}, got {actual}')
    check('no-store' in hdr.get('Cache-Control', '').lower(), 'Missing no-store')
    check(hdr.get_content_type() == 'application/json', 'Missing JSON content type')
    if status == 405:
        check(hdr.get('Allow') == 'GET', 'Incorrect Allow')
    if method == 'HEAD':
        check(raw == b'', 'HEAD returned a body')
        return None, hdr
    try:
        data = json.loads(raw)
    except ValueError:
        raise RuntimeError('Non-JSON feature response') from None
    check(set(data) == ({'data', 'meta'} if status == 200 else {'error', 'meta'}), 'Envelope field mismatch')
    check(set(data['meta']) == {'requestId'}, 'Metadata mismatch')
    try:
        uuid.UUID(data['meta']['requestId'])
    except (ValueError, TypeError, AttributeError):
        raise RuntimeError('Invalid request ID') from None
    if code:
        error = data['error']
        check(set(error) == {'code', 'message', 'details'} and error['code'] == code
              and error['details'] == {}, 'Unsafe or incorrect error envelope')
        messages = {'BAD_REQUEST': 'Invalid provider settings request', 'UNAUTHORIZED': 'Authentication required',
                    'FORBIDDEN': 'Request denied', 'METHOD_NOT_ALLOWED': 'Method not allowed',
                    'RATE_LIMITED': 'Too many requests', 'PROVIDER_CONNECTION_UNAVAILABLE': 'Provider settings are unavailable'}
        check(error['message'] == messages[code], 'Noncontract error message')
    return data.get('data'), hdr


def options(client, revision, enabled):
    data, _ = response(client)
    check(set(data) == {'organizationId', 'policyRevision', 'providers'}, 'Options fields mismatch')
    check(data['organizationId'] == ORG and data['policyRevision'] == str(revision), 'Options identity/revision mismatch')
    expected = [('OPENAI', 'OPENAI_API_KEY', 'EMPTY'), ('ANTHROPIC', 'ANTHROPIC_API_KEY', 'ANTHROPIC_WORKSPACE'), ('GOOGLE_GEMINI', 'GEMINI_API_KEY', 'EMPTY')]
    check(data['providers'] == [dict(provider=p, credentialMode=m, enabled=p in enabled, optionsSchema=s)
                               for p, m, s in expected], 'Provider descriptors/flags mismatch')


def main():
    check(os.environ.get('PROVIDER_OPTIONS_DISPOSABLE') == '1', 'Explicit disposable opt-in required')
    parsed = urllib.parse.urlsplit(BASE)
    check(parsed.scheme == 'http' and parsed.hostname in ('127.0.0.1', 'localhost')
          and parsed.port == 18080 and not parsed.username and not parsed.password
          and parsed.path == '' and not parsed.query and not parsed.fragment, 'Only local disposable port 18080 is supported')
    check(os.environ.get('E2E_EMAIL', 'qc@example.test') == 'qc@example.test', 'QC bootstrap identity required')
    check(bool(os.environ.get('E2E_PASSWORD')), 'E2E_PASSWORD required')
    check(native('provider_deployment_policy') == [] and native('provider_read_admission') == [], 'Requires empty disposable policy/admission tables')
    baseline = business_snapshot()
    anonymous = Client()
    admin = Client('provider-options-admin@example.test')
    admin2 = Client('provider-options-admin2@example.test')
    member = Client('provider-options-member@example.test')
    foreign = Client('provider-options-foreign@example.test')
    stale = Client('provider-options-stale@example.test')
    response(anonymous, 401, 'UNAUTHORIZED', path=ROOT+'?invalid=1')
    response(member, 403, 'FORBIDDEN', path=ROOT+'?invalid=1')
    response(foreign, 403, 'FORBIDDEN')
    for method in ('HEAD', 'OPTIONS', 'POST', 'PUT', 'DELETE', 'PATCH'):
        response(admin, 405, 'METHOD_NOT_ALLOWED', method=method)
    check(native('provider_read_admission') == [], 'Denied authority/method consumed quota')
    response(admin, 503, 'PROVIDER_CONNECTION_UNAVAILABLE')
    # Replacing absent policy must fail, never initialize it or adopt a default.
    operator('replace', '--providers', 'ANTHROPIC', '--expected-revision', '1', success=False)
    check(native('provider_deployment_policy') == [], 'Absent-policy replacement initialized policy')
    operator('provision', '--providers', 'OPENAI,GOOGLE_GEMINI')
    operator('show')
    options(admin, 1, {'OPENAI', 'GOOGLE_GEMINI'})
    before = native('provider_deployment_policy')
    operator('provision', '--providers', 'ANTHROPIC', success=False)
    operator('replace', '--providers', 'ANTHROPIC', '--expected-revision', '2', success=False)
    operator('replace', '--providers', 'OPENAI,GOOGLE_GEMINI', '--expected-revision', '1', success=False)
    check(native('provider_deployment_policy') == before, 'Rejected operator command changed policy')
    operator('replace', '--providers', 'ANTHROPIC', '--expected-revision', '1')
    options(admin, 2, {'ANTHROPIC'})
    # Turn Anthropic off and back on: identical flags must carry a newer revision.
    operator('replace', '--providers', 'OPENAI', '--expected-revision', '2')
    options(admin, 3, {'OPENAI'})
    operator('replace', '--providers', 'ANTHROPIC', '--expected-revision', '3')
    options(admin, 4, {'ANTHROPIC'})
    policy = native('provider_deployment_policy')
    check(len(policy) == 1 and policy[0]['revision'] == 4 and policy[0]['anthropic_enabled']
          and not policy[0]['openai_enabled'] and not policy[0]['gemini_enabled'], 'Native policy differs from HTTP')
    for path, body, headers in [(ROOT+'?', None, None), (ROOT+'?x=1', None, None),
                                (ROOT, b'x', None), (ROOT, None, {'Accept': 'text/plain'})]:
        response(admin, 400, 'BAD_REQUEST', path=path, body=body, headers=headers)
    # Deterministically exercise the full real HTTP window without sleeps or policy resets.
    bucket = native('provider_read_admission', f"organization_id='{ORG}' AND actor_id='{ACTOR}'")
    check(len(bucket) == 1, 'Missing native admission row')
    count = len(json.loads(bucket[0]['recent_requests']))
    check(count == 9, 'Expected missing-policy, four projections and four malformed GET admissions')
    for _ in range(120-count):
        options(admin, 4, {'ANTHROPIC'})
    before = native('provider_read_admission', f"organization_id='{ORG}' AND actor_id='{ACTOR}'")
    check(len(json.loads(before[0]['recent_requests'])) == 120, 'Native quota not full')
    _, hdr = response(admin, 429, 'RATE_LIMITED', path=ROOT+'?malformed=1')
    check(hdr.get('Retry-After', '').isdigit() and 1 <= int(hdr['Retry-After']) <= 60, 'Invalid Retry-After')
    check(native('provider_read_admission', f"organization_id='{ORG}' AND actor_id='{ACTOR}'") == before, '429 persisted quota changes')
    options(admin2, 4, {'ANTHROPIC'})
    # human_accounts has created_at, no updated_at or update trigger; login only locks it.
    # Change only the isolated disposable identity; preserve its complete row on exit.
    stale_before = native('human_accounts', f"id='{STALE}'")
    sql(f"UPDATE human_accounts SET credential_version=credential_version+1 WHERE id='{STALE}';")
    try:
        response(stale, 401, 'UNAUTHORIZED')
        response(stale, 401, 'UNAUTHORIZED')
        check(native('provider_read_admission', f"actor_id='{STALE}'") == [], 'Stale session consumed admission')
    finally:
        sql(f"UPDATE human_accounts SET credential_version=credential_version-1 WHERE id='{STALE}';")
    check(native('human_accounts', f"id='{STALE}'") == stale_before, 'Stale identity row was not fully restored')
    check(business_snapshot() == baseline, 'Options probe changed business tables')
    print('Provider options disposable PostgreSQL/CLI/cookie-session probe passed')


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Suppress HTTP/SQL/subprocess payloads and authentication material.
        print(str(error) if isinstance(error, RuntimeError) else 'Provider options probe failed', file=sys.stderr)
        sys.exit(1)
