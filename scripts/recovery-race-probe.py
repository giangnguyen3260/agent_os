"""Disposable CI: actual HTTP login/reset with a PostgreSQL row-lock barrier.

No production test hooks, host database port, token files or secret output.
"""

from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.cookiejar
import json
import os
import queue
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


PG = ["docker", "compose", "exec", "-T", "postgres", "sh", "-c",
      'exec psql -X -qAt -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"']


def sql(statement):
    result = subprocess.run(PG, input=statement, text=True, capture_output=True, timeout=12)
    if result.returncode:
        raise AssertionError("PostgreSQL race assertion failed; no SQL/secret output retained")
    return result.stdout.strip()


class Client:
    def __init__(self, base):
        self.base = base.rstrip("/")
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, body=None, headers=None):
        request = urllib.request.Request(self.base + path, body, headers or {})
        try:
            response = self.opener.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            return response.status, json.loads(raw) if raw else None

    def csrf(self):
        status, body = self.call("/api/v1/auth/csrf")
        assert status == 200, "CSRF bootstrap failed"
        token = body["data"]
        return {token["headerName"]: token["token"]}


def main():
    base = os.environ["E2E_BASE_URL"]
    mailbox = Client(os.environ["E2E_MAILPIT_URL"])
    email = "recovery-race@example.test"
    initial = os.environ["E2E_PASSWORD"]
    login, reset = Client(base), Client(base)
    headers = reset.csrf() | {"Content-Type": "application/json"}
    status, _ = reset.call("/api/v1/auth/recovery/request",
                           json.dumps({"email": email}).encode(), headers)
    assert status == 202, "Recovery request failed"
    token = None
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        _, listing = mailbox.call("/api/v1/messages")
        for message in listing.get("messages", []):
            if not any(to.get("Address") == email for to in message.get("To", [])):
                continue
            _, details = mailbox.call("/api/v1/message/" + message["ID"])
            match = re.search(r"/reset#token=([A-Za-z0-9_-]{43})", details.get("Text", ""))
            if match:
                token = match.group(1)
                break
        if token:
            break
        time.sleep(0.2)
    assert token, "Real SMTP reset link not received"
    key = hashlib.sha256(email.encode()).hexdigest()
    sql("INSERT INTO authentication_attempts(account_key, failures) VALUES ('" + key
        + "',0) ON CONFLICT (account_key) DO UPDATE SET failures=0;")
    login_headers = login.csrf() | {"Content-Type": "application/x-www-form-urlencoded"}
    reset_headers = reset.csrf() | {"Content-Type": "application/json"}
    barrier = subprocess.Popen(PG, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               stderr=subprocess.DEVNULL, text=True, bufsize=1)
    lines = queue.Queue()
    threading.Thread(target=lambda: [lines.put(line.strip()) for line in barrier.stdout],
                     daemon=True).start()
    try:
        barrier.stdin.write("SET statement_timeout='15s'; SET lock_timeout='10s';\n"
                            "SELECT 'BARRIER_PID=' || pg_backend_pid();\n"
                            "BEGIN; SET LOCAL idle_in_transaction_session_timeout='20s';\n"
                            "SELECT id FROM human_accounts WHERE email='" + email
                            + "' FOR UPDATE;\n\\echo ACCOUNT_LOCKED\n")
        barrier.stdin.flush()
        deadline = time.monotonic() + 10
        barrier_pid = None
        while True:
            line = lines.get(timeout=max(0.1, deadline - time.monotonic()))
            if line.startswith("BARRIER_PID="):
                barrier_pid = int(line.split("=", 1)[1])
            if line == "ACCOUNT_LOCKED":
                break
            assert time.monotonic() < deadline, "Barrier not established"
        assert barrier_pid, "Barrier PostgreSQL connection not identified"
        with ThreadPoolExecutor(max_workers=2) as pool:
            auth = pool.submit(login.call, "/api/v1/auth/login",
                               urllib.parse.urlencode({"username": email, "password": initial}).encode(),
                               login_headers)
            deadline = time.monotonic() + 8
            while True:
                blocked = sql("SELECT pid FROM pg_stat_activity WHERE wait_event_type='Lock' "
                              "AND query ILIKE '%human_accounts%for update%' AND "
                              + str(barrier_pid) + " = ANY(pg_blocking_pids(pid));")
                if blocked:
                    login_pid = int(blocked.splitlines()[0])
                    break
                assert time.monotonic() < deadline and not auth.done(), "Login did not wait account-first"
                time.sleep(0.1)
            # The old attempt-first implementation cannot satisfy this NOWAIT assertion.
            sql("BEGIN; SELECT account_key FROM authentication_attempts WHERE account_key='"
                + key + "' FOR UPDATE NOWAIT; ROLLBACK;")
            replacement = "Race-" + os.urandom(20).hex()
            changed = pool.submit(reset.call, "/api/v1/auth/recovery/confirm",
                                  json.dumps({"token": token, "password": replacement}).encode(),
                                  reset_headers)
            deadline = time.monotonic() + 6
            while True:
                waiting_reset = sql("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock' "
                                    "AND query ILIKE '%human_accounts%for update%' AND pid<>"
                                    + str(login_pid) + " AND (" + str(barrier_pid)
                                    + " = ANY(pg_blocking_pids(pid)) OR " + str(login_pid)
                                    + " = ANY(pg_blocking_pids(pid)));")
                if int(waiting_reset) > 0:
                    break
                assert time.monotonic() < deadline and not changed.done(), "Reset did not reach the barrier"
                time.sleep(0.1)
            barrier.stdin.write("COMMIT;\n\\q\n")
            barrier.stdin.flush()
            barrier.wait(timeout=8)
            auth_status, _ = auth.result(timeout=15)
            reset_status, _ = changed.result(timeout=15)
            assert auth_status == 200 and reset_status == 204, "Concurrent commands failed"
            assert login.call("/api/v1/auth/session")[0] == 401, "Old credential session survived reset"
        print("PASS: real PostgreSQL account-first barrier, concurrent login/reset and stale-session denial")
    finally:
        if barrier.poll() is None:
            barrier.terminate()
            barrier.wait(timeout=8)


if __name__ == "__main__":
    main()
