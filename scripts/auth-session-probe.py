"""Disposable CI probe: persist a session across a real backend restart.

Never use a real person's credentials. The state file contains a session secret;
it is mode 0600, ignored by Git and removed after the resume phase.
"""

import http.cookiejar
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.parse
import urllib.request


def main():
    phase = sys.argv[1]
    state = Path("auth-session-probe.json")
    base = os.environ["E2E_BASE_URL"].rstrip("/")
    jar = http.cookiejar.CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))

    def request(path, body=None, headers=None):
        operation = urllib.request.Request(base + path, data=body, headers=headers or {})
        with opener.open(operation, timeout=20) as response:
            data = response.read()
            return json.loads(data) if data else None

    def csrf():
        token = request("/api/v1/auth/csrf")["data"]
        return {token["headerName"]: token["token"]}

    if phase == "save":
        headers = csrf()
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        body = urllib.parse.urlencode({
            "username": os.environ["E2E_EMAIL"],
            "password": os.environ["E2E_PASSWORD"],
        }).encode()
        identity = request("/api/v1/auth/login", body, headers)["data"]["id"]
        cookie = next(cookie for cookie in jar if cookie.name == "SESSION")
        descriptor = os.open(state, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(descriptor, "w") as output:
            json.dump({"identity": identity, "cookie": cookie.value}, output)
        print("PASS: session issued; secret state saved privately for restart probe")
    elif phase == "resume":
        saved = json.loads(state.read_text())
        try:
            # Explicit cookie header preserves the exact pre-restart credential.
            opener.addheaders = [("Cookie", "SESSION=" + saved["cookie"])]
            identity = request("/api/v1/auth/session")["data"]["id"]
            assert identity == saved["identity"], "Restart changed identity"
            request("/api/v1/auth/logout", b"", csrf())
            try:
                request("/api/v1/auth/session")
            except urllib.error.HTTPError as error:
                assert error.code == 401, "Unexpected replay rejection status"
            else:
                raise AssertionError("Logged-out session was accepted")
            print("PASS: exact session survives backend restart; logout rejects replay")
        finally:
            state.unlink(missing_ok=True)
    else:
        raise ValueError("Use save or resume")


if __name__ == "__main__":
    main()
