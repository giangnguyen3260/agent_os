# AUTH workspace independent QC review

Review date: 2026-10-19. Scope: active, uncommitted shared-workspace changes against `docs/engineering/AUTH_WORKSPACE.md`. Agents were still completing changes; this report is a source snapshot, not revision-specific execution approval. QC changed only this report.

## Disposition and validation limits

**Pending CI; no CI pass claimed.** Local backend execution is **BLOCKED by network**, as reported by main; QC did not independently reproduce that environment failure. No backend, Docker or browser suite was executed by this review. QC ran only `node --check frontend/tests/e2e/workspace.spec.mjs` successfully and checked that its Docker command cwd resolves to the repository root. Syntax/cwd checks do not establish test behavior.

No direct cross-tenant authorization bypass was identified in the inspected diff. The workspace detail query requires both authenticated account membership and the URL organization ID. Missing/foreign detail requests share the not-found path. Opening/reloading uses the detail endpoint; context loading clears prior data and rejects obsolete component responses. Existing organization-resource authorization remains separate and must continue to execute on each request.

## Findings and source reinspection

| ID | Finding | Current state and required evidence |
| --- | --- | --- |
| AWC-01 | P2: delayed session success or 401 could overwrite/clear a newer account's global identity despite component destruction/version checks. | Source-addressed, runtime verification pending. `frontend/src/app/core/auth/auth.service.ts` now increments `authEpoch` on login/logout and guards session/login/logout writes and request-level 401 clearing. Deterministic delayed-success and delayed-401 regression cases remain required; component-local guards alone are insufficient. |
| AWC-02 | P2: original reload E2E retained membership, so it did not demonstrate revoked access on reload. | Source-addressed, execution pending. `frontend/tests/e2e/workspace.spec.mjs` now deletes the signed-in account's Alpha membership using real Docker/PostgreSQL SQL, reloads the same URL, requires the lost-access heading, checks Alpha text absent and detail API 404, then restores membership in `finally`. Docker cwd resolves from the test file to the repository root. Current Playwright configuration is serial (`workers: 1`, `fullyParallel: false`); preserve serialization or isolate mutable fixtures if parallelism changes. |
| AWC-03 | P2: foreign UI case previously passed on any screen without workspace names. | Source-addressed, execution pending. Foreign case now requires the exact foreign URL and exact lost-access heading, with Alpha/Beta names absent. Missing-ID case now requires the exact lost-access heading. Missing-ID URL retention and API/body equivalence are useful additional assertions, not claims about current browser coverage. |

Login E2E selectors inspected in `frontend/tests/e2e/login.spec.mjs` use the current exact `Đổi tài khoản` label. No remaining old `Đăng xuất` selector was found in the inspected frontend specs. Organization roles are labelled as organization roles; no sample dashboard statistics or team grants were introduced in the reviewed context screen.

## Test cases and evidence to collect

All execution outcomes below are pending unless explicitly stated. Test presence is not a passing result.

| Case | Procedure and expected result | Coverage / owner |
| --- | --- | --- |
| AWC-T01 Membership isolation | Log in as workspace QC. List contains Alpha only; direct Beta detail request returns 404; foreign URL retains its ID, shows exact lost-access heading and neither workspace name. | Browser assertions present; main/CI execute across configured desktop, tablet and mobile projects. |
| AWC-T02 Valid URL and reload | Open Alpha, verify its name and organization role; reload the same URL and require revalidated context. | Browser open/reload assertions present; explicit role assertion remains to add if needed. Backend integration checks ADMIN/MEMBER detail responses. |
| AWC-T03 Revocation on reload | Open Alpha; delete only that account's membership through PostgreSQL; reload unchanged URL; require lost-access heading, no Alpha context and API 404; always restore fixture. | Real SQL browser case present; main/CI execution pending. |
| AWC-T04 Missing vs foreign | Request foreign and nonexistent UUIDs with the same valid session; require equivalent 404 error semantics and lost-access UI without workspace data. | Backend tests cover revoked/missing error equality and foreign 404/message. Browser has exact headings for foreign/missing; complete three-way body comparison is not established. |
| AWC-T05 Empty state and account change | Account with no memberships can refresh permissions, log out/change account and reach login; session endpoint then returns 401. | Browser case present; execution pending. |
| AWC-T06 Auth response generation | Hold A's session 200, leave context, complete logout/login as B, release A's response; identity remains B. Repeat with stale A 401; B remains signed in. | FE owner: deterministic regression required; auth-epoch source remedy observed. |
| AWC-T07 Workspace response ordering | Hold Alpha detail; navigate to Beta/missing context or leave page; release Alpha success/404/401 after the newer load. Old result must not repopulate workspace or trigger obsolete component navigation. | Version/destruction guards present; deterministic browser/component evidence pending. |
| AWC-T08 Authentication lifecycle | Unauthenticated detail gives 401; malformed UUID gives 400; credential-version change and disabled account invalidate existing access. | Backend integration cases present; local BE blocked by reported network restriction; CI pending. |
| AWC-T09 Failure and retry | Fail current list/detail request with network error or 5xx, verify explicit failure UI and successful retry; no stale context survives. | Source states present; runtime evidence pending. |
| AWC-T10 Regression and viewports | Run existing login/recovery suites plus workspace cases at 1440/768/390; retain actual job results and failing assertion output. | Main/CI. One foreign-screen width assertion alone does not establish full visual/accessibility acceptance. |

## Handoff

Main owns real Docker/PostgreSQL E2E and CI evidence; FE owns auth-generation regression evidence. Record the final tested commit, exact commands/job URLs, case counts and failures before closing findings. Historical login/recovery CI results do not validate this active workspace diff. Sonar remains paused per contract. This review does not claim dashboard/team-role completion or full screen acceptance.

## Auth generation regression evidence

Main ran `cd frontend && node scripts/test-auth-generation.cjs`: both delayed session success and delayed 401 cases passed after logout/login as B. The harness transpiles the actual service and replaces Angular DI/HTTP adapters to control completion order; this verifies service state logic, not browser rendering or Angular transport integration. This command is now part of the quality workflow. CI verification remains pending.
