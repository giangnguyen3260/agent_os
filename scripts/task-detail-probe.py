"""Read-only Task Detail acceptance against the shared disposable PostgreSQL fixture."""
import datetime
import http.cookiejar
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get('E2E_BASE_URL', 'http://127.0.0.1:18080')
ORG = '20202020-2020-4020-8020-202020202020'
FOREIGN = '21212121-2121-4121-8121-212121212121'


def ident(prefix, n):
    return f'{prefix}0000-0000-4000-8000-{n:012d}'


TEAM, MISSION = ident('2010', 1), ident('2030', 1)
ROOT = f'/api/v1/organizations/{ORG}/teams/{TEAM}/missions/{MISSION}/tasks/'
client = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
FORBIDDEN = re.compile(r'"(?:payload|actorId|actor_id|goal|spec|owner|run|runs|output|outputs|privateMissionContext|passwordHash|password_hash)"\s*:|PRIVATE_CONTROL|OTHER_MISSION_TASK_PRIVATE|OTHER_TEAM_TASK_PRIVATE|FOREIGN_TASK_PRIVATE')


def request(path, data=None, headers=None):
    try:
        response = client.open(urllib.request.Request(BASE + path, data=data, headers=headers or {}), timeout=20)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        raw = response.read().decode()
        assert not FORBIDDEN.search(raw), f'Forbidden raw field/value: {path}'
        return response.status, json.loads(raw)


def sql(query):
    return subprocess.check_output(['docker', 'compose', 'exec', '-T', 'postgres', 'sh', '-c',
        'psql -t -A -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'],
        cwd=Path(__file__).resolve().parents[1], input=query, text=True, timeout=20).strip()


def normalize(row):
    return {key: datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))
            if value is not None and key in ('createdAt', 'updatedAt', 'waitingSince') else value
            for key, value in row.items()}


def main():
    status, body = request('/api/v1/auth/csrf')
    assert status == 200
    csrf = body['data']
    status, _ = request('/api/v1/auth/login', urllib.parse.urlencode({
        'username': 'mission-workspace-admin@example.test', 'password': os.environ['E2E_PASSWORD']
    }).encode(), {csrf['headerName']: csrf['token'], 'Content-Type': 'application/x-www-form-urlencoded'})
    assert status == 200
    snapshot = '\n'.join(f'SELECT row_to_json(x)::text FROM {table} x ORDER BY id;'
                         for table in ('teams', 'agents', 'missions', 'tasks', 'audit_events'))
    snapshot += '\nSELECT row_to_json(x)::text FROM mission_create_receipts x ORDER BY organization_id,account_id,idempotency_key;'
    before = sql(snapshot)
    expected = json.loads(sql(f'''SELECT json_agg(x)::text FROM
        (SELECT id,organization_id AS "organizationId",team_id AS "teamId",mission_id AS "missionId",
        title,status,pending_reason AS "pendingReason",waiting_since AS "waitingSince",resolver,
        created_at AS "createdAt",updated_at AS "updatedAt" FROM tasks
        WHERE organization_id='{ORG}' AND team_id='{TEAM}' AND mission_id='{MISSION}' ORDER BY id) x;'''))
    assert len(expected) == 31
    for task in expected:
        path = ROOT + task['id']
        status, body = request(path)
        assert status == 200, (path, status)
        detail = body['data']
        assert set(detail) == {'generatedAt', 'task', 'mission', 'team'}
        datetime.datetime.fromisoformat(detail['generatedAt'].replace('Z', '+00:00'))
        assert normalize(detail['task']) == normalize(task), task['id']
        assert detail['mission'] == {'id': MISSION, 'title': 'Mission Control Review'}
        assert detail['team'] == {'id': TEAM, 'name': 'Mission Control Delivery'}
        expected_events = json.loads(sql(f'''SELECT coalesce(json_agg(x),'[]')::text FROM
            (SELECT id,event_type AS "eventType",actor_type AS "actorType",created_at AS "createdAt"
            FROM audit_events WHERE organization_id='{ORG}' AND task_id='{task['id']}'
            AND (mission_id IS NULL OR mission_id='{MISSION}') ORDER BY created_at DESC,id) x;'''))
        actual = []
        for page in range(max(1, (len(expected_events) + 19) // 20)):
            status, body = request(f'{path}/activity?page={page}')
            assert status == 200
            data = body['data']
            assert set(data) == {'items', 'page', 'size', 'total'}
            assert (data['page'], data['size'], data['total']) == (page, 20, len(expected_events))
            assert len(data['items']) == len(expected_events[page * 20:(page + 1) * 20])
            actual.extend(data['items'])
        assert list(map(normalize, actual)) == list(map(normalize, expected_events))
        if task['id'] == ident('2040', 1):
            assert [e['id'] for e in actual] == [ident('2050', n) for n in range(24, 0, -1)]
    path = ROOT + ident('2040', 1)
    invalid = [(path.replace(ORG, FOREIGN), 403)]
    invalid += [(path.replace(old, new), 404) for old, new in (
        (TEAM, ident('2010', 2)), (TEAM, ident('2010', 999)), (TEAM, ident('2110', 1)),
        (MISSION, ident('2030', 2)), (MISSION, ident('2030', 3)),
        (MISSION, ident('2030', 4)), (MISSION, ident('2130', 1)),
        (ident('2040', 1), ident('2040', 32)), (ident('2040', 1), ident('2040', 33)),
        (ident('2040', 1), ident('2140', 1)), (ident('2040', 1), ident('2040', 999)))]
    for invalid_path, expected_status in invalid:
        for suffix in ('', '/activity?page=0'):
            assert request(invalid_path + suffix)[0] == expected_status, invalid_path + suffix
    for page in ('-1', '100001', 'bad'):
        assert request(path + '/activity?page=' + page)[0] == 400
    status, body = request(path + '/activity?page=100000')
    assert status == 200 and body['data']['items'] == []
    assert before == sql(snapshot), 'Task GETs changed persisted business rows'
    print('PASS: 31 SQL task projections, exact safe activity, all scope boundaries, raw exclusions, zero business writes')


if __name__ == '__main__':
    main()
