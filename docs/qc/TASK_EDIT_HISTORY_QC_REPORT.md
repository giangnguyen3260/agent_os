# Task edit history QC evidence

Candidate: worktree on feature/task-edit-history, stacked on PR30 closure ae1f19660b039edf1719dd9c15dee88658519dad. No merge or deployment.

## Executed locally

2026-10-27: frontend lint, full source formatting check, tooling security checks, Task History production DTO/pagination/authority/stale-response harness, existing Task Edit harness, and Angular production build passed. Java import rules, Python native-probe syntax and browser spec syntax passed. Browser collection finds21 cases (7 × desktop/tablet/mobile), not executed cases.

## Pending acceptance

The current host has Java17, no Maven/Java21 or Docker. A formatter download timed out. Backend tests, Google Java formatting, native PostgreSQL history probe, real-backend browser execution and responsive capture inspection require CI. No prior PR30 counts are reused as history acceptance. CI will run existing retained gates plus the new native probe and browser slice. Sonar remains paused.

## Scope

Read-only existing persisted task edit provenance, current ADMIN-only, before/after snapshots, actual current actor display name, fixed20 paging. Empty history has no invented baseline. No runtime/execution history or mutations. Offset pages are fresh snapshots and can shift under concurrent edits; no stable cross-request cursor is claimed. Before text is preserved verbatim; malformed selected provenance fails the whole page closed. See frozen contract and independent review/testcases.
