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
`{id,name,role}`. There is no signup endpoint. Recovery endpoints are described below.

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
  lose access on their next request. Recovery deletes all JDBC sessions and increments the account credential version.
  Every authenticated request checks that version, rejecting sessions saved late
  by concurrent logins or in-flight requests.

Only organization members can read organization paths; only ADMIN memberships
can write. Mission references must belong to the requested organization, including
trace lookups and team/manager references. Planning returns 501 and has no success
audit side effects. Authentication/bootstrap/logout audit records are persisted
in `authentication_audit` without credentials, session IDs, or CSRF tokens.

Operational follow-up: configure edge/IP rate limits and bounded request bodies;
apply retention to old authentication audit and expired account-key attempt rows.
Account-based throttling does not prevent an attacker from targeting many emails
or deliberately locking out another account. Controlled workspace provisioning remains a separate delivery.

## Verification

Run Java 21 `mvn spotless:apply verify`. The test profile runs Flyway V001/V002/V003
on H2 in PostgreSQL compatibility mode, including JDBC session tables, and runs
MockMvc through the real security and session filters. H2 is not evidence of
PostgreSQL lock semantics, bootstrap race behavior, or PostgreSQL restart
persistence. Main's Docker CI must run the real PostgreSQL migrations and HTTP
session/bootstrap smoke checks. No Docker runtime is available in this workspace.

## Password recovery

Recovery is disabled by default (`AGENTOS_RECOVERY_ENABLED=false`); both POSTs
return 503 while disabled, subject to normal CSRF and JSON parsing checks.
`POST /api/v1/auth/recovery/request` accepts JSON `{email}` with CSRF and returns
202 `ApiResponse.data {accepted:true}` for known, unknown, disabled and throttled
emails. No request sends SMTP. A durable SHA-256 normalized-email bucket enforces
a 60-second cooldown for every identifier. This is response-shape uniformity;
actual timing distributions remain a PostgreSQL/edge acceptance check, not a
proven constant-time guarantee. Edge/IP and global abuse controls, bounded request
bodies and retention of old buckets/jobs remain deployment responsibilities.

`POST /api/v1/auth/recovery/confirm` accepts JSON `{token,password}` with CSRF.
Success is 204; invalid/expired/consumed or disabled-account tokens return generic
400 `INVALID_RESET_TOKEN`. Passwords require at least 14 Unicode code points and
at most 72 UTF-8 bytes, using BCrypt cost 12. No GET endpoint validates a token.
A reset increments `credential_version`, consumes every account token, cancels
pending mail jobs, revokes sessions by UUID principal index and clears login
failure state atomically. Principal credential versions are excluded from JSON.

Requests lock the account before inserting work; that acquisition is the enqueue
linearization point. Confirm and dispatch also lock account before job/token
mutations. A request serialized after confirm is a new eligible request; all work
serialized before it is canceled. Dispatch claims one account and one job using
`FOR UPDATE SKIP LOCKED`, with a transaction per job. New issuance invalidates
previous tokens. Tokens contain 32 random bytes encoded URL-safe without padding;
only SHA-256 digests persist. The 15-minute expiry is an engineering default.
The outbox stores account IDs and job metadata, never tokens or message bodies.
A password-change notification is queued atomically with reset and uses the same
SMTP worker without any password or reset link in its message.

Configure `AGENTOS_RECOVERY_FRONTEND_BASE_URL` to the trusted frontend base URL
and `AGENTOS_RECOVERY_FROM` (local example: `no-reply@agentos.local`). HTTPS is
required except localhost/loopback HTTP; userinfo, query and fragments are rejected.
Email links use `/reset#token=...`; request Host never controls the destination.
`SPRING_MAIL_HOST` and `SPRING_MAIL_PORT` configure JavaMail (defaults localhost,
1025). SMTP authentication/STARTTLS settings and credentials use `AGENTOS_SMTP_*`.
Connect/read/write timeouts each default to 5000ms and can be configured via
`AGENTOS_SMTP_CONNECTION_TIMEOUT`, `AGENTOS_SMTP_READ_TIMEOUT` and
`AGENTOS_SMTP_WRITE_TIMEOUT`. Production operators must configure trusted SMTP.
Do not enable JavaMail debug logging or request-body/transport secret logging.

The scheduler defaults to 1000ms (`AGENTOS_RECOVERY_DISPATCH_DELAY_MS`); public
`RecoveryService.dispatch()` allows deterministic service tests. SMTP failures
retry at most five times, delayed 60 seconds by default
(`AGENTOS_RECOVERY_RETRY_DELAY_SECONDS`). SENT means SMTP acknowledgement, not
inbox delivery. SMTP executes while account/job locks are held; timeout durations
therefore bound individual I/O waits, not total lock latency under arbitrary load.
SMTP acknowledgement and DB commit are not atomic: a crash between them may
produce a delivered but invalid link and a retried job. Exactly-once mail delivery
is not claimed. Failed attempts remove their token digest and never mark SENT.
Tests mock mail; real PostgreSQL locking, restart behavior, timing and Mailpit
SMTP evidence must come from CI.
