# Task creation QC — 22 October 2026

Status: backend local gate and rendered mock UI passed; service-backed CI acceptance pending. Branch feature/task-create stacks on draft/unmerged PR16.

## Scope

Forest S17 real task intake: blank scoped form, title/description/acceptance/priority, optional same-Team owner and up to20same-Mission dependencies. Atomic DRAFT persistence maps to existing Team Intake lane. Fresh ADMIN authorization and immutable canonical receipt; replay before mutable eligibility but after fresh auth. Mission pause/revision conflict enforced. Task Detail reads saved fields and safely handles legacy nulls and moved owners.

No execution, positive activation, model/tool grants, plan-version validation, outputs or task/dependency editing. Intake revision is not a plan version. Existing unsupported controls remain disabled. No production sample data.

## Evidence

- Local Java21 full Maven verify:120tests, zero failures/errors/skips, six new TaskCreate cases. Google formatting, packaging and JaCoCo report passed. Initial expected-key mismatch in the baseline Task Detail test was updated for the additive DTO fields, with nullable legacy assertions retained.
- Independent QC authored eight browser cases across three widths (24instances), syntax/discovery/style checked; real service acceptance pending. Candidates wait on exact query/page and use isolated surface Missions.
- Native PostgreSQL probe will compare stored task fields/owner/dependency rows, receipt/event/revision cardinality, canonical replay, concurrent same-key replay/two-key stale conflict, scoped candidates, member/CSRF/revocation denial and error no-write snapshots. Disposable fixtures only.
- Existing Task Detail read probe extended to independently compare additive legacy fields and include dependency/receipt tables in no-write snapshot.
- Backend receipt-failure integration test injects a rejected receipt after inserting a task with a real existing dependency: task/edge/revision/receipt/audit rollback asserted.

- Coordinator rendered mock UI:65 checks passed at1440×1000/768×1024/390×844, all three screenshots inspected. Covered real rendered form, owner/dependency paging/select/focus, unsaved Cancel, mobileMenuEscape, no overflow, current-scope/null/paused/member handling, happy detail readback, ambiguous503/mismatched-success locked retry with identical body/key, stale409 refresh/new-key/version recovery, contradictory canCreate metadata rejection and max20 dependency selection/deselection boundaries. Mock QA verifies UI behavior, not PostgreSQL persistence.

## Pending verification

All real backend browser regressions/new cases, healthy Docker/PostgreSQL/migration/native probes and restart/logout. Exact published candidate, merge and run IDs will be recorded after completion. Initial local JVM self-attachment requires explicit ByteBuddy startup agent; this environment setup is not a product change. Sonar remains paused, not passed. Full production security/scale/release acceptance and deployment to the user's Mac are not claimed.
