# Mission creation QC report

Decision: **PASS for bounded Mission creation/read delivery**. Draft PR13 remains stacked on unmerged PR12. This is not production acceptance or planning/execution delivery.

## Exact candidate and execution

Application `91fac7ef98e272e7be553ac52ebae098613f1c0e`; tested PR merge `8f418491e7b8a2f7072e41a65fa59f20069ae461` onto `4a8d335530c75094d2e592881d201729803b98f0`. Evidence timestamps below are UTC on21October2026. Final evidence-only documentation commit is separate from this tested application revision.

| Gate | Actual completed evidence |
|---|---|
| Code quality | [Run39598891981](https://github.com/giangnguyen3260/agent_os/actions/runs/39598891981), job119218028546, success12:22:17Z;96backend tests,0failures/errors/skipped; Java imports/format, FE lint/format/tooling/build passed |
| Docker/PostgreSQL/browser | [Run39598891905](https://github.com/giangnguyen3260/agent_os/actions/runs/39598891905), job119218027730, success12:28:25Z; healthy root Compose, V008 schema history success, fixtures, frontend/API/proxy isolation passed |
| Browser regression | Completed12:27:39Z:222passed across1440/768/390, including33Mission instances and all189baseline instances; no retries |
| Native Mission probe | PASS12:23:51Z: PostgreSQL persistence/readback, no planning/tasks, canonical/concurrent replay, conflict rollback, manager/tenant scope, validation and fresh authorization; also malformed JSON/header/redaction and400/410 assertions |
| Existing native regressions | Agent creation concurrency, Studio replay/stale revision, activation no-mutation, Agent/Team directory projections, Workspace snapshot/pagination/safe events and auth account barrier passed |
| Restart/logout | PASS12:28:12Z: exact issued session survives backend restart; logout rejects replay |
| Local rendered UI |26mock API checks passed across1440/768/390 after final Forest input styling; screenshots inspected, validation/Back/retry/private clearing/GET-only recovery and compact-menu Escape/focus checked. These are visual/interaction evidence, separate from persistence |
| Independent QC | Source review and corrected HTTP handler/embedded-server test review completed; no open blocking finding. Coordinator inspected completed CI logs; independent reviewer did not rerun the entire CI suite |

## Covered behavior

Three steps Goal & context → Execution → Review save a real CREATED Mission. Required title/goal/output, fixed scoped Team, optional same-Team DRAFT/ACTIVE manager, priority, optional deadline/budget/context and HUMAN_REQUIRED configuration persist and reload. DRAFT manager and configured limits are not runtime authorization or enforcement.

Eleven real-backend Mission scenarios cover validation, no early writes, Back/Cancel, fixed Team accessibility, manager paging/literal search, DRAFT labels, actual creation/reload, ambiguous POST replay, GET-only recovery, canonical/concurrent replay, invalid input, manager/tenant/role/CSRF denial, legacy POST closure, fresh-role revocation, editable setup rejection and locked idempotency conflict. Workspace baseline tests check ADMIN Create Mission link and MEMBER absence. See [cases](MISSION_CREATE_TEST_CASES.md).

Every request/replay checks enabled identity, credential version and membership. Creation locks org/account/membership then Team/manager. Team is part of canonical request hash. Durable original receipts precede current eligibility checks after fresh authorization. Mission, receipt and safe status-only audit are atomic. Detail manager-name join constrains current Team. Private goal/context/title do not enter audit payloads or malformed-input errors.

## Findings and retest

The initial application74b1784 run39597243912 reported219passed/3failed: numeric-string input returned403 instead of400 at all widths. Framework sendError redispatched to the denied /error route; MockMvc alone did not reproduce this. Direct sanitized conversion/binding/status envelopes fix the boundary without relaxing security. A real embedded-servlet regression covers invalid JSON/numbers/keys, redaction, legacy410/planning501 and zero writes. Corrected full gate above passed; failed-run native probes after the browser step were skipped, not accepted as evidence.

An early source review found nonexistent PLANNED/PAUSED statuses in the frontend validator; actual READY/WAITING_APPROVAL/REVIEW enum values now match the backend. Rendered QA corrected native input styling and exact accessible field labels. No open blocker remains for the bounded scope.

## Limits

Planning, task creation/execution, effective grants, provider integration and activation remain unavailable. Manager is optional for saving and must be ACTIVE for future planning. No generated plan, task or model call is fabricated. Sonar remains paused, not passed. Field encryption/KMS, production scale/failover and comprehensive accessibility/security acceptance remain open; see [V008 rollout](../../backend/MISSION_CREATE_MIGRATION.md). No Mac deployment or image publication is claimed. All PRs remain draft/unmerged.
