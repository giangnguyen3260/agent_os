# Mission directory QC — acceptance pending

The six-scenario browser specification is prepared for 18 instances across 1440/768/390 widths. No real-backend browser instance has executed in this workspace. No acceptance PASS is claimed.

## Actual checks on 21 October 2026 UTC

| Check | Actual result |
|---|---|
| Read contract and disposable SQL fixture | Completed; 27 missions, 25 Teams, mission 25 has 7 tasks / 2 completed / 2 pending. This is source evidence, not database execution. |
| `node --check frontend/tests/e2e/mission-directory.spec.mjs` | PASS: JavaScript syntax only. |
| `npx --prefix frontend playwright test --config frontend/playwright.config.mjs mission-directory.spec.mjs --list` | PASS: discovers 18 instances, six per width. Discovery does not execute browser tests. |
| Local app readiness | BLOCKED: localhost:8080 refused connection; no E2E base URL/password supplied; `docker` executable unavailable. No attempt to bootstrap or modify Main's runtime/fixture/workflow. |
| FE selector reconciliation | Completed against newly delivered component/template/service: test IDs and contract labels match; chooser search uses form submission (Enter), not input debounce. |
| Independent source QC | Backend and new directory component/template/service read. Full navigation/unsaved-guard review remains pending; sidebar finding below. |
| Native PostgreSQL probe, backend tests, CI workflow | NOT RUN by QC; Main owns these gates. No external CI result has been independently inspected. |

## Limited read-only source findings

Read `MissionDirectoryController`, `MissionDirectoryResponse`, `MissionDirectoryService` and the authorization checks in `MissionCreateService`. The directory uses a read-only REPEATABLE_READ transaction, bound query parameters, explicit literal wildcard escaping, organization-constrained Team lookup, independently grouped task counts, and organization/Team-constrained manager joins. The response record excludes goal and task payloads. Authorization delegates to fresh enabled/credential-version/membership checks. These observations do not establish runtime correctness, query execution, snapshot isolation behavior or no-write evidence.

Corrected the inherited test's unsupported positive backslash-fixture assumption: the fixture title contains `%_!`, while backslash should yield zero results. Tightened exact 27/20/7 paging, full safe projection, known mission 25 counts, 25-Team uniqueness, foreign-Team rejection and archived create selection. Successful business responses are never fabricated; only failure/malformed envelopes are intercepted, followed by recovery against the real backend. Membership revocation is real SQL with restoration in `finally`.

## Remaining gate work

Execute all 18 browser instances against fresh fixtures after Main runs its native probe. Independently review final route/auth cancellation, malformed-envelope validation, navigation and guard sources. Main must supply native PostgreSQL projection/no-write evidence and workflow results for independent inspection. Also review the additional coverage limits listed in [test cases](MISSION_DIRECTORY_TEST_CASES.md); no delayed-response race, credential revocation, broad accessibility or production acceptance is certified here.

Only the E2E specification and the two Mission directory QC documents are in QC's edit scope. No automation, commit or push was performed.

Delivered FE review: directory loads clear private rows before reauthorization; subscriptions are cancelled and guarded by epochs/account identity; malformed/foreign data fails validation; chooser cancellation and archived create disabling are implemented. Finding for FE: the directory sidebar also marks Teams as selected/current, rather than linking to Teams, while Missions is current. This source issue remains open at this inspection. No browser assertion or visual acceptance is inferred from this review.

Further route source review confirms both scoped wizard/detail routes retain `canDeactivate -> canLeave()`, whose unsaved branch asks for discard confirmation; Browse missions is a router link through that guard. Team Workspace exposes View missions with Team query scope. This is read-only guard wiring evidence, not executed discard-dialog coverage.

## Coordinator pre-publication checks

Full local backend Maven verify completed successfully:103tests,0failures/errors/skipped, including7new Mission directory integration tests. FE format/lint/build and Google Java import checks passed. Coordinator rendered35mock API checks across1440/768/390 including bounded paging/literal search, empty/error/null/scope/count rejection, private clearing, MEMBER create visibility, chooser pagination/filter/direct-create/archive disabling, Escape/focus and zero business writes. Screenshots inspected. These checks do not substitute for native PostgreSQL or real-backend browser execution. Final Playwright collection240instances includes18new; execution pending.

Sidebar Teams-current issue is corrected in final source; Teams is linked and Missions alone current. Whitespace assertions now match recorded counts using actual-source regex proof. No remaining blocker established by coordinator source/render checks; independent final review and actual CI evidence pending.
