# Login runtime and local setup

This feature branch implements human-account login; it does not complete the full designed application. Password recovery is not implemented and explicitly reports that limitation. Workspace display is an authenticated membership hand-off, not completed workspace management. Existing static business previews are not reachable from the authenticated application until their real implementations are delivered.

## Initial account on a local Mac

Use Docker Desktop and the root Compose stack. Copy `.env.example` to `.env` only if no `.env` exists. Set a private PostgreSQL password. Set `AGENTOS_BOOTSTRAP_ENABLED=true`, your own `AGENTOS_BOOTSTRAP_EMAIL`, `AGENTOS_BOOTSTRAP_DISPLAY_NAME` and a unique `AGENTOS_BOOTSTRAP_PASSWORD` of at least14 characters and at most72 UTF-8 bytes. Never paste that password into a ticket/chat or commit `.env`.

Run `docker compose up --build -d --wait`. Open http://localhost:8080/login. Once the initial account is created, set bootstrap to false, remove its password from `.env`, and run `docker compose up -d --force-recreate backend`. The account persists in PostgreSQL; restarting does not reset its password. Bootstrap never creates sample teams, agents or workspace memberships. An account without membership sees a truthful empty workspace hand-off; provisioning/workspace management is a later screen dependency.

Do not delete the PostgreSQL volume to troubleshoot a login or migration failure. Inspect backend logs and Flyway history. Existing volumes are upgraded by V002; changing `.env` alone does not change an already initialized database user's password.

## Implemented API contract

| Endpoint | Contract |
|---|---|
| GET `/api/v1/auth/csrf` | Public; `data.token` and `data.headerName`, tied to the browser session |
| POST `/api/v1/auth/login` | URL-encoded `username` email and `password`; fresh CSRF header required; generic credential errors |
| GET `/api/v1/auth/session` | Authenticated human identity only; no credential/hash fields |
| GET `/api/v1/auth/workspaces` | Current persisted organization memberships, not all organizations |
| POST `/api/v1/auth/logout` | CSRF-protected invalidation;204 success |

Sessions use Spring Session JDBC and PostgreSQL. Passwords use BCrypt cost12; no bearer credential is stored in localStorage. Current implementation defaults are30 minutes inactivity and five failed attempts per normalized-email digest per15-minute window; these are documented engineering defaults, not a claim that the business has approved every policy. They are not an absolute session TTL or a complete network abuse-control solution. The per-account lockout does not replace ingress rate limiting, bot defenses, retention/cleanup or security/load testing before public deployment.

The session cookie is HttpOnly/SameSite=Lax and Secure by default in the application. Root Compose explicitly disables Secure for localhost HTTP; HTTPS deployment must set `AGENTOS_SESSION_COOKIE_SECURE=true`. Do not expose this local configuration publicly.

Domain APIs require identity and current organization membership. Writes require ADMIN membership. The broad unscoped organization list/create endpoint is denied until controlled organization provisioning is delivered. This minimal ADMIN/MEMBER boundary is not the complete designed team-role/approval-grant system. Planning returns501 while its implementation remains absent; it never claims to have generated tasks.

## Verification

`mvn -f backend/pom.xml verify` runs actual Spring Security/session integration cases plus foundation tests. H2 integration results do not replace PostgreSQL migration verification. Docker CI starts the real PostgreSQL stack with an ephemeral account and runs `npm run test:e2e` from frontend at1440/768/390 widths. Browser tests require `E2E_EMAIL`, `E2E_PASSWORD` and `E2E_BASE_URL` pointing to a disposable environment; no default credentials are embedded. Never run the negative-credential/lockout suite against a real person's account.

Do not call login Accepted until the exact revision has independent QC, real PostgreSQL migration/session evidence, rendered visual comparison and the required recovery/integration dependencies resolved. Sonar remains temporarily paused by user instruction.
