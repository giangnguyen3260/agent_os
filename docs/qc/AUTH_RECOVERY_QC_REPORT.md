# Independent password recovery QC

Review date: 2026-10-18. **Status: NOT ACCEPTED; implementation in progress.**
Baseline HEAD: `a36b22cd4efb4ae3b41707f11c5e6b96dbf69dc3`. Reviewed a changing, uncommitted working tree; this is not exact-final-revision certification. Initial inspection found no recovery implementation; backend RecoveryService/RecoveryController/V003 appeared during the subsequent source inspection. No repeated polling performed.

Ownership: QC edits this report only. BE worker Herschel owns backend implementation; FE recovery worker owns frontend; main owns SMTP, Compose, CI and tests. Full application remains incomplete. Parent PR #2 is not approved for merge by this review; keep it unmerged.

## Actionable findings

### R1 — High: enqueue/confirm serialization is incomplete

`backend/src/main/java/com/agentos/auth/RecoveryService.java`, `request`, `confirm`, `dispatch`.

Confirm deletes PENDING jobs while holding the account row lock. Dispatch also locks the account before its job, which closes the direct dispatch/confirm inversion and stale pending-job path. Request, however, reads the enabled account without an account lock and inserts an unversioned job. An overlapping request can read the account before reset, pause, then insert after confirm's pending-job deletion/commit. Dispatch can subsequently issue a fresh valid token from that pre-reset request. Account foreign keys do not establish the intended reset-generation ordering.

Action: serialize eligibility/read/enqueue with the same account lock, or stamp jobs with an account recovery generation captured before the overlap and reject stale generations under lock. Explicitly define the linearization point for overlapping requests. Add a barrier-controlled PostgreSQL regression: pause request after account lookup, confirm an existing token, resume enqueue, dispatch, and prove the pre-reset job cannot create a valid token. Sequential cancellation alone is insufficient evidence.

### R2 — Medium: request timing equivalence is unproven

Known enabled accounts perform an additional durable job insertion; unknown and disabled accounts do not. All return the same controller 202 body, but this does not establish comparable timing under database latency/contention. Throttled requests also take a shorter path.

Action: run randomized, interleaved batches for known/unknown/disabled identifiers, with cold/warm and throttled cases, against the actual stack. Record distributions and the agreed acceptance criterion; assess whether scheduling or response handling needs adjustment. Do not claim timing privacy from the generic body alone.

### R3 — Medium: SMTP transaction and concurrency evidence required

Dispatch holds the account/job transaction through `mail.send`. This preserves useful ordering but makes SMTP timeouts part of account lock duration. Inspect main's connect/read/write timeout configuration and demonstrate bounded failure behavior. Confirm and dispatch visibly use account-first locking; request uses a rate-bucket lock and foreign-key insertion and needs inclusion in the complete lock analysis. No deadlock is claimed proven here.

SMTP acceptance occurs before the database commit. A crash after SMTP acceptance can leave a delivered link invalid and a job retried. Document this outcome honestly and exercise retry/restart behavior; do not equate SMTP acknowledgement with inbox delivery or claim exactly-once delivery.

## Source observations — not runtime passes

- Service uses SecureRandom with 32 bytes, URL-safe encoding, SHA-256 token digests and a 15-minute expiry. V003 stores digests, account IDs and job metadata, not raw token/mail bodies. No token is returned by the recovery controller.
- Confirm locks the account then rechecks/locks an unexpired token, updates the adaptive password hash, deletes all account tokens and pending jobs, deletes JDBC sessions by account UUID principal name, and clears login attempts in one transaction.
- `HumanIdentity.getName()` returns UUID, matching confirm's session deletion key. Concurrent login/session-save races still require testing.
- Disabled accounts are excluded at enqueue and rechecked at dispatch/confirm.
- Dispatch creates tokens only when processing a durable job. MailException deletes that attempt's digest and schedules retry; the fifth failure marks FAILED. Raw token exists transiently for mail construction only in the reviewed service.
- Link uses configured frontend base URL, not request Host; validation rejects credentials/query/fragment and requires HTTPS except loopback HTTP. Token travels in the intended email URL fragment (`/reset#token=...`), not API response URLs. Browser history removal, referrer behavior and client persistence remain FE review items.
- Per-normalized-email digest locking provides a 60-second request throttle in source. This does not prove distributed behavior or sufficient abuse controls; unique-email requests can still grow rate-bucket storage.
- Initial FE inspection showed the existing unavailable-recovery modal and no reset route. FE implementation has not been accepted or tested here.

## Required acceptance checklist

Every row remains OPEN until independent evidence is attached for the final revision.

| ID | Check and required evidence | State |
|---|---|---|
| QC-01 | Inspect RNG, entropy, digest-only schema and runtime DB rows; capture response/logs without publishing raw secrets; no token in API URLs, access logs, error details, analytics or client persistence. Intended email fragment is the delivery exception. | OPEN |
| QC-02 | Valid token resets once; sequential replay fails; concurrent confirms on separate PostgreSQL connections produce exactly one success and one final password. Competing tokens for the same account cannot both succeed. | OPEN |
| QC-03 | Expired, exact-boundary, malformed, missing, unknown and already-used tokens fail without password/session changes; inactive/deleted accounts cannot recover. | OPEN |
| QC-04 | Known, unknown, disabled and throttled requests have indistinguishable public status/body and assessed timing distributions; disabled recovery truthfully returns unavailable for everyone. | OPEN |
| QC-05 | Concurrent normalized-email requests yield bounded durable work across instances; verify throttle boundary, reset, request-size bounds and distinct-email abuse policy. | OPEN |
| QC-06 | Restart after enqueue preserves real work; no fake token/sent outcome; outbox has no plaintext token, password or secret-bearing URL. SMTP success and failure persist correct job/token state. | OPEN |
| QC-07 | SMTP rejection, unavailable server, timeout, ambiguous acknowledgement and restart cause bounded attempts/backoff; no valid digest survives a failed send attempt; eventual FAILED is observable without secret logs. | OPEN |
| QC-08 | Trusted configured HTTPS link; reject unsafe config; hostile Host/Forwarded/return URL cannot influence destination; loopback exception stays local. | OPEN |
| QC-09 | Reset invalidates every prior session across browsers/instances; protected APIs reject old cookies; old password fails and new password succeeds; concurrent login and in-flight session saves cannot resurrect pre-reset access. | OPEN |
| QC-10 | Queue at least two old jobs, send first, confirm, dispatch remaining: no fresh token. Repeat with request paused before enqueue, retry paused before send, concurrent dispatchers and restart. Old generation must never regain validity. R1 blocks acceptance. | OPEN |
| QC-11 | PostgreSQL barrier tests overlap request/confirm/dispatch and simultaneous confirms with bounded completion; review account/token/job/rate-bucket/session lock order. H2 is insufficient for locking claims. | OPEN |
| QC-12 | CSRF enforced for both public POSTs; no-CSRF/invalid-CSRF fail; legitimate anonymous recovery succeeds. Validation and transport errors do not expose secrets/account existence. | OPEN |
| QC-13 | FE real request and confirm integration; generic queued wording, unavailable/network/error states, double-submit prevention, accessible forms and responsive review. Fragment is removed promptly from history and retained only transiently in memory; no storage/service-worker/analytics leakage. | OPEN |
| QC-14 | Inspect exact-revision CI artifacts: backend tests, PostgreSQL migrations/concurrency, real SMTP acknowledgement/failure and browser recovery. Build or login-only tests are insufficient. Sonar remains paused, not passed. | OPEN |

## Verification actually performed

Read auth controller/provider, security filters, identity principal, V002/V003 schema, recovery controller/service, application recovery configuration, auth frontend service/routes, login recovery references, auth integration-test references, runtime/security/delivery documentation and quality workflow. Static inspection only; no recovery test pass is asserted.

The current quality workflow invokes Maven verify and frontend lint/format/tooling/build, but its text alone proves neither execution nor recovery coverage. No remote CI result was reviewed.

Local prerequisite check: `command -v mvn` produced no executable; `java -version` reported OpenJDK 17, while the project requires Java 21. Therefore Maven recovery checks were not run in this review. Do not modify another worker's tooling or tests to manufacture evidence. Main should supply exact-revision Java 21/real PostgreSQL/SMTP CI evidence for independent review.

Next review is triggered by supplied implementation/test evidence, not polling. Re-read the final diff, resolve findings with precise source and test evidence, and record command, revision, environment, result and limitations before marking any check passed.
