"""Disposable CI probe: concurrent Agent Draft requests against PostgreSQL."""
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
ORG = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'
PATH = f'/api/v1/organizations/{ORG}/agents'
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))


def request(path, data=None, headers=None, client=opener):
    req = urllib.request.Request(BASE + path, data=data, headers=headers or {})
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
    'username': 'team-admin@example.test', 'password': os.environ['E2E_PASSWORD']
}).encode(), headers)
assert status == 200, f'Login failed: {status}'
headers = csrf()
headers.update({'Content-Type': 'application/json', 'Idempotency-Key': str(uuid.uuid4())})
body = {
    'name': 'Concurrent Agent Draft CI', 'role': 'Reviewer', 'teamId': None,
    'description': 'Concurrent HTTP retry probe',
    'responsibilities': 'Review changes', 'expectedInput': 'Change specification',
    'outputFormat': 'Findings with evidence', 'acceptanceCriteria': 'Evidence per finding',
    'model': None, 'skills': None, 'tools': None, 'limits': None,
}
encoded = json.dumps(body).encode()
barrier = threading.Barrier(2)


def submit(_):
    # Separate clients preserve the authenticated cookie without sharing an opener.
    client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
    barrier.wait(timeout=10)
    return request(PATH, encoded, headers, client)


with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
    results = list(pool.map(submit, range(2)))
assert sorted(status for status, _ in results) == [200, 201], 'Expected creation and replay'
ids = {body['data']['id'] for _, body in results}
assert len(ids) == 1, 'Concurrent retry produced different agents'
agent_id = next(iter(ids))
assert all(result['data']['status'] == 'DRAFT' for _, result in results)
status, detail = request(f'{PATH}/{agent_id}')
assert status == 200 and detail['data']['responsibilities'] == body['responsibilities']
changed = dict(body, name='Changed request must conflict')
status, conflict = request(PATH, json.dumps(changed).encode(), headers)
assert status == 409 and conflict['error']['code'] == 'IDEMPOTENCY_CONFLICT'
print('PostgreSQL Agent Draft concurrent HTTP creation/replay and conflict probe passed')
