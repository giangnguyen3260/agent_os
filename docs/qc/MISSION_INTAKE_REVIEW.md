# Mission intake independent BA + QC review

Status: source review and runnable browser suite prepared; **no runtime acceptance claimed**.

Reviewed shared tree on 2026-10-27, based on HEAD `5597bac2822107f2bb47892982148f27b4bd1ffc` plus concurrent uncommitted implementation. This is not an immutable implementation commit gate. Frozen contract SHA256: `955d921f605f310a5753d3ac8d3ffe19ea23c86b41e42966a27fb124a5b50be6`.

Ownership: only this review, `docs/qc/MISSION_INTAKE_TEST_CASES.md`, and new `frontend/tests/e2e/mission-intake.spec.mjs`. No production edits, commits, push, merge, deployment, or Sonar run by this reviewer.

## Source findings requiring coordinator disposition

| ID | Severity | Finding | Required resolution |
| --- | --- | --- | --- |
| MI-R1 | Resolved in source, runtime pending | `mission-intake-validation.ts` treats Mission `PLANNING` and `REVIEW` as unknown, while `MissionIntakeService.blockers` and existing Draft Edit recognize both and return `MISSION_NOT_EDITABLE`. The valid backend context is rejected instead of displaying the contract's ordered blockers. | Parallel frontend now recognizes PLANNING/REVIEW as known noneditable states. Validate both against real backend; keep unknown values fail closed. |
| MI-R2 | Resolved in source, runtime pending | `TaskCreateService.parent` still uses `!ARCHIVED` for Team eligibility, accepting unknown Team status while intake and Draft Edit fail closed. This predates intake controls but is relevant to actual shared policy. | Parallel backend now uses explicit DRAFT/ACTIVE allowlist, retains MAX guard and replay ordering, and adds unknownTeamStatusFailsClosedWithoutEffectsAndRetainsCreateReplay. Runtime result not independently observed. |
| MI-R3 | Resolved in source, runtime pending | Initial Task Create source had no Long.MAX_VALUE guard before SQL increment. Parallel backend now adds `< Long.MAX_VALUE` to canCreate. | Native PostgreSQL probe must prove exhausted create returns domain conflict, no task/receipt/audit mutation, and historical replay remains accepted. |

The browser suite asserts contract behavior, not alternate behavior to accommodate findings. Assertions use semantic controls and supplied hooks; no mocked successful business operation.

## Independent policy and transaction assessment

Intake update source locks organization, account, membership, Team, then exact scoped Mission; reauthorizes after parent acquisition. Its READ_COMMITTED transaction covers flag/version/time, scoped actor/key receipt and one safe audit event. Receipt lookup occurs before current fingerprint, eligibility and no-op checks. Same key/body returns historical receipt; changed canonical body conflicts. Existing Draft Edit uses the same parent lock order, includes Mission intake flag/version in its fingerprint and blocks fresh edits while paused. Task Create uses organization/account/membership then Team/Mission locks, so these operations serialize, though lock-wait behavior and rollback require actual PostgreSQL evidence.

Context/receipt service reads use REPEATABLE_READ. Intake versions remain decimal strings across context, command, receipt, digest and frontend BigInt comparison. Strict wire code rejects duplicate/extra fields, trailing JSON, non-UTF8 bytes, wrong scalar types, out-of-range/noncanonical decimal, invalid fingerprint/key, query strings and GET bodies. The pre-controller interceptor freshly verifies enabled account/credential and raw ADMIN membership before parsing. These are source observations, not executed wire results.

Receipt decoder checks exact keys/types/scope, canonical versions and increment, flip, event UUID, timestamp and digest shape; failures map to safe `MISSION_INTAKE_UNAVAILABLE`. Audit metadata contains only event/version/paused data and structured Mission ID; DTOs do not include task content, private Mission configuration or credentials. Migration V018 adds the scoped receipt table, exact Mission FK, human actor FK and unique event ID without seeds/roles. No claim of database immutability enforcement: application code inserts receipts and does not update them.

Frontend clears returned context and receipt before fresh authority reads, binds source version/fingerprint and actor, uses cancellation/epoch/destruction checks, freezes original unknown body/key, and retains only locator after authority loss. Review has an initially unchecked acknowledgment; save performs fresh authority/context checks. Explicit receipt lookup and identical retry are separate actions; receipt is labeled historical. Dirty navigation/native beforeunload and responsive menu are present in source. Actual held response probes remain essential to establish these fences work in the browser.

## Validation and execution boundary

- `node --check frontend/tests/e2e/mission-intake.spec.mjs`: passed.
- Prettier formatted the owned spec.
- `npx playwright test tests/e2e/mission-intake.spec.mjs --list`: passed; 17 scenarios × desktop/tablet/mobile = 51 cases. Discovery is not test execution.
- Local HTTP probe to default `127.0.0.1:8080` failed to connect; no local Docker executable and no supplied E2E_PASSWORD. No real backend browser run or screenshot was fabricated.
- Coordinator reports exact prefix530 fixture and is correcting all Azure APT source URLs to canonical HTTPS mirrors, retaining signed packages; new-browser execution/artifact upload precedes the full existing baseline. Those external run results are not independently observed here.

Run from frontend against the real built stack with coordinator credentials: `npx playwright test tests/e2e/mission-intake.spec.mjs --workers=1 --retries=0`. Use configured projects at 1440×1000, 768×1024, 390×844. Optionally set `E2E_BASE_URL` and `E2E_VISUAL_DIR`. Do not skip failing cases or add retries. Fixture reset is coordinator-owned.

Successful captures append `mission-intake-manifest.jsonl` only after semantic state assertions, overflow check and screenshot completion. Each row records project, viewport, state, filename, test, path, capture timestamp and image SHA256. Required states: edit/review/saved/blocked/conflict/unknown/denied/recovered in each project. No manifest or image evidence exists from this reviewer yet; upload actual captures and Playwright report before acceptance. Auth traces/video/storage are not requested.

Native coordinator evidence still required: parser matrix and int64 boundaries, same/different-key races, Task Create/Draft Edit races, lock-wait revocation, forced receipt/audit rollback, historical replay after lifecycle changes, corrupted receipt safe500, audit projection and migration retention. Bind final run commands/results, source hashes and CI artifacts to the final implementation commit/tree. Do not reuse baseline counts as proof of this feature.


## Read-only review of coordinator native probe

Reviewed `scripts/mission-intake-probe.py` SHA256 `4c0d8211aba84730d3207547ddd068a618c5b1d853297d5a733e728ecf47d586`. No script modification or execution by this reviewer. Coordinator reports completion; that report is not substituted for an independently observed execution log. Fixture IDs and flattened Draft Edit command match actual wire DTOs. Task Create numeric expectedIntakeVersion is correct for its existing API; intake's own versions remain strings.

The organization-lock probe observes a real PostgreSQL blocker PID before committing role/disabled/credential mutation and checks HTTP denial plus unchanged business snapshot. Receipt/audit trigger failures independently use whole-business-table snapshots. Those are substantive checks. The following concrete assertion gaps should limit the claims made from a PASS:

| ID | Probe location | Defect and concrete false-positive path | Recommended coordinator correction |
| --- | --- | --- | --- |
| MI-P1 | historical replay calls to reject(), including accepted Task Create replay at MAX | reject() checks status and unchanged snapshot, but not response identity. An endpoint returning200 with a different receipt/DTO passes; lifecycle receipt GET does not prove POST replay returned the original. | Compare each replay response data with the captured original intake receipt or created Task detail, including paused and MAX cases. |
| MI-P2 | different-key intake race and Task Create/intake race | Assertions inspect sorted status codes only. Extra receipts/audits/increments, ghost task insertion from the rejected command, or wrong409 code can pass. Same-key race has stronger check_commit but still lacks whole-table allowed-delta/count assertions around the race. | Capture pre/post version, flag, task IDs and scoped receipt/audit counts; identify winner and assert exact effect, loser domain code, and stable unrelated tables. |
| MI-P3 | Draft Edit/intake race | Any409 is accepted and only a selected subset of task columns is compared afterward. A spurious edit rejection, or unauthorized change to another task's title/description/criteria/priority/revision/updated_at, can escape. Dependencies/history/receipts and exact accepted edit are not checked in this branch. | If201 assert exact four-field/revision/history/receipt result; if409 assert expected conflict/block code and zero edit effects. Compare all other tasks/columns/dependencies and permitted event deltas. |
| MI-P4 | check_commit() audit checks | Audit payload is parsed only to locate eventId. Extra private keys, wrong version/paused metadata or wrong actor_id pass. Mission updated_at is excluded from invariant comparison but never asserted to change or match committedAt; receipt created_at is also unchecked. | Assert exact safe payload keys/values, actual human actor ID, persisted timestamp equality to receipt, and updated_at change. |
| MI-P5 | strict-wire loops and PASS wording | Current loop covers only a subset of strict wire: no invalid UTF8, exact4096 accepted boundary, GET-body rejection, receipt query/body, malformed/multiple key, charset, trailing JSON, malformed fingerprint, or full int64 transition. A generic claim of complete strict-wire/full-int64 coverage is unsupported by this script alone. | Attribute missing cases to named backend tests/results or add actual requests. Keep this probe's claim scoped to its executed cases. |

These are probe false-positive risks, not independently reproduced production bugs. No certain startup/runtime crash was identified by source inspection. The probe intentionally assumes a fresh native fixture (version7); do not rerun it on its already-mutated fixture without the coordinator's disposable-stack reset. Its global business snapshots also assume no concurrent baseline writer. Cleanup uses global trigger/function names, so native runs must remain serialized.

## Coordinator final disposition — 2026-10-28

The independent review above remains a historical source review, not an independently observed runtime sign-off. Coordinator accepted source562ef49 after Quality40615549101 and Docker40615548941 completed successfully:526 backend tests,753 browser instances, native probes, restart/logout and24 reviewed responsive captures. See MISSION_INTAKE_FINAL_REVIEW.md and MISSION_INTAKE_QC_REPORT.md for immutable hashes and coverage limits. MI-R2/R3 have named executed backend regressions and native overflow/replay assertions. MI-R1's PLANNING/REVIEW mapping is resolved in source but has no separate named HTTP/browser scenario; lifecycle noneditable behavior is exercised with COMPLETED. Strengthened final native probe SHA256 af9ef4ce6005132a310374875022d3a486cd5188d40ce4a1c8c2f4ad1d76bfb3 addresses MI-P1–MI-P4 with exact response/effect/audit assertions and expands MI-P5; remaining parser/lock permutation limits are explicitly documented. Final UI copy is human-readable and its blocked cases passed. No second independent-agent final approval is asserted.
