# Authentication / login independent QC

Status: Implementing — NOT ACCEPTED. Review date: 2026-10-18 UTC.
QC owns this report only. Backend: BEagentSinger. Frontend source: FEagentPauli.
Baseline: `docs/planning/AUTH_LOGIN_DELIVERY_CONTRACT.md` and `DELIVERY_COORDINATION.md`.
Observed HEAD: `207fced92adb328bf1039970bae6b216f8458fbd`; implementation is an uncommitted, changing working tree. Findings below describe the observed snapshot, not a completed delivery or immutable revision.

**Recovery dependency: UNAVAILABLE / acceptance blocked. Workspace handoff: NOT FULL-SCREEN ACCEPTANCE. All screens: NOT COMPLETE.** No auth flow, workspace screen, or wider product screen is accepted by this review. Recovery requires its adjacent ticket, real email transport and token lifecycle evidence. Membership-backed workspace navigation alone cannot establish workspace-screen acceptance.

## Initial snapshot findings (superseded by review 2 below)

| ID | Priority / owner | Evidence and consequence | Required correction / retest |
|---|---|---|---|
| QC-A01 | High / BEagentSinger | `HumanAuthenticationProvider.verify` checks `password.length() <= 72`; `BootstrapAdmin` uses UTF-8 byte length. Multibyte login input can exceed BCrypt's 72-byte boundary despite passing the character check. Exact encoder behavior is not yet wired/verified; truncation or exceptions must not become accepted credential behavior or a 500 response. | Apply the same byte policy at login and bootstrap. Test 72-byte boundary, multibyte overflow, and same-prefix/different-suffix credentials with the actual encoder; return generic failure without exceptions. Static finding; runtime reproduction pending. |
| QC-A02 | Blocking integration gap / BEagentSinger | Observed `SecurityConfig` is an empty configuration; password encoder, login/logout handlers, session fixation defense, CSRF policy and membership enforcement are not yet shown. Existing organization/agent controllers delegate using request organization IDs. No authorization conclusion can be made from membership tables alone. | Inspect final security chain and scope enforcement, then exercise anonymous access and two-account/two-organization API isolation. Do not interpret this partial snapshot as a reproduced exploit. |
| QC-A03 | Blocking evidence gap / BEagentSinger + FEagentPauli | Initial inventory contains only organization/agent service backend tests; no auth-specific tests or frontend auth files. Existing routes have no auth guard and shell identifies sample workspace/data. Implementation is in progress. | Deliver meaningful security integration and browser tests with actual session-backed login and real memberships. Review new files on owner/main handoff. |

These findings are reported in this shared report and the QC handoff. No source modifications or messages through external services have been made.

## Exact execution checklist

Every row is PENDING unless an evidence entry explicitly replaces it. Static inspection does not satisfy runtime acceptance. Each execution entry must record exact commit plus dirty-tree state (or artifact digest), command/scenario, environment, observed result, expected result, and sanitized evidence. Retests must reference defect IDs.

| ID | Execute / expected acceptance evidence |
|---|---|
| Q01 | Review written API contract before acceptance: CSRF bootstrap, sign-in fields/content type, session, memberships, sign-out, stable validation/401/403 envelopes, generic credential error. Trace final implementation against it. |
| Q02 | Start disposable empty PostgreSQL; run Flyway V001/V002 and inspect constraints/session tables. Bootstrap explicitly with operator-provided secret; restart app and DB and authenticate persisted account. No public signup/default credential; bootstrap disabled is inert, repeated/concurrent bootstrap creates no duplicate. Capture sanitized bootstrap audit. |
| Q03 | Verify adaptive password hashes at rest; no raw password/token in DB, logs, responses, fixtures or normal runtime. Test changed hash rejects old password; document treatment of already authenticated sessions. Include UTF-8 byte limits and malformed/oversized inputs. |
| Q04 | Real correct/incorrect/unknown/disabled login against DB: correct session contains trusted human identity; failures are indistinguishable in public status/body, with reasonable timing mitigation. Disable account after login and verify documented enforcement on existing sessions. No model-agent identity substitution or fake frontend success. |
| Q05 | Exercise persisted throttling: five failures/window policy, unknown accounts, normalized email, concurrent requests, reset after window, success reset and restart behavior. Check unbounded unknown-email attempt-row growth and document perimeter/rate controls; audit contains no credentials. |
| Q06 | Pre-auth session ID versus authenticated ID must rotate. Replay old pre-auth ID fails to inherit authentication. Login success rotates CSRF token; old CSRF token is rejected and fresh token works. Verify actual framework behavior, not defaults inferred from dependencies. |
| Q07 | Inspect Set-Cookie in localhost HTTP and HTTPS deployment: HttpOnly session, explicit SameSite, Secure in HTTPS, intentional localhost exception, appropriate path/domain, lifetime. Verify reverse-proxy forwarded-header trust and no credential/token localStorage persistence. |
| Q08 | Reject missing/invalid CSRF on login and all state-changing protected endpoints including logout. Valid token/header works. Cross-origin requests cannot bypass policy; GET cannot mutate or log out. Check CSRF refresh after authentication, logout and expiry. |
| Q09 | Logout invalidates server-side session; replay captured cookie and reload/back navigation cannot access protected API. Expired session rejects API and FE returns safely to login. Document timeout, concurrent sessions and password-change/revocation policy. |
| Q10 | With accounts A/B and organizations A/B, exercise every organization, team, agent, mission and observability read/write route: anonymous denied, nonmember denied, authorized member allowed only within documented role. Include collection listing, nested mismatched IDs, supplied body/query IDs, organization creation and membership ownership; no arbitrary UUID authorization. |
| Q11 | Restart app with same DB and verify session policy; use two instances against shared persistence to test session creation/read/logout propagation if multi-instance support is claimed. Otherwise explicitly leave scale unverified. |
| Q12 | Browser: empty required fields, accessible labels/autocomplete, email normalization, password handling, Enter submit, pending state, repeated click/Enter makes one request, generic wrong-password error, offline/network failure, recovery from pending/error. No default input credentials or mock success wording. |
| Q13 | Browser: successful login refreshes actual `/session`; direct protected URL, refresh, expired session and back navigation enforce actual session. Reject absolute/protocol-relative/encoded unsafe return URLs; allow safe internal target. No redirect loop. |
| Q14 | Browser workspace handoff uses real membership endpoint; cover zero/one/multiple memberships and revoked membership. No fixed UUID or sample business data presented as runtime state. This verifies handoff only, not the complete workspace screen. |
| Q15 | Recovery dependency UNAVAILABLE until actual flow, email transport, random digest-stored token, expiry, one-time use/replay prevention, generic enumeration-safe response and tests are delivered/accepted. An inert CTA or fake sent confirmation blocks login acceptance. |
| Q16 | Compare actual screenshots with desktop 70:6350/6387/6421, tablet 71:7109/7142/7176, mobile 71:7913/7946/7980 at 1440/768/390 and narrow viewport. Tablet context still needs inspection. Check keyboard/focus order, visible focus, no clipping, contrast and 44px targets; obtain designer signoff. |
| Q17 | Coordinate main before running shared runtime/build checks. Run backend Google-style + meaningful tests, frontend lint/format/meaningful tests/build and Docker integration on exact final revision. Record command output and unavailable tools honestly. Tooling smoke tests alone do not establish auth coverage. Sonar PAUSED by product-owner instruction, never PASSED. |
| Q18 | Coordinator obtains independent BA requirement, designer visual and QC functional/security signoffs. Review missing evidence and defects before acceptance; no automatic Done from build or file count. All other screens remain incomplete absent their own accepted evidence. |

## Execution ledger

- Initial read-only inventory and contract/source inspection completed; no functional, build, security, browser or migration test executed. No test pass claimed.
- Present at inspection: JDBC account verification, bootstrap and auth controller, human identity, V002 account/membership/attempt/audit/session migration; these show intended real persistence but do not prove executable integration.
- Awaiting owner/main handoff for stable security/frontend implementation and coordinated test environment. No repeated polling or background monitoring process started. Next review is event-driven on supplied implementation/test handoff.
- Source ownership respected: only this report is authored by QC. Existing in-progress changes belong to their owners.


## Review 2 — implemented source handoff, 2026-10-18

Disposition: NOT ACCEPTED; no P0 identified by this source review. One P1 integration defect identified below. HEAD remains `207fced92adb328bf1039970bae6b216f8458fbd` with uncommitted implementation; findings are against the working tree, not a completed commit. Only this report modified by QC.

### Immediate P1 to main / BEagentSinger

**QC-A04 — principal name can exceed JDBC session column, breaking valid login persistence.**

Evidence: `HumanIdentity` is a serializable record with no `Principal`/`UserDetails` name implementation. `HumanAuthenticationProvider.authenticate` places that object directly in `UsernamePasswordAuthenticationToken`. Under the framework's ordinary authentication-name fallback and principal index extraction, its name is the record's complete `toString()`. V002 defines `spring_session.principal_name varchar(100)`. The Docker CI bootstrap identity `qc@example.test` / `QC Operator` has a 102-character record representation for any UUID. The existing integration fixture `human@example.test` / `Human` produces 99 characters and therefore misses this boundary. Valid longer email/display-name inputs amplify the overflow.

Expected consequence: saving/indexing the authenticated JDBC session can fail on the database length constraint, preventing reliable login even when password verification succeeds. This is a static defect with a calculated boundary, NOT a runtime-reproduced failure. Framework-source verification through the public raw source endpoint was blocked (`DisabledError`), and no local framework JAR was available to inspect in this QC runtime; BE must confirm the actual resolved framework path in its Java21 environment.

Required action: provide a stable, bounded identity name (prefer account UUID through `java.security.Principal.getName()` or an explicit supported index resolver), rather than relying on a record representation containing mutable profile data. Add real JDBC session login/reload/logout tests for the exact CI identity and maximum permitted email/display name; assert principal index is the intended stable identifier. Widening the column alone leaves unstable profile-derived identity semantics. BE should prioritize this alongside its pending integration run.

### Source review findings and closure state

| Area | Independent source finding | State / remaining evidence |
|---|---|---|
| QC-A01 byte boundary | Provider now rejects UTF-8 passwords over 72 bytes, aligned with bootstrap and FE validation. BCrypt strength 12 is explicitly configured. | Original source defect corrected. Runtime regression pending. Existing multibyte test runs after disabling the account, so it cannot distinguish byte-limit rejection from disabled-user rejection; add an enabled-account boundary test. |
| QC-A02 security wiring | Explicit form-login/session chain, default deny, CSRF handler, session fixation `changeSessionId`, session/logout JSON handlers, session JDBC dependency/config and migration now exist. | Original empty-config gap superseded. Actual cookie/session/CSRF behavior requires tests. |
| Organization scope | Security matcher queries current enabled account membership for path organization; non-read methods require ADMIN. Root organization listing/creation denied. Agent team references and mission team/manager references are checked within the organization. Mission trace checks owning mission and filters event organization. | No cross-organization bypass identified in inspected routes. Runtime matrix still missing second account/actual second-org resources, nested foreign IDs, membership revocation, and all read/write endpoints. Denied organization creation leaves provisioning a later dependency, not a working feature. |
| Disabled account | Filter rechecks persisted enabled status on authenticated requests and invalidates session/clears context when disabled/deleted. | Positive source evidence; disable-after-login/replay regression absent. Existing tests cover disabled sign-in only. |
| CSRF / replay | FE fetches `/csrf` before login and again before logout; no persisted bearer credentials. BE leaves CSRF enabled and explicitly rotates session ID on login. | Tests assert missing/invalid login CSRF, missing logout CSRF and pre-auth session-cookie replay. No assertion that old CSRF token fails after successful login and new token succeeds. Do not infer token-rotation acceptance from session-ID rotation. |
| Cookies / persistence | Application defaults HttpOnly, SameSite=Lax, Secure=true; Compose explicitly defaults Secure=false and binds frontend to loopback for local HTTP. JDBC persistence schema exists. | Local cookie assertions are written, not independently run here. HTTPS Secure behavior, proxy deployment, logout propagation, expiry, process/DB restart and concurrent sessions remain unverified. |
| Credential failures / throttling | Generic login failure response; dummy BCrypt verification for unknown accounts; persisted per-normalized-email attempts with row locking, five failures/15 minutes. | Cross-account/IP spray remains unbounded by this implementation: fresh email keys trigger costly hashes and persistent attempt/audit rows, with no shown cleanup or aggregate limit. Record release/perimeter rate-control and retention decisions; test concurrency/window behavior. This is a capacity/security hardening concern, not a reproduced P0/P1 exploit. |
| Session policy | Configurable 30-minute default idle timeout is present. Enabled state is rechecked; password hash is not rechecked on existing sessions. | Explicit concurrent-session and password-change revocation policies still needed. No claim that changing a hash revokes existing sessions. |
| FE auth | Actual same-origin HTTP form login; required/validated empty inputs, pending guard, generic error, password clearing, network errors, guarded `/workspace`, session read by guard, membership-backed list and logout. Fixed internal `/workspace` redirect ignores return URL input, avoiding an external redirect target. | No fake auth identified. Browser behavior and responsive/accessibility acceptance pending. Guard reads session before workspace navigation; no arbitrary return URL support is implemented. |
| Recovery / workspace | Recovery dialog explicitly says unavailable; workspace lists actual memberships and explicitly says opening workspace unavailable. Legacy sample routes are diverted to workspace by preview guard. | Recovery dependency UNAVAILABLE. Workspace handoff NOT FULL-SCREEN ACCEPTANCE. All screens NOT COMPLETE. These honest unavailable states do not satisfy full delivery requirements. |

### Executed checks and exact limitations

- **PASS (QC executed):** `npm run lint` in `frontend`, Node `v24.19.0`, exit 0 (`eslint src --max-warnings 0`). npm emitted an environment `http-proxy` configuration warning; no lint error.
- **Build NOT RERUN:** existing `dist/agentos-frontend/browser/index.html` is newer than all files under actual `frontend/src`. Avoided duplicating prior build per main's instruction. Timestamps do not prove build success; QC does not independently claim a build pass.
- A timestamp helper initially used a duplicated `frontend/` prefix and failed with FileNotFoundError; rerun with paths relative to frontend succeeded. This helper failure was not a build/test failure.
- **BE tests NOT RUN by QC:** main reports BE owner preparing Java21 test execution. Existing `AuthenticationIntegrationTest` source was read; written assertions are not passing results. No shared runtime or backend test process started by QC.
- **Browser BLOCKED (main-reported):** browser binary download returned SiteUnavailable HTML and install failed. QC did not retry installation or report a browser pass.
- Docker workflow now installs Chromium and runs real backend Playwright at 1440/768/390. Four scenarios per viewport are written: anonymous/CSRF denial, empty form/geometry/keyboard, wrong credentials, real login/reload/logout/cookie replay. **CI added, execution unverified.** No CI result observed. It does not yet cover network failure, repeated-submit requests, expired sessions, CSRF rotation, membership variants, recovery lifecycle, full visual comparison or full keyboard/contrast checks. Three viewports alone do not prove design acceptance.
- Sonar remains PAUSED; no Sonar pass claimed.

Reviewed FE auth file SHA-256 values at lint review:

| Actual source | SHA-256 |
|---|---|
| `frontend/src/app/core/auth/auth.guard.ts` | `13e5fab13d6f009102afc6f883cb683a6d8c226a7feda93c4ac512a0f9fc80f6` |
| `frontend/src/app/core/auth/auth.service.ts` | `fda2f4dcfd16e4adce973d514075d89a468a9447be5ee8ea4343627e8969aaf8` |
| `frontend/src/app/features/auth/login-page.component.ts` | `1580d796f01f7bc7881fa7c8940e5c9f82301f6bbb42311c4f6e13ae46680a76` |
| `frontend/src/app/features/workspace/workspace-page.component.ts` | `598253c071ab56a949e0dfd6b4727f49d903967bc6cc7400c00abd7e0aa198f6` |

A duplicate `frontend/frontend/src` tree exists but is not the Angular configured source root or the `eslint src` target. Main/FE should remove or reconcile it within their ownership to avoid mistaken reviews; QC did not modify it.

Next event-driven handoff: BE resolves QC-A04 and supplies actual test output; main supplies immutable revision, Docker/Playwright CI result and browser/design evidence. No repeated polling started.

## Review 3 — final source findings for draft PR handoff, 2026-10-18

**Current disposition: no outstanding P0/P1 source defect identified in the reviewed auth implementation; NOT ACCEPTED because required execution and adjacent-flow evidence are outstanding.** This section supersedes review 2's open QC-A04 and coverage observations where specified. HEAD observed remains `207fced92adb328bf1039970bae6b216f8458fbd` with uncommitted changes. QC modified this report only; no tests/builds rerun in this review.

- **QC-A04 corrected in source; runtime retest pending:** `HumanIdentity` now implements `java.security.Principal`, returning `id.toString()` from `getName()`, with `@JsonIgnore`. The principal name is therefore bounded to a UUID and excludes profile text. The prior 102-character CI-identity overflow path is addressed. Confirm actual JDBC index value and successful CI bootstrap identity login in execution; source correction is not a passing test.
- **Disabled-after-login correction confirmed in source:** security request filter checks the persisted enabled account, invalidates its session and clears authentication when disabled/deleted. New `disablingAccountRevokesExistingSession` asserts 401 for session, workspaces and organization agents after disabling a signed-in account. Test is PRESENT, execution PENDING.
- **QC-A01 password-byte correction remains confirmed:** provider checks UTF-8 byte length before BCrypt. Bootstrap and FE use the same byte ceiling. Enabled-account boundary/same-prefix regression remains useful; no boundary-test pass claimed.
- **CSRF replay coverage improved:** login helper now posts logout with authenticated cookie plus pre-login CSRF token and expects 403, then asserts a freshly fetched token differs. Existing logout flow exercises the fresh token. These are written assertions, not verified results.
- **Organization reference coverage improved:** test creates real foreign organization/team/manager/mission fixtures and asserts cross-organization trace is not found, foreign mission references are rejected, and no mission is inserted into the current organization. Full two-account/read-write/revocation matrix remains outstanding.
- `backend/AUTH_SECURITY.md` and `docs/engineering/AUTH_RUNTIME.md` now document the API, default timeout, concurrent sessions, password-change session policy and abuse-control limits. Policy defaults explicitly await business approval; documentation presence does not constitute approved policy or runtime evidence.

### Final actionable handoff

1. **Main / BE: obtain Java21 backend verification results on the exact draft PR commit.** Backend execution is PENDING; QC has no observed passing output. Preserve style and test checks; H2 results alone do not verify PostgreSQL bootstrap advisory locking, migration compatibility, restart durability or multi-instance behavior.
2. **Main: run Docker real PostgreSQL + Playwright CI and record results.** There are 12 configured browser test instances (four scenarios across three viewports), not 12 independently verified cases. Local browser binary download/install remains blocked as reported; no browser pass, Docker pass or CI pass is claimed. Publishing a draft PR is a review handoff, not acceptance.
3. **BE: confirm the corrected principal index and disable/CSRF replay tests actually pass; add/execute expiry, restart, enabled-account byte boundaries, and broader account/scope tests.** Capture sanitized evidence. Confirm Secure cookies in HTTPS; localhost cookie behavior does not establish deployment acceptance.
4. **Main / security owner: resolve documented policy approvals and deploy-time edge/IP limits, request-size bounds and authentication-row retention before public release.** Current per-email throttling does not bound a many-email spray. No reproduced P0/P1 abuse exploit is asserted by this static review.
5. **BA / designer / main: keep recovery and full workspace acceptance open.** Recovery dependency UNAVAILABLE; workspace handoff NOT FULL-SCREEN ACCEPTANCE; all screens NOT COMPLETE. Full recovery lifecycle, responsive visual comparison/accessibility and missing browser error/expiry/repeated-submit scenarios still need evidence.

Prior independently executed FE lint remains PASS for its recorded snapshot; build was not duplicated. Sonar remains PAUSED. No automatic acceptance follows from a draft PR, configured tests, successful compile, or source fixes.

## Review 4 — fixture fix and local browser evidence handoff, 2026-10-18

**Disposition: NOT ACCEPTED.** The evidence below was reported by main; QC has not independently rerun these checks or fetched CI results. Main will append new CI outcomes when available. This section supplements earlier pending-execution entries without retroactively turning them into passes.

### Historical backend execution failure and current retest

- Main reports actual CI run `117733690786` compiled under Java21 and executed 19 tests: **18 passed, 1 failed**. The failure was attributed to an invalid `DRAFT` fixture value. This is a historical failed run, not a passing backend suite and not merely an unexecuted test plan.
- Fixture correction published at commit `ae48419f8505de317664a0c44dcebf5f1b209010` (main-reported). Corrected source does not establish that the failing test or full suite now passes.
- New quality run `39147482417`: **PENDING RETEST / outcome not observed by QC**.
- New Docker run `39147482389`: **PENDING / outcome not observed by QC**. No real PostgreSQL integration or full authenticated Playwright pass is claimed from the existence of this run.
- Earlier source findings were against the recorded changing working tree. Main must attach final CI evidence to the exact tested revision; this report does not assume the two new runs passed or independently verify their commit association.

### Actual local Chromium rendering (main-reported evidence)

Main obtained local Chromium 153 through an npm-package workaround after the earlier browser download/install blockage. The earlier failed install remains historical; it no longer means every local browser check is blocked. Actual static frontend pages were rendered at all three widths:

| Viewport width | Login card bounds (CSS px) | Reported checks |
|---|---|---|
| 1440 | x=440, y=126, width=560, height=498 | No horizontal overflow; empty inputs |
| 768 | x=104, y=126, width=560, height=498 | No horizontal overflow; empty inputs |
| 390 | x=16, y=110, width=358, height=526 | No horizontal overflow; empty inputs |

Main also reports required-email and required-password validation checks passed, and the recovery dialog closed with Escape. Main visually viewed the mobile screenshot. These are bounded UI observations, not QC's own executed checks or independent designer signoff. Viewport heights and a screenshot artifact/immutable render revision were not supplied in this handoff; no additional geometry or visual-comparison result is inferred.

No API stubbing/interception was needed for these static page checks. **No authentication API was tested by this local render exercise.** It does not verify credential handling, successful/failed server login, session/CSRF rotation, disabled-account revocation, membership scope, logout replay, expiry, PostgreSQL persistence or the 12 configured authenticated/HTTP Playwright test instances. It also does not establish full keyboard navigation, contrast, all touch targets, all designed states, or desktop/tablet/mobile Figma equivalence.

### Remaining acceptance gates

Await actual quality/backend and Docker/PostgreSQL/Playwright results, with exact revision and sanitized failure/retest evidence. Retain the remaining security and coverage checks in review 3. Recovery dependency remains **UNAVAILABLE**; workspace handoff remains **NOT FULL-SCREEN ACCEPTANCE**; **ALL SCREENS NOT COMPLETE**. Sonar remains **PAUSED**. Neither the fixture fix, partial historical test success nor the reported static browser checks constitutes acceptance of the login delivery.

## Review 5 — backend fixture retest result, 2026-10-18

Main/QA reports fetching actual GitHub job `117734172206` logs for commit `ae48419f8505de317664a0c44dcebf5f1b209010`. The logs show **19 tests run, 19 passed**, with **BUILD SUCCESS at 17:00:07Z**: Bootstrap 4, AuthIntegration 7, Agent 5, Organization 3. QC records this as main-fetched CI evidence, not a local QC execution or independently fetched log. No additional local checks performed.

The fixture-related historical 18/19 failure is superseded for this corrected revision by the reported successful retest; retain the failed run as history. Backend test execution is therefore no longer wholly pending for this commit. These integration tests use **H2**, not PostgreSQL: their pass does not verify PostgreSQL migration/locking behavior, restart persistence, bootstrap concurrency or multi-instance session propagation.

Docker job `117734172022` remains **RUNNING / final outcome not observed**, per main. Real PostgreSQL stack and authenticated browser E2E results remain unverified; do not infer success from backend tests or the earlier static Chromium renders. Earlier pending statements describe their historical snapshots and are superseded only to the extent stated here.

**Overall disposition remains NOT ACCEPTED. Recovery dependency UNAVAILABLE; workspace handoff NOT FULL-SCREEN ACCEPTANCE; ALL SCREENS NOT COMPLETE.** Remaining security, browser/design and adjacent-flow evidence gates still apply. Sonar remains PAUSED.
