"""Disposable PostgreSQL HTTP probe for Studio versions, retries and lost updates."""
import concurrent.futures
import http.cookiejar
import json
import os
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee'
AGENT = 'd4000000-0000-4000-8000-000000000001'
PATH = f'/api/v1/organizations/{ORG}/agents/{AGENT}/configuration'
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def request(path, data=None, headers=None, method=None, client=opener):
    req = urllib.request.Request(BASE + path, data=data, headers=headers or {}, method=method)
    try:
        with client.open(req, timeout=30) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def csrf():
    status, body = request('/api/v1/auth/csrf')
    assert status == 200, f'CSRF failed: {status}'
    return {body['data']['headerName']: body['data']['token']}


headers = csrf()
headers['Content-Type'] = 'application/x-www-form-urlencoded'
status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
    'username': 'studio-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), headers)
assert status == 200, f'Login failed: {status}'
headers = csrf()
headers['Content-Type'] = 'application/json'
status, envelope = request(PATH)
assert status == 200, f'Studio read failed: {status}'
initial = envelope['data']
revision = initial['revision']
fields = ['responsibilities', 'expectedInput', 'outputFormat', 'acceptanceCriteria',
          'model', 'skills', 'tools', 'limits']
body = {key: initial['agent'].get(key) for key in fields}
body.update({key: initial.get(key) for key in ['concurrency', 'maxTokensPerRun', 'monthlyBudget']})
body.update(expectedRevision=revision, responsibilities='Native concurrent retry ' + str(uuid.uuid4()))
first_key = str(uuid.uuid4())


def concurrent_save(attempts):
    barrier = threading.Barrier(2)

    def submit(attempt):
        key, data = attempt
        client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
        request_headers = dict(headers, **{'Idempotency-Key': key})
        barrier.wait(timeout=10)
        return request(PATH, json.dumps(data).encode(), request_headers, 'PUT', client)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        return list(pool.map(submit, attempts))


same = concurrent_save([(first_key, body), (first_key, body)])
assert [status for status, _ in same] == [200, 200], 'Identical retry must succeed twice'
assert same[0][1]['data'] == same[1][1]['data'], 'Replay response differs'
original = same[0][1]['data']
assert original['revision'] == revision + 1 and original['agent']['id'] == AGENT
next_a = dict(body, expectedRevision=revision + 1, responsibilities='Concurrent edit A')
next_b = dict(body, expectedRevision=revision + 1, responsibilities='Concurrent edit B')
different = concurrent_save([(str(uuid.uuid4()), next_a), (str(uuid.uuid4()), next_b)])
assert sorted(status for status, _ in different) == [200, 409], 'Expected one winner and one stale conflict'
loser = next(result for status, result in different if status == 409)
assert loser['error']['code'] == 'CONFIGURATION_CONFLICT'
winner = next(result['data'] for status, result in different if status == 200)
status, current = request(PATH)
assert status == 200 and current['data'] == winner
assert current['data']['revision'] == revision + 2
replay_headers = dict(headers, **{'Idempotency-Key': first_key})
status, replay = request(PATH, json.dumps(body).encode(), replay_headers, 'PUT')
assert status == 200 and replay['data'] == original, 'Committed retry must preserve its original response after later edits'
status, conflict = request(PATH, json.dumps(dict(body, model='Different request')).encode(), replay_headers, 'PUT')
assert status == 409 and conflict['error']['code'] == 'IDEMPOTENCY_CONFLICT'
print('PASS: PostgreSQL Studio concurrent same-key replay, two-key stale revision and durable original response')
