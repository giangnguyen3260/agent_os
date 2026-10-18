# Login foundation and proposed operating defaults

The server exposes public `GET /api/v1/auth/csrf`, returning
`ApiResponse.data {token, headerName}`. Send the token in that header or `_csrf`
for URL-encoded `POST /api/v1/auth/login` (`username` is the email, `password`).
Login returns `ApiResponse.data {id,email,displayName}`. Unknown, disabled, wrong
and throttled credentials all return 401 `INVALID_CREDENTIALS` with the same
message. Missing/invalid CSRF returns 403. Fetch a fresh CSRF token after login
and logout: authentication changes the session ID and clears the previous token.
`GET /api/v1/auth/session` returns the current identity or JSON 401.
`POST /api/v1/auth/logout` requires CSRF and returns 204, invalidating the JDBC
session. `GET /api/v1/auth/workspaces` returns actual membership rows with
`{id,name,role}`. There is no signup or recovery endpoint.

Passwords use BCrypt cost 12. All credential inputs exceeding 72 UTF-8 bytes
are rejected; bootstrap requires at least 14 characters. Email is trimmed and
lowercased. Credentials and tokens are never included in audit records.

Initial operator bootstrap is disabled unless `AGENTOS_BOOTSTRAP_ENABLED=true`.
Set `AGENTOS_BOOTSTRAP_EMAIL`, `AGENTOS_BOOTSTRAP_DISPLAY_NAME`, and
`AGENTOS_BOOTSTRAP_PASSWORD`. A PostgreSQL transaction advisory lock serializes
bootstrap across instances. An existing account prevents bootstrap from running.
No organization, membership, or example data is created. An operator must
provision memberships through a controlled database procedure; global organization
listing and creation are denied for every web user until secure provisioning is
implemented. The bootstrap account has no implicit cross-organization privilege.
Remove bootstrap secrets from the deployment environment after initial creation.

Proposed operating defaults, pending business approval:

- 30-minute idle session timeout, configurable with `AGENTOS_SESSION_TIMEOUT`.
- No concurrent-session cap. Sessions are persisted in PostgreSQL via Spring
  Session JDBC, with expiration cleanup managed by Spring Session.
- `SESSION` cookie is HttpOnly, SameSite=Lax, Secure by default. Explicitly set
  `AGENTOS_SESSION_COOKIE_SECURE=false` only for local HTTP development.
- Five failures per normalized email in a 15-minute fixed window. The sixth
  attempt is blocked, including a correct password, until the window expires.
  PostgreSQL row locks serialize attempts for an account across instances.
  Unknown emails use the same flow and dummy BCrypt verification. Keys are
  SHA-256 digests of normalized emails, not plaintext identifiers.
- Successful authentication resets failure count. Disabled/deleted accounts
  lose access on their next request. Password changes affect new logins;
  existing sessions remain valid until logout, idle expiry, or explicit operator
  deletion from `spring_session`. A future password-change/recovery flow must
  explicitly invalidate existing sessions.

Only organization members can read organization paths; only ADMIN memberships
can write. Mission references must belong to the requested organization, including
trace lookups and team/manager references. Planning returns 501 and has no success
audit side effects. Authentication/bootstrap/logout audit records are persisted
in `authentication_audit` without credentials, session IDs, or CSRF tokens.

Operational follow-up: configure edge/IP rate limits and bounded request bodies;
apply retention to old authentication audit and expired account-key attempt rows.
Account-based throttling does not prevent an attacker from targeting many emails
or deliberately locking out another account. Recovery and controlled workspace
provisioning remain separate deliveries.

## Verification

Run Java 21 `mvn spotless:apply verify`. The test profile runs Flyway V001/V002
on H2 in PostgreSQL compatibility mode, including JDBC session tables, and runs
MockMvc through the real security and session filters. H2 is not evidence of
PostgreSQL lock semantics, bootstrap race behavior, or PostgreSQL restart
persistence. Main's Docker CI must run the real PostgreSQL migrations and HTTP
session/bootstrap smoke checks. No Docker runtime is available in this workspace.
