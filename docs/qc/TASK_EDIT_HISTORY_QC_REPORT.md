# Task edit history QC evidence — C4 PASS

Accepted application/test/fixture/workflow candidate: C4 `77733d1757b839e3b127801a7d191d136a0b6dcc`, tree `bcbf459182258ae0932b2408e0c204c98e3dd1d1`, stacked on PR30 closure `ae1f19660b039edf1719dd9c15dee88658519dad`. Final current results below supersede historical checkpoints. No merge or deployment.

## Executed locally

2026-10-27: frontend lint, full source formatting check, tooling security checks, Task History production DTO/pagination/authority/stale-response harness, existing Task Edit harness, and Angular production build passed. Java import rules, Python native-probe syntax and browser spec syntax passed. Browser collection finds21 cases (7 × desktop/tablet/mobile), not executed cases.

## Pending acceptance

The current host has Java17, no Maven/Java21 or Docker. A formatter download timed out. Backend tests, Google Java formatting, native PostgreSQL history probe, real-backend browser execution and responsive capture inspection require CI. No prior PR30 counts are reused as history acceptance. CI will run existing retained gates plus the new native probe and browser slice. Sonar remains paused.

## Scope

Read-only existing persisted task edit provenance, current ADMIN-only, before/after snapshots, actual current actor display name, fixed20 paging. Empty history has no invented baseline. No runtime/execution history or mutations. Offset pages are fresh snapshots and can shift under concurrent edits; no stable cross-request cursor is claimed. Before text is preserved verbatim; malformed selected provenance fails the whole page closed. See frozen contract and independent review/testcases.

## Candidate C1 correction

C1 c5bf58a647cdd04087a5538ae07dab4724ccec58 / tree771cfe7f189b064d31491e0de413071eb0564c2e: Quality40510751010 stopped at Google Java formatting (six files); no backend test execution or quality acceptance. Static QC also found history link under the Task Detail error branch; moved into successful ADMIN actions independently of DRAFT. Added a dedicated safe history-provenance exception/HTTP500 mapping and actual native/browser corruption checks. Browser suite now24 collected cases (8×3), not24 passes. A separate CI diagnostic publishes a Google formatting patch without replacing or weakening the required quality gate.

## C3 verification candidate preparation

Applied actual Google Java Format patch artifact12737980398 from C2 diagnostic job122213367749 (run40511231198). The required quality check remains unchanged. Independent QC found native session reuse after disabled/credential revocation; the probe now authenticates fresh after each restored condition, and expects the existing security filter's HTTP401 for invalidated credentials/accounts versus403 for lost ADMIN. Matching new MockMvc assertions corrected. No security policy was relaxed. C3 actual runtime evidence remains pending.

## C3 runtime finding / C4 correction

C3 Docker40511651266/job122214836398 passed formatting/compilation and executed409 regular tests with1 failure,0errors,0skips. The new noncanonical-ID test substituted a different unauthorized organization and incorrectly expected controller400; established security transport denies that organization403 before controller parsing. C4 adjusts only that assertion to403 for the foreign organization and retains400 for malformed child IDs and zero-write checks. No application or security behavior changes. Native/browser/visual stages were not reached on C3.

## C4 completed backend and native evidence

C4 head77733d1757b839e3b127801a7d191d136a0b6dcc, treebcbf459182258ae0932b2408e0c204c98e3dd1d1, baseae1f19660b039edf1719dd9c15dee88658519dad. Quality40512442303/job122217509810 completed/success:480tests,0failures/errors/skips (409regular +23TeamSettingsPostgresIT +48TaskDraftEditPostgresIT), Google formatting/imports, FE audit0vulnerabilities and retained style/tooling/build passed. Independent QC verified mergec3cc271caad1ece9e624ca291e784cdb2b95ab36 has exact candidate tree/parents.

Docker40512442174/job122217510792 completed history fixture provisioning and history native-probe steps successfully at13:36:32UTC27October2026. Browser/responsive and whole Docker job are still pending at this checkpoint; no inferred browser count is reported.

## Final current C4 acceptance

Quality40512442303/job122217509810 and Docker40512442174/job122217510792 are completed/success. Docker log checkout is tested mergec3cc271caad1ece9e624ca291e784cdb2b95ab36, whose tree/parents independently match C4/base. Docker regular backend409/0/0/0 at13:31:47UTC; Quality480/0/0/0 includes both explicit real-PostgreSQL suites. No skipped tests or H2 substitution for native suites.

Native history PASS at13:36:32.954UTC: exact persisted HTTP edit receipt correlation, nullable projection, static25-row paging, strict wire rejection, current role/account/credential scope denials, safe real HTTP corruption500/restoration and no-write business digests. Earlier retained native probes also passed. New browser24passed(57.3s) at13:50:05.125UTC; retained baseline678passed(18.5m) at14:08:33.281UTC: **702 actual browser instances**, one worker, zero retries. Dedicated history harness passed in the Docker browser lane. Expected totals were not substituted for execution.

Exact-session backend restart/logout PASS14:09:31.471UTC. This is the retained generic session/account smoke, not a separately executed history-endpoint-after-restart probe. Docker run completed/success updated14:10:04UTC. Slow browser dependency provisioning delayed this run but did not skip or change acceptance gates.

Main verified and visually accepted exact15responsivePNG/15JSONL records at1440/768/390; see [visual manifest](TASK_EDIT_HISTORY_VISUAL_MANIFEST.md). Artifact12740900228 ZIP SHA256 ff41a4229583bafdd04f2ca28cf4dbf4efe60a2cd5bf70bb103fcdf96db256da matches actual upload/download. Main reviewed top/bottom contact views for all captures and original-resolution representative cards. No blocking layout/contrast/privacy finding. Runtime acceptance is bounded to read-only task configuration provenance, not execution history, tamper-proof storage or stable cross-request offset paging. Source/Quality review is independently recorded in [final review](TASK_EDIT_HISTORY_FINAL_REVIEW.md).

Closure changes only Markdown; application, tests, fixture/probe and workflow bytes remain exactly C4. Later documentation-triggered CI is not represented as another completed application run. PR31 remains draft/unmerged; no deploy. Sonar paused.
