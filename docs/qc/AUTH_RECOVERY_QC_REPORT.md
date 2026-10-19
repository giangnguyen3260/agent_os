# Independent password recovery QC

Review finalized: 2026-10-19. **Disposition: READY FOR DEVELOPMENT USE within the agreed functional scope. Production security acceptance is not claimed.**

Exact source: `9a4159c5682b0c990471360c0b2d5c1a3b050bc4`. Both workflows checked out PR merge `f4bb0fe`, merging that source into parent `a36b22cd4efb4ae3b41707f11c5e6b96dbf69dc3`. This disposition concerns that tested source/parent pairing; subsequent application changes require relevant validation.

## Independently verified evidence

QC fetched completed job summaries and logs directly from GitHub. Both jobs concluded **success**, including cleanup.

| Gate | Result and exact evidence |
|---|---|
| Backend and quality | [Quality run 39183379255 / job 117840666063](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379255/job/117840666063): **29 tests, zero failures/errors/skips**, including all nine recovery tests; Maven BUILD SUCCESS at 01:01:58Z. Import, formatting, frontend lint/format/tooling/build steps succeeded. |
| Browser and real local SMTP | [Docker run 39183379306 / job 117840666525](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379306/job/117840666525): **24 passed (21.6s)** at 01:04:35Z, comprising 12 login and 12 recovery instances across desktop/tablet/mobile. Real Mailpit-link reset/session-revocation tests passed at 01:04:21Z, 01:04:28Z and 01:04:35Z respectively. |
| Hardened PostgreSQL race probe | Same Docker job: CI applied statement_timeout=15s and lock_timeout=10s; **PASS: real PostgreSQL account-first barrier, concurrent login/reset and stale-session denial** at 01:04:38Z. Reviewed probe observes barrier/blocker PID relationships, attempt NOWAIT and confirm overlap before release; dedicated step timeout applies. |
| Parent restart compatibility | Same Docker job: session issued at 01:04:38Z; **PASS: exact session survives backend restart; logout rejects replay** at 01:05:07Z. Four disposable recovery accounts isolate reset mutations from the parent login fixture. |

Docker's backend build independently also reported 29 tests with zero failures/errors/skips and BUILD SUCCESS at 01:03:02Z. PostgreSQL migration/schema, API checks and disposable-account provisioning steps succeeded. No local runtime pass is substituted for this CI evidence.

## Bounded finding disposition

| Finding | Functional development disposition |
|---|---|
| R1 old queued-job issuance | Accepted in source using account-lock acquisition as the agreed linearization point. Enqueue/confirm/dispatch serialize on account; confirm deletes old tokens/PENDING jobs; PASSWORD_CHANGED jobs cannot issue tokens. Broader queued-retry/multiple-dispatcher/multi-replica stress remains follow-up, not a functional blocker. |
| R4 account/attempt/audit inversion | **Closed for the identified defect and agreed development gate.** Account -> attempt -> audit order is fixed; H2 regression and hardened real PostgreSQL login/reset barrier passed. This does not claim all possible deadlocks are excluded. |
| R5 failed replacement deleting previous link | **Closed for the identified defect within tested scope.** Old digests are deleted only after successful send. Passing mocked MailException regression preserves/consumes the prior link; successful replacement invalidation is also covered. Real happy-path SMTP delivery is verified separately above. |
| Mail-health context error | Resolved: test annotation alone disables management.health.mail for mocked-mail context; all nine recovery tests now pass. Production mail health was not disabled by this test setting. |
| Functional flow/session behavior | Browser scope verifies real request/reset, replay rejection, prior-session denial, old-password rejection and new-password login; credential-version checks reject stale principals on subsequent requests. |

All agreed functional gate conditions are satisfied. No further production-hardening condition is added to this disposition.

## Preserved limits

- **R2 timing privacy remains UNVERIFIED:** no constant-time or indistinguishable-timing claim. This is production-hardening follow-up.
- **R3 operational guarantees remain limited:** actual Mailpit SMTP delivery passed; outage/retry/previous-link preservation failure tests use mocked JavaMailSender/MailException. Real server rejection/outage, ambiguous acknowledgement, SMTP/DB crash atomicity, sustained contention and overall transaction-duration guarantees are not established. SMTP network I/O remains inside account/job transactions.
- PostgreSQL probe proves its bounded account-first overlapping login/reset scenario. It does not certify broad multi-replica stress, all queued-job/retry schedules or a forced JDBC session-save-after-reset interleaving; an already-authorized in-flight request is not canceled by version checking.
- Main supplied nine visual checks for forgot/reset/expired across three widths. Self-hosted Inter imports were source-reviewed; QC has not independently certified final rendered typography or complete Figma parity. No extra visual acceptance claim is inferred from browser test counts.
- Sonar remains paused, not passed. External inbox delivery, production readiness and full application completion are not claimed.

QC changed only this report. Main owns publication and PR decisions. This development-readiness disposition does not authorize merging parent PR #2; keep it unmerged. PR #3 may be reviewed using this concrete evidence without treating the full app or production security as accepted.

The historical record below preserves earlier observations and pending snapshots. Its NOT ACCEPTED/OPEN/pending labels are historical and are superseded by the final disposition above where explicitly resolved.

---

# Historical review record

Review date: 2026-10-18. **Status: NOT ACCEPTED; implementation in progress.**
Current evidence summary (2026-10-19): independently verified 29/29 backend tests for source `26cdaf3d42b7f544eb9694443226ae4f4f97d607`. Current PR head `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` has identical application/test code; only probe and Docker workflow differ. Latest-head quality/Docker results remain pending in the bounded snapshot below. R5 has passing mocked-mail regression evidence; R4 has passing H2 regression evidence, with actual PostgreSQL proof pending. Functional development readiness is pending the bounded gate defined in the final section. Production security acceptance is separate and not claimed. Later dated sections supersede historical findings below.

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

## Restored-source review — 2026-10-19

This section supersedes stale current-state statements above after overnight restoration to `a7c99d5a02c8a21cfcb025c15d548aaf91ea6dd8`. QC edits this report only. Main owns the new race script/CI/font changes; BE owns reapplied implementation/test fixes. No runtime passes are asserted.

### Actual current implementation diff

- **R1 source-addressed:** request locks the enabled account before enqueue. Dispatch and confirm lock the account before job/token changes. Confirm deletes all PENDING account jobs and tokens; PASSWORD_CHANGED jobs issue no token. A request serialized after confirm is a new request at account-lock acquisition. Real PostgreSQL queued/retry/confirm regression remains open; the login/reset probe does not test that invariant.
- **R4 source-addressed in working tree:** HumanAuthenticationProvider now locks account before authentication_attempts and audit insertion, matching confirm account-before-attempt ordering and removing the previously identified FK lock cycle. This is not certified at restored remote SHA until the change is committed/tested.
- **R5 source-addressed in working tree:** prior digests are deleted only after replacement SMTP send succeeds. MailException deletes only the replacement digest. The added failedReplacementPreservesDeliveredToken test asserts the original digest remains and can reset. Existing successful replacement test asserts the old token fails.
- Test-only management.health.mail.enabled=false is present in RecoveryIntegrationTest; PASSWORD_RESET audit includes UUID/event only. A latch-based login/reset test and explicit future/executor bounds are present. Tests use H2, so PostgreSQL lock claims need the actual PG probe.
- **R3 remains open:** SMTP occurs within the account/job transaction. Configured individual network timeouts and five retries do not themselves prove an overall transaction bound or crash/ambiguous-ack behavior. Neither the new probe nor successful Mailpit delivery establishes SMTP failure recovery.
- Credential-version principal/filter checks still address a late-saved old-version session on its next request. They do not cancel an already-authorized request.

### New scripts/recovery-race-probe.py — actionable review for main

**P1 — High evidence gap: waiter identification is global.** The pg_stat_activity query counts any Lock waiter containing human_accounts and for update. It does not identify the login backend, barrier backend or race account. A scheduled dispatcher can wait while acquiring a relation lock before its SKIP LOCKED row selection; unrelated sessions can also match. The script can then check attempts NOWAIT before login has reached its intended stage. Capture the barrier backend PID, identify the actual login query/backend, and require a pg_blocking_pids relationship to that barrier. Avoid printing SQL bind values/secrets while diagnosing this.

**P2 — Medium evidence gap: reset overlap is not established.** After submitting confirm, the script immediately commits the barrier. It never observes confirm blocked on the account. A sequential login-then-reset can satisfy both statuses and the later 401. Wait for the specifically identified confirm backend to reach the barrier/expected lock chain before release, with a deadline. Distinguish a real overlapping login/reset test from forced late JDBC session-save coverage: this probe does not pause session persistence after reset and should not claim that interleaving.

**P3 — Medium bounds/cleanup limitation.** Python SQL subprocesses have 12-second timeouts, HTTP uses 20-second socket timeouts, polling loops have deadlines, futures/process waits are bounded, and the barrier has a 20-second idle-in-transaction timeout. These are useful local bounds. However idle-in-transaction timeout does not bound an active lock-waiting SELECT; a killed docker exec client does not independently prove its server transaction ended. Add PostgreSQL statement_timeout/lock_timeout to the barrier and assertion connections, and explicitly rollback/close the interactive connection in failure cleanup before terminating it as fallback. ThreadPoolExecutor context exit waits for running HTTP tasks; a socket timeout is not an absolute whole-response deadline. Give this probe step its own CI timeout as a final bound rather than relying solely on the 30-minute job timeout. Do not call it a strict wall-clock-bounded proof until those limits are established.

**Useful coverage and isolation:** the script reads a real Mailpit message for recovery-race@example.test, uses independent login/reset cookie jars with actual CSRF, hashes the fixed email for the attempt key, tests attempt-row NOWAIT while login should wait account-first, expects actual login/reset statuses and rejects the old session afterward. Tokens/passwords stay in memory; SQL failures use a generic message and captured stderr is not printed. The workflow provisions a fourth disposable race account alongside the three viewport accounts, preserving qc@example.test for the parent restart probe. The script itself prints only a fixed result line on success. It must not be counted as executed merely because that print exists.

Its repeated short SQL/mail checks are bounded test synchronization, not repeated external CI-status polling. For deterministic reruns, select mail produced by this request rather than any older same-recipient reset message; fresh disposable CI currently reduces that risk.

**Independent check performed:** parsed scripts/recovery-race-probe.py with Python ast.parse successfully, without executing it or creating bytecode. Syntax success only. No Docker/PG/SMTP/browser execution performed by QC in this turn.

### Visual/font and evidence status

Styles now import self-hosted Inter 400/600/700 assets from @fontsource/inter and choose Inter in the family stack. Source supports the intended family; loading and final visual acceptance require rendered evidence. Prior main-supplied nine visual checks (forgot/reset/expired at three widths, empty/no overflow/no URL secret) remain attributed to main, not independently certified. No other UI feature changes were requested or accepted.

Historical CI context error: prior independent logs showed mailHealthContributor initialization failing with Beans must not be empty; main reported 20 passes/seven recovery context errors out of 27. The test-scoped fix needs a successful rerun; these errors were not recovery functional assertions. Maven/JDK21 exist under the parent .tools directory; the earlier PATH-only missing-runtime statement is superseded and is not a blocker. Avoid repeating long dependency resolution; evaluate exact-final-revision CI later.

**Disposition:** R1/R4/R5 source fixes are reviewable, with runtime proof outstanding. Main should address P1/P2 and tighten P3 before using the probe to close PostgreSQL concurrency QC. R3, timing privacy, stale queued jobs and actual SMTP failures remain open. No new CI result checked or inferred on 2026-10-19. Keep full application incomplete, PR #3 pending acceptance and parent PR #2 unmerged.

## Revised probe static review — 2026-10-19

Reviewed current scripts/recovery-race-probe.py and the two-file CI diff only; no runtime execution or CI result claimed.

- **P1 source-addressed for the isolated CI scenario:** interactive psql emits its actual backend PID before the account-lock marker. Login detection now requires that PID in pg_blocking_pids, rather than counting arbitrary global Lock waiters. The subsequent independent attempt-row FOR UPDATE NOWAIT assertion remains before confirm is queued. This ties the account-first assertion to the barrier held for the disposable race account. Detection still selects the first matching SQL shape rather than an HTTP request identity; keep the race account exclusive to this probe. Matching login's password_hash/credential_version query and confirm's email/enabled query separately would further improve diagnostics if other same-account traffic is introduced.
- **P2 source-addressed for overlap:** after submitting confirm, a separate loop requires another human_accounts FOR UPDATE waiter, excluding login_pid, blocked by the barrier or the identified login backend, before COMMIT releases the barrier. This is materially stronger than task submission alone and checks the PostgreSQL wait chain in the isolated stack. It still does not force late JDBC session persistence after reset; accept only its stated account-first/overlapping-login-reset/old-session-denial scope.
- **P3 bounds materially addressed:** barrier sets 15-second statement_timeout, 10-second lock_timeout and 20-second idle-in-transaction timeout. CI applies statement_timeout=15s and lock_timeout=10s with ALTER SYSTEM plus pg_reload_conf in the disposable PostgreSQL stack, covering application SQL as well as fresh assertion sessions. A dedicated two-minute step timeout now bounds the executor/HTTP/process cleanup tail; the existing always-run Compose teardown is the final cleanup. These CI-only server settings are not production configuration and remain effective for the subsequent disposable restart probe. No secrets are added to output by these changes.

Bounds caveat for CI interpretation: login's possible 8-second discovery plus confirm's possible 6-second discovery exceeds the 10-second lock wait limit. Slow runners may intentionally fail closed via timeout rather than complete the full polling budgets. If this occurs, inspect lock/barrier timing; do not remove bounds or misclassify it as a proven business-code deadlock. psql assertions have a 12-second subprocess limit; terminated clients have the additional server timeouts as fallback. Explicit ROLLBACK/connection close on exceptional cleanup remains a possible refinement, with two-minute step and disposable teardown providing the outer bound.

Independent syntax check: ast.parse succeeded for the revised script, without executing it or writing bytecode. Static review found no new business-source change in the two files reviewed. No additional blocker from P1/P2/P3 for publishing these CI changes within the isolated-stack assumptions above; require exact-revision CI output before marking any concurrency check passed.

R1 queued-job invariants and R3 SMTP failure/crash behavior remain separate evidence requirements. Backend R4/R5 fixes still need successful final-revision tests. Full application remains incomplete, PR #3 pending acceptance and parent PR #2 unmerged.

## Independent exact-source CI disposition — 2026-10-19

### Verified evidence

Read GitHub job summaries and downloaded quality job logs directly. Run [39183256521](https://github.com/giangnguyen3260/agent_os/actions/runs/39183256521), job `117840318943`, completed successfully, including import rules, Maven formatting/tests/coverage and frontend lint/format/tooling/build checks. Checkout log states `HEAD is now at 7f4a55c Merge 26cdaf3d42b7f544eb9694443226ae4f4f97d607 into a36b22cd4efb4ae3b41707f11c5e6b96dbf69dc3`. Thus this is PR merge validation of that source against the stated parent, not a claim that raw head was the checkout.

Independent suite summaries: BootstrapAdminTest 4, AuthenticationIntegrationTest 8, RecoveryIntegrationTest 9, AgentServiceTest 5, OrganizationServiceTest 3. Total **29 tests, zero failures, zero errors, zero skipped** at `01:00:37Z`; Maven BUILD SUCCESS at `2026-10-19T01:00:40Z`. This supersedes the earlier seven recovery context errors for this tested source.

GitHub comparison from full source `26cdaf3d42b7f544eb9694443226ae4f4f97d607` to `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` shows exactly one commit and only `.github/workflows/docker.yml` and `scripts/recovery-race-probe.py` changed. Application and recovery-test code are unchanged. Direct latest-head reads confirm:

- HumanAuthenticationProvider blob `7c74b4b9cc4876c6c27502978747e66dbab7c7da`: account locked before attempt/audit work.
- RecoveryService blob `c31c9d2baa55dbed53b88ab85a878a0d422f4228`: previous token deletion after successful send, transactional PASSWORD_RESET audit, account-first confirm/dispatch and queued-job cancellation.
- RecoveryIntegrationTest blob `0aa8cf1dda16d4d52a99b3c72c10ff8559d6fe38`: management.health.mail.enabled=false scoped to the test annotation; mocked JavaMailSender remains in use. All nine test methods belong to the independently passing suite. Test-only health disablement does not prove production mail health or real SMTP.

### Findings and limits

| Item | Current bounded disposition | Remaining condition |
|---|---|---|
| R1 stale queued jobs | Source-addressed, not runtime-closed | PostgreSQL queued/retry/confirm/multiple-dispatcher evidence; login barrier alone does not cover this |
| R2 timing privacy | OPEN | Measured known/unknown/disabled/throttled timing with an explicit criterion; generic response or test count is insufficient |
| R3 SMTP/transaction behavior | OPEN | Real transport outcomes and bounded failure/retry/crash evidence; network I/O still occurs under account/job locks |
| R4 login/reset lock inversion | Source-fixed; nine-test recovery suite including loginAndResetCompleteWithoutAccountAttemptLockCycle passed on H2 | Hardened real PostgreSQL barrier must pass for the final revision; do not claim exhaustive deadlock/load safety |
| R5 previous-link preservation | Fixed and regression-verified for mocked MailException; failedReplacementPreservesDeliveredToken and onlyLatestIssuedTokenWorks are in the passing suite | Real SMTP delivery/replacement failure and ambiguous acknowledgement remain part of R3; no full transport acceptance |
| Context/mail-health failure | Resolved in the tested recovery context | Latest-head rerun result still required; production SMTP is separate |
| Session version/replay/password behavior | Covered by passing integration suite within its H2/mock scope | Real browser/PG evidence and bounded concurrency scope; injected stale principal is not a forced late JDBC save |
| Visual/font | Main-attributed nine checks plus source-reviewed Inter imports | Final rendered/font-loading evidence independently reviewed before visual acceptance |

### Latest-head CI snapshot — no repeated polling

PR #3 independently reports draft/open/unmerged, base `feature/auth-login` at `a36b22cd4efb4ae3b41707f11c5e6b96dbf69dc3`, head `9a4159c5682b0c990471360c0b2d5c1a3b050bc4`.

| Run/job | Observed state |
|---|---|
| [Quality 39183379255](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379255), job 117840666063 | In progress: imports succeeded, backend checks running, frontend checks pending |
| [Docker 39183379306](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379306), job 117840666525 | In progress: Compose validation succeeded; build/start running; migrations, browser, PG barrier and restart probe pending |

Do not transfer prior quality success into a claimed completed latest-head workflow. No real SMTP pass, 24-browser pass, PostgreSQL barrier pass or parent restart pass was observed in this snapshot. Conditions before acceptance: inspect final-head completed logs for each required stage and actual counts; retain R1/R2/R3 and late-save/visual limitations where those stages do not cover them. Sonar remains paused, not passed. No full timing/privacy, production-readiness or full-app completion claim is authorized. PR #3 remains draft/pending QC; parent PR #2 remains unmerged.

## Acceptance scope agreed with user — bounded development gate

This section supersedes broader historical acceptance wording above. Evaluate **functional development readiness** separately from **production security acceptance**. Do not extend the development gate with new production-hardening requirements.

### Functional development readiness

Mark READY FOR DEVELOPMENT USE when independent exact-revision evidence establishes all of the following:

1. **29 backend tests pass**, including the nine recovery tests and the R4/R5 regressions. Already independently verified for source `26cdaf3d42b7f544eb9694443226ae4f4f97d607`; application/test equivalence to `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` independently verified. Record the latest-head result when available.
2. **24 browser instances pass**: 12 login plus 12 recovery across the three viewports, with actual Mailpit SMTP receipt, successful reset, replay rejection, prior-session denial, old-password rejection and new-password login in the recovery test scope. This establishes local development SMTP integration, not external inbox delivery.
3. **Real PostgreSQL barrier passes** on the final hardened probe: account-first waiter/attempt NOWAIT assertion, confirm overlap before release, successful concurrent commands and subsequent old-session rejection. Do not describe this as exhaustive multi-replica or forced late-save testing.
4. **Parent restart probe passes** after the recovery tests, demonstrating the unchanged core login fixture/session survives backend restart. The four recovery accounts remain disposable and isolated from that fixture.

Retain the already specified quality checks and source-reviewed fixes; failures in those checks require resolution. Once the four runtime evidence conditions pass, issue the bounded functional disposition without waiting for unrelated production-hardening work. No runtime pass is inferred in this scope update.

### Source dispositions accepted for this gate

- **R1:** account-lock acquisition is the accepted linearization point. Enqueue/dispatch/confirm share the account lock; pending old jobs and tokens are deleted on confirm; notification jobs cannot mint reset tokens. This source disposition is accepted for the functional gate. Broader retry/multiple-dispatcher/multi-replica stress evidence is follow-up, not an additional form/function blocker.
- **R4:** account -> attempt -> audit order is fixed in the reviewed source. Passing H2 regression plus the bounded actual PostgreSQL barrier closes the identified inversion for this gate; no universal lock-safety claim follows.
- **R5:** source fix plus passing mocked-failure regression closes the identified previous-link deletion defect within the tested scope. Keep real transport failure/ambiguous-ack limits explicit without expanding the development gate.
- **R2:** timing privacy remains explicitly UNVERIFIED. No constant-time or indistinguishable-timing claim. Production-hardening follow-up, not a development gate blocker.
- **R3:** real happy-path Mailpit receipt is required through E2E. Broader outages, ambiguous SMTP acknowledgement, crash atomicity, sustained contention and transaction-duration guarantees remain production/operational follow-up; do not mislabel their absence as a failed functional flow.

### Final report wording after runtime evidence arrives

If the bounded gate passes: **“Recovery is ready for development use at [exact source SHA], validated by 29 backend tests, 24 browser instances with local SMTP, the bounded PostgreSQL race probe and the parent restart probe. Production security acceptance is not claimed.”** Report the actual merge checkout/source pairing and evidence links, and disclose timing privacy, multi-replica stress, SMTP crash/failure guarantees, forced late-save coverage and independent final visual review limits at their actual evidence level.

This disposition neither completes the full application nor authorizes merging parent PR #2. PR #3 draft/merge decisions remain with main/user after the concrete evidence is reviewed. QC continues to own this report only. No new CI polling or runtime execution occurred in this scope-only update.

## Independent completed first Docker run — 2026-10-19

Fetched job summary and logs directly, once in this review. [Docker run 39183256484](https://github.com/giangnguyen3260/agent_os/actions/runs/39183256484), job `117840319001`, is **completed/success**, including cleanup. Checkout identifies merge `7f4a55c` of source `26cdaf3d42b7f544eb9694443226ae4f4f97d607` into parent `a36b22cd4efb4ae3b41707f11c5e6b96dbf69dc3`.

| Evidence | Independent log result |
|---|---|
| Backend in Docker build | 29 tests, zero failures/errors/skips |
| Actual browser suite | **24 passed (21.4s)** at 01:03:05Z: 12 login + 12 recovery |
| Real local SMTP receipt/reset | SMTP-link reset test passed at desktop 01:02:50Z, tablet 01:02:57Z, mobile 01:03:04Z, using actual Mailpit messages |
| Original PG race probe | PASS at 01:03:06Z: account-first barrier, concurrent login/reset and stale-session denial |
| Parent restart probe | Session issued at 01:03:07Z; **exact session survives backend restart; logout rejects replay** at 01:03:42Z |

The original probe result is recorded at its actual pre-hardening evidence strength. It is not substituted for the latest hardened waiter-identification/overlap/timeout probe. A post-job cache reservation warning did not fail the completed job or its functional steps.

**SMTP distinction:** real Mailpit SMTP delivery is now independently verified for the successful development recovery flow at source 26cdaf3. SMTP failure/retry and prior-link preservation assertions in the H2 recovery suite use mocked JavaMailSender/MailException; they do not establish real server outage, rejection or ambiguous-ack behavior. No external inbox-delivery claim.

Latest-head [quality run 39183379255](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379255), job `117840666063`, independently reports completed/success for every step. Latest-head [Docker run 39183379306](https://github.com/giangnguyen3260/agent_os/actions/runs/39183379306), job `117840666525`, remains in progress in this snapshot: stack, API checks, schema and isolated accounts succeeded; browser dependencies installing, browser/hardened barrier/restart pending.

**Bounded disposition:** application code has independently successful first-run backend/browser/local-SMTP/restart evidence. Final development-readiness signoff at `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` remains conditional only on the agreed latest-head Docker stages completing successfully, particularly the hardened PG probe. Do not reopen the functional gate for production timing or multi-replica scope. R4's identified source inversion is fixed with passing H2 and original PG regression evidence; final hardened proof pending. R5 is fixed with passing mocked-failure regression; real happy-path SMTP now passes separately. Preserve all previously declared production limitations. Full application remains incomplete; parent PR #2 remains unmerged.

Latest quality log detail independently verified: checkout `f4bb0fe` merges full head `9a4159c5682b0c990471360c0b2d5c1a3b050bc4` into the stated parent. RecoveryIntegrationTest: nine passes; total 29 tests with zero failures/errors/skips; Maven BUILD SUCCESS finished `2026-10-19T01:01:58Z`. Therefore the backend-test condition is now verified on the latest PR merge as well, not only transferred by application-code equivalence.

## Final development disposition — 2026-10-19

The completed QC review and coordinator log retrieval supersede the preceding in-progress snapshot. **Recovery is ready for development use at source `9a4159c5682b0c990471360c0b2d5c1a3b050bc4`.**

- Quality run `39183379255`, job `117840666063`: completed/success;29 tests, zero failures/errors/skips, Maven BUILD SUCCESS at01:01:58Z.
- Docker run `39183379306`, job `117840666525`: completed/success;24 browser instances passed at01:04:35Z, including real Mailpit delivery and reset behavior across1440/768/390.
- Hardened PostgreSQL probe: PASS at01:04:38Z, identifying the barrier/wait chain, attempt-row NOWAIT assertion, overlapping login/reset completion and old-session denial.
- Parent restart probe: PASS at01:05:07Z; the exact pre-restart session survives backend restart and logout rejects replay.

QC independently retrieved the completed results and returned the development-ready verdict; the coordinator also fetched the final logs. R4/R5 are closed within this bounded gate. Final evidence/documentation commits do not change application or probe source. This is not production-security acceptance: timing privacy, multi-replica stress/failover, complete visual/accessibility review and broader real SMTP failure/crash/ambiguous-ack guarantees remain follow-up. Full application remains incomplete; PR2/PR3 remain unmerged. Sonar remains paused.
