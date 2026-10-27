# Task edit history QC evidence

Candidate: worktree on feature/task-edit-history, stacked on PR30 closure ae1f19660b039edf1719dd9c15dee88658519dad. No merge or deployment.

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
