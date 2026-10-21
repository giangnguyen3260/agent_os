# Mission creation QC report

Decision: **PENDING runtime and CI evidence**. Working branch `feature/mission-create`; independent source review on 21 October 2026. Shared working tree is still changing; no reviewed commit SHA or CI run is claimed. No commits, push, deployment or automations performed by this QC worker.

## Delivered coverage

`frontend/tests/e2e/mission-create.spec.mjs` contains 11 real-backend scenarios for the existing desktop/tablet/mobile projects (33 instances). It covers validation, no premature writes, Back/Cancel, accessible fixed Team, manager paging and DRAFT labelling, actual creation and reload, ambiguous POST replay, GET-only detail retry, canonical/concurrent replay, input bounds, manager/tenant/role/CSRF denial, legacy fail-closed POST, fresh-role revocation, editable setup rejection and locked idempotency conflict. Successful data is never synthesized. See `MISSION_CREATE_TEST_CASES.md` for prerequisites and limitations.

`frontend/tests/e2e/team-workspace.spec.mjs` now checks the scoped Create Mission link for ADMIN and its absence for MEMBER. Existing E2E files contained no old legacy POST expectation or assertion that ADMIN's action must remain disabled.

## Executed evidence

| Check | Evidence and result |
|---|---|
| Contract, fixture, existing E2E and implementation inspection | Performed locally against shared sources |
| Playwright collection | Passed: `npx playwright test mission-create.spec.mjs --list` collected 33 instances; workspace baseline collection found 18 instances |
| JavaScript syntax and formatting | Passed: `node --check tests/e2e/mission-create.spec.mjs`; Prettier check passed for both changed specs; scoped `git diff --check` passed |
| Local stack availability | Port 8080 API preflight returned curl HTTP code 000; Docker executable not found. Real browser/SQL tests cannot execute here against the required stack |
| Backend test artifacts | Inspected completed local Surefire text and XML: MissionCreateIntegrationTest, 7 tests, 0 failures, 0 errors, 0 skipped, 9.303 seconds. Produced by backend worker; not independently rerun by QC and not CI. Earlier Mockito self-attach errors were superseded by this passing result |
| Frontend application checks | FE worker reports final format, lint and build passed; coordinator Google import check and diff check passed; full local backend suite95passed,7new |
| Coordinator rendered mock UI | 26 checks passed across1440/768/390: validation/Back, frozen retry, saved read, error/null/scope clearing, GET-only recovery and compact-menu Escape/focus. Rechecked after final Forest input-style refinement; screenshots inspected at all three widths. Mock API results are not persistence evidence |
| CI/PostgreSQL/browser execution | Pending; no run URL, completed browser result or native PostgreSQL probe output supplied |

## Independent findings

1. **Resolved during review — legacy status mismatch.** `validMission()` initially accepted PLANNED/PAUSED but rejected backend READY/WAITING_APPROVAL/REVIEW, preventing legitimate legacy detail display. Reinspection found the backend enum values added to the validator. Source resolution only; runtime regression evidence remains pending.
2. **Resolved baseline observations.** Before implementation arrived, legacy POST remained enabled and old service audit included title. Current controller returns 410 and the new create transaction writes only safe status audit metadata. These were baseline observations, not outstanding findings against the new implementation.

No further concrete defect established by source inspection at this checkpoint. This is not a claim of exhaustive correctness.

## Reviewed invariants and outstanding gates

Create locks organization, account and membership before Team/manager, checks fresh enabled identity and credential version, and authorizes before original-key replay. Canonical payload hashes include Team. Receipt replay precedes current Team/manager eligibility checks. Mission, receipt and safe audit insert share the transaction. Manager candidate queries constrain organization, Team, level and status and escape literal search. Detail manager-name join also constrains current Team. Strict deserializers reject scalar coercion. POST response validation checks scope, identity, CREATED status and request field equality; unknown outcomes keep the request frozen. Setup/400 rejection clears the attempt and permits a fresh key. Planning remains unavailable.

Require completed backend results, native PostgreSQL probe and all browser projects before acceptance. Source inspection of test code does not prove it passed. Browser viewport execution and visual/keyboard interaction are pending; no Figma pixel comparison, screenshot evidence, production load/failover test or production deployment is claimed. Sonar is paused, not passed. Integration rollback evidence must remain distinct from real PostgreSQL evidence. Main owns workflow/probe/fixture execution and CI evidence updates.
