# Password recovery delivery

Branch `feature/auth-recovery` builds on `feature/auth-login` (PR2). Recovery is a separate serial flow; neither parent nor recovery is automatically approved for merge. This work does not complete workspace management or the full application.

## Designed states

All15 Figma high-fidelity contexts/screenshots were inspected before acceptance review: desktop forgot/sent/reset/expired/success `71:6395`, `71:6425`, `71:6444`, `71:6471`, `71:6491`; tablet `71:7203`, `71:7229`, `71:7248`, `71:7275`, `71:7295`; mobile `71:8007`, `71:8033`, `71:8052`, `71:8079`, `71:8099`. Reuse Forest tokens and existing Angular auth controls, not the generated React snippets. Example credentials and prototype-only success text do not ship.

Routes are `/forgot-password` and `/reset`; states change only after real API outcomes. The email link uses `/reset#token=…`. The component reads then immediately removes the fragment from browser history and keeps the token only in component memory. No token query string, local/session storage or automatic password change on page load. Reload after fragment erasure shows an invalid-link state; reopening the email link restores the form while the token remains valid. The password policy is displayed before entry.

## API and security contract

POST `/api/v1/auth/recovery/request`, JSON `{email}`, requires CSRF. With recovery enabled, known/unknown/disabled/throttled identifiers receive202 with `{data:{accepted:true}}`; that means request acknowledged, not proof of SMTP delivery or account existence. Recovery disabled returns503 uniformly. A durable job schedules real SMTP delivery outside the public request path.

POST `/api/v1/auth/recovery/confirm`, JSON `{token,password}`, requires CSRF. Successful committed reset returns204 without auto-login. Missing/invalid/expired/consumed token returns400 `INVALID_RESET_TOKEN`. New password requires at least14 Unicode characters and at most72 UTF-8 bytes for BCrypt. FE also requires matching confirmation; BE independently enforces credential policy.

Tokens contain32 cryptographically random bytes; only SHA-256 digests are stored. The engineering defaults are15-minute expiry,60-second per-email-digest request cooldown, five mail attempts with a retry delay. These are implementation defaults pending final business-policy review, not invented user approvals. Mail jobs contain account IDs and state, never raw tokens or message bodies. The worker holds account/job locks in a consistent order, invalidates previous token generations and records SENT only after SMTP acknowledgement. SMTP cannot guarantee exactly-once delivery; duplicate/uncertain deliveries must not enable token replay.

Confirmation serializes per account, changes the password, consumes all prior reset tokens, cancels queued reset jobs, clears login throttling, revokes JDBC sessions and increments credential version. Request-time version checks also reject a late-created session authenticated against an old password during a concurrent reset. Notification email contains no password or token.

Trust only the explicitly configured frontend base URL; never derive reset links from a request Host header. HTTPS is required except explicit loopback development HTTP. Public deployment also requires trusted SMTP/TLS credentials, edge abuse controls, retention policy and additional security/load verification; local Mailpit is a development inbox, not external delivery.

## Run on a Mac with Docker

Follow `AUTH_RUNTIME.md` for initial human provisioning. Root Compose includes Mailpit, binding only its web UI to `127.0.0.1:8025`; SMTP remains on the internal Docker network. Open http://localhost:8080/login, choose Quên mật khẩu, submit the provisioned email, then open http://localhost:8025 to read the real captured SMTP email. No external email is sent or relayed. Treat the development inbox as sensitive because it contains reset links.

`AGENTOS_RECOVERY_FRONTEND_BASE_URL` must match the browser origin, including `APP_PORT` if overridden. `MAILPIT_PORT` controls only the inbox web port. Application recovery defaults disabled outside Compose; Compose explicitly enables it for local testing. Configure an external SMTP provider separately before claiming delivery to a real inbox.

## Required verification

Backend tests must cover generic responses, CSRF, disabled/malformed/expired/replayed tokens, password byte/Unicode boundaries, concurrent token consumption, stale credentials, invalidation, SMTP failure/retry and configured URL validation. H2 passes do not establish PostgreSQL lock semantics or timing indistinguishability. Docker CI provides actual PostgreSQL+Mailpit and browser integration at1440/768/390, with isolated per-viewport accounts so reset does not mutate the base login fixture.

QC records execution on exact commits. Full UI visual/accessibility, failure modes, statistical timing and multi-instance/load results remain distinct from a compiler or happy-path pass. Sonar is temporarily paused by user instruction.

## Verified development gate — 2026-10-19

Application and hardened probe source `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` passed [quality run39183379255](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379255) and [Docker run39183379306](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379306):29 backend tests,24 browser instances across three viewports, real SMTP via Mailpit, PostgreSQL account-first lock barrier with overlapping login/reset and stale-session denial, and session persistence/replay denial across a backend restart. This is development-use readiness for the recovery flow, not full-application or production-security acceptance.

Replacement tokens supersede older tokens only after SMTP acknowledges the new message. A failed replacement attempt removes only its own digest, preserving the previously delivered link. The mocked-failure regression verifies this behavior; real transport outage/crash/ambiguous-ack guarantees remain separate operational work. The final evidence/documentation publication does not change the tested application or probe source.
