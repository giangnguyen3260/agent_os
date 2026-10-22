# Task creation QC — 22 October 2026

Status: PASS for bounded Create Task intake delivery. Backend/quality, native PostgreSQL,300browser instances and Docker/restart/logout passed. Branch feature/task-create stacks on draft/unmerged PR16.

## Scope

Forest S17 real task intake: blank scoped form, title/description/acceptance/priority, optional same-Team owner and up to20same-Mission dependencies. Atomic DRAFT persistence maps to existing Team Intake lane. Fresh ADMIN authorization and immutable canonical receipt; replay before mutable eligibility but after fresh auth. Mission pause/revision conflict enforced. Task Detail reads saved fields and safely handles legacy nulls and moved owners.

No execution, positive activation, model/tool grants, plan-version validation, outputs or task/dependency editing. Intake revision is not a plan version. Existing unsupported controls remain disabled. No production sample data.

## Evidence

- Local Java21 full Maven verify:121tests, zero failures/errors/skips, seven new TaskCreate cases. Google formatting, packaging and JaCoCo report passed. Initial expected-key mismatch in the baseline Task Detail test was updated for the additive DTO fields, with nullable legacy assertions retained.
- Fresh-auth replay test covers disabled account, changed credential version, MEMBER downgrade and removed membership; context/candidates/replay deny and business snapshot stays unchanged.
- A baseline Mission HTTP redaction assertion falsely matched `1e2` inside random UUID metadata. It now asserts error-subtree redaction, raw-body private-text rejection and valid UUID metadata separately; corrected direct and full runs passed without retries.
- Independent QC authored eight browser cases across three widths (24instances), all24instances passed against the real backend, alongside276baseline instances (300total). Candidates wait on exact query/page and use isolated surface Missions.
- Native PostgreSQL probe passed and compared stored task fields/owner/dependency rows, receipt/event/revision cardinality, canonical replay, concurrent same-key replay/two-key stale conflict, scoped candidates, member/CSRF/revocation denial and error no-write snapshots. Disposable fixtures only.
- Existing Task Detail read probe extended to independently compare additive legacy fields and include dependency/receipt tables in no-write snapshot.
- Backend receipt-failure integration test injects a rejected receipt after inserting a task with a real existing dependency: task/edge/revision/receipt/audit rollback asserted.

- Coordinator rendered mock UI:65 checks passed at1440×1000/768×1024/390×844, all three screenshots inspected. Covered real rendered form, owner/dependency paging/select/focus, unsaved Cancel, mobileMenuEscape, no overflow, current-scope/null/paused/member handling, happy detail readback, ambiguous503/mismatched-success locked retry with identical body/key, stale409 refresh/new-key/version recovery, contradictory canCreate metadata rejection and max20 dependency selection/deselection boundaries. Mock QA verifies UI behavior, not PostgreSQL persistence.

## Published candidate and CI

Application candidatef9bcbcf17b9b643843bddd17948b9550e8d4c412, tested merge82b758425e4c2c3ee357d4fcf1ade2d9ff87ae04 onto basea30ed3211f403e13d00e056d513b4c30949e6ec6. Draft PR17 remains unmerged.

- [Quality run39774232545](https://github.com/giangnguyen3260/agent_os/actions/runs/39774232545), job119809032540 PASS:121tests with zero failures/errors/skips11:56:46Z, BUILD SUCCESS11:56:50Z; FE formatting/lint/tooling/auth-generation/build passed. Coordinator inspected decoded completed log.
- [Docker run39774232547](https://github.com/giangnguyen3260/agent_os/actions/runs/39774232547), job119809032401 completed SUCCESS12:17:38Z. Coordinator inspected decoded log at exact tested merge82b758425e4c2c3ee357d4fcf1ade2d9ff87ae04. Healthy Compose/V009 schema and all fixture/probe steps passed. Native Task Intake probe PASS11:59:27Z, including field/owner/dependency persistence, moved-owner sanitization, receipt/event/revision counts, concurrent canonical replay/two-key stale conflict, scope/CSRF/revocation/error no-write checks. Browser300passed(5.4m)12:16:51Z. All post-browser probes passed, including account-first login/reset barrier. Issued session survived backend restart and logout rejected replay12:17:25Z.
- Host browser dependency preparation in the first Docker run took about12minutes and then succeeded. A docs-only checkpoint6417ac2739dc600b1605f8f6912181b1e69acee0 started an independent runner while waiting; it preserves identical application source. Its quality run39776236591/job119815778702 also passed121tests; Docker run39776236633 was still running when the already-completed first gate was accepted. No additional app retest is inferred from a docs-only publication.

## Acceptance limits

This gate accepts intake/configuration creation, persisted reads and regression checks. No task execution, positive activation/effective grants, plan-version validation, run/output history, task/dependency editing, pause controls, full production security/scale/release acceptance or deployment to the user's Mac. Intake revision is not a plan version. Owner assignment is configuration, not executable capacity. Sonar remains paused, not passed. Final publication after the tested candidate changes only documentation.

## Existing dependency audit finding

Completed Docker install log and coordinator `npm audit --json` report4affected packages:1low/3high/0critical. The existing development toolchain includes @angular/cli and its @modelcontextprotocol/sdk dependency, plus vite under @angular/build. Package manifests/lockfile are unchanged by this feature. This functional gate does not claim a clean dependency audit. Triage and patch these development dependencies before release; do not use `npm audit fix --force` to silently change the Angular major version. Production release/security acceptance remains incomplete. Audit evidence is separate from the paused Sonar gate.

## Follow-up tooling patch

The audit findings recorded above describe the Task Create candidate at its acceptance time. Follow-up draft PR [18](https://github.com/giangnguyen3260/agent_os/pull/18) addresses the SDK/Vite graph without changing Task Create behavior. See [Tooling security QC](TOOLING_SECURITY_QC_REPORT.md) for the separate candidate, audit and execution evidence; historical findings above remain unchanged.
