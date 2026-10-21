# Mission creation test cases

Contract: `docs/engineering/MISSION_CREATE_CONTRACT.md`. Scope is persisted CREATED Mission configuration, with no planning, execution, provider calls or attachment retrieval.

## Real-backend browser suite

Run from `frontend`: `E2E_PASSWORD=<bootstrap password> npx playwright test mission-create.spec.mjs`. Use the existing `E2E_BASE_URL` override when the stack is not on port 8080. The existing projects run at 1440×1000, 768×1024 and 390×844. Eleven scenarios produce 33 instances. Test discovery is not browser execution.

Prerequisites: migrated real PostgreSQL stack, Main's `scripts/mission-create-ci-fixture.sql`, installed Chromium, and Docker Compose database access for duplicate-row and membership-revocation assertions. Each viewport uses its dedicated `mission-desktop/tablet/mobile@example.test` ADMIN account; read-only MEMBER checks use `mission-member@example.test`. No successful business data is mocked. Interception is limited to injected error/ambiguous responses; the ambiguous-POST case forwards the first request to the backend before dropping/replacing its response.

| ID | Automated scenario | Required assertions |
|---|---|---|
| MC-B01 | Validation, Back, Cancel and keyboard | Fixed real Team name; whitespace title rejected; out-of-range budget rejected; no POST before Review; keyboard Back preserves values and exact context; dismiss Cancel retains review; accept Cancel leaves without a write; compact menu Escape restores focus; no horizontal overflow |
| MC-B02 | Review, create and reload | Eligible manager paging in UI; DRAFT labelled configuration only; ACTIVE selected on desktop/mobile and DRAFT on tablet; reviewed title trimmed; goal/output, exact context, priority, strict leap date, USD budget and approval persisted; status CREATED; scoped URL and GET readback; reload; plan not generated and execution unavailable |
| MC-B03 | Unknown POST outcome | Real first POST returns 201; desktop loses response, tablet receives injected 503, mobile receives malformed 201; Back locked; retry sends identical key and body; backend replay returns 200 and original ID; SQL verifies exactly one Mission with unique title |
| MC-B04 | Detail read failure | Real confirmed POST followed by injected detail GET 503; Reload current data recovers; exactly one POST |
| MC-B05 | Eligible managers | Real pages 20+3 ordered by name/ID; only this Team's DRAFT/ACTIVE MANAGER agents; literal percent/underscore/backslash do not act as wildcards; case-insensitive search |
| MC-B06 | Canonical and concurrent replay | Optional manager accepted; empty/null context canonicalized to empty; trimmed title canonicalizes; same key returns original response; changed goal yields IDEMPOTENCY_CONFLICT; concurrent identical requests yield 201+200 and same ID |
| MC-B07 | Input and scope rejection | Text bounds, whitespace required fields, context limit, negative/overprecision/too-large/string money, invalid dates/priority/approval rejected; other-Team/suspended/non-manager rejected; archived Team conflict; foreign Team 404; foreign org 403; missing CSRF 403; legacy POST 410 |
| MC-B08 | MEMBER and scoped detail | MEMBER reads saved detail; MEMBER cannot create or list managers or see wizard fields; same-org wrong-Team detail 404 |
| MC-B09 | Fresh authorization | Successful key replay denied after ADMIN→MEMBER SQL mutation; stale wizard save denied and private form removed; role restored in finally using per-viewport account |
| MC-B10 | Setup conflict recovery | Injected MISSION_SETUP_CONFLICT unlocks fields; changing priority and reviewing again creates through real backend with a fresh key |
| MC-B11 | Idempotency conflict | Injected IDEMPOTENCY_CONFLICT keeps review frozen and disables resubmission |

Baseline `team-workspace.spec.mjs` additionally asserts ADMIN receives the scoped Create Mission link and MEMBER does not. A search found no baseline E2E dependency on the former disabled action or legacy mission POST.

## Backend and database evidence gates

Main owns `scripts/mission-create-probe.py`, fixture and workflow. The probe must verify native PostgreSQL persistence, identity attribution, no task generation, safe audit metadata, independent-session concurrent replay, rejected-input nonwrites, receipt cardinality and fresh role authorization. Backend worker owns `MissionCreateIntegrationTest`: inspect its completed results for transaction rollback of Mission/receipt/audit, replay before eligibility, fresh enabled/credential-version/membership checks on read/write/replay, strict deserialization, scoped manager search and legacy closure.

Acceptance requires recorded results, not merely authored tests. Forced receipt-insert rollback is backend integration coverage, not native PostgreSQL browser coverage. Review maximum accepted text/money values and malformed success scope/identity separately if additional evidence is needed; browser negatives are not an exhaustive acceptance-boundary proof. Sonar remains paused.
