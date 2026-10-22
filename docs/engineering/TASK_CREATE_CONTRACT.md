# Create Task intake contract

Branch feature/task-create stacks on draft/unmerged Task Detail PR16, basea30ed3211f403e13d00e056d513b4c30949e6ec6. Forest S17: desktop47:2885/mobile60:7733/tablet60:14349, Figma5wUjDKXg8vtRaJcO9XHfml. All three high-fidelity contexts/screenshots inspected. No static assets. React Simple Design System Code Connect controls unavailable in Angular; reuse Forest native form/dialog/token conventions.

## Product behavior

Mission Control ADMIN Add Task opens `/workspace/:id/teams/:teamId/missions/:missionId/tasks/new`. MEMBER read paths remain available, creation denied. One-page blank form: title*, current Mission* read-only, description/input*, acceptance criteria*, owner(optional), priority, dependencies(optional/max20). Real scoped/searchable/paginated candidates, no example business values. Cancel/route navigation prompts before abandoning unsaved text. Native menus/choosers support Escape/focus return. Responsive1440/768/390, Forest224sidebar/32desktop/24tablet/16mobile.

Create task stores DRAFT: the existing durable state mapped to the Team INTAKE lane. This preserves existing nine-state persistence instead of introducing a competing INTAKE enum. Owner is stored configuration assignment, not evidence of model/tool grants or executable capacity. Task does not execute, move to READY or generate a run. Existing task detail reads the new saved fields; legacy nulls are labelled unavailable.

Authoritative mission `task_intake_paused` and nonnegative `task_intake_version` are stored, not simulated. Creation allowed only in nonarchived Team, unpaused intake and Mission CREATED/READY/RUNNING/WAITING_APPROVAL. PLANNING/REVIEW/terminal Missions deny new intake. Version increments atomically on each accepted create and stale requests conflict. This is an intake revision, **not a plan version**: planning and plan-version validation remain unavailable because no authoritative plan exists. Pause controls are not delivered by this slice; server reads/enforces the persisted pause flag.

Dependencies point only from a new server-generated task identity to existing tasks in the exact same organization/Team/Mission, unique/max20 and never self. The only application edge writer creates immutable outgoing edges for a new identity, so it cannot introduce a cycle. No dependency editing/forward references or cross-Mission edges. Foreign/missing/ineligible owners and dependencies rejected atomically.

## APIs

Root `/api/v1/organizations/{org}/teams/{team}/missions/{mission}/tasks`.

- GET `/creation-context`: `{organizationId,teamId,teamName,missionId,missionTitle,missionStatus,intakeVersion,intakePaused,canCreate}`.
- GET `/eligible-owners?page=0&query=`: `{items:[{id,name,level,status}],page,size:20,total}`. Same organization+Team, DRAFT/ACTIVE, literal name search<=100chars.
- GET `/eligible-dependencies?page=0&query=`: `{items:[{id,title,status}],page,size:20,total}`. Exact current Mission, literal title search<=100chars. All nine saved states.
- POST root, UUID Idempotency-Key: `{title,description,acceptanceCriteria,priority,ownerAgentId,dependencyIds,expectedIntakeVersion}`. Required trimmed title<=255/description<=8000/acceptance<=4000; priorityLOW/NORMAL/HIGH/URGENT; optionalownerUUID; bounded unique dependencyUUIDs; nonnegative integer revision. Unknown/invalid fields rejected.

Fresh enabled account, credential version and current ADMIN role on every context/candidate/create/replay request. Foreign org403, wrong parent404, validation400. State/pause/owner/dependency409 TASK_SETUP_CONFLICT, stale revision409 TASK_INTAKE_CONFLICT, same key/different canonical request409 IDEMPOTENCY_CONFLICT. First create201; canonical same-key replay200 original durable response, even if mutable parent configuration changes afterward. Revoked accounts never replay. Canonical digest SHA256 includes parent scope, sorted dependencies and request; receipt stores original DTO. No request/private text in logs or audit payload.

Lock order matches existing organization/account/membership authorization, followed by Team/Mission and optional owner/dependency eligibility. Insert task, dependency rows, receipt, safe TASK_CREATED audit event and intake revision in one transaction. Replay occurs before mutable eligibility checks. No broad idempotency catch or duplicate writes on ambiguous network retry.

POST response and existing GET Task Detail envelope: `{generatedAt,task:{id,organizationId,teamId,missionId,title,status,pendingReason,waitingSince,resolver,createdAt,updatedAt,description,acceptanceCriteria,priority,ownerAgentId,ownerName,dependencyIds},mission:{id,title},team:{id,name}}`. Legacy details keep nullable fields and empty dependencies. Owner projection revalidates current same organization/Team before showing ID/name. Only scoped dependency IDs are returned. Approval/run/output actions remain unavailable.

## Frontend submission boundary

Fresh context validated against route/role. Controls become immutable after ambiguous result (network/5xx/malformed success) and retry uses same captured key/body; no uncertain new identity. Definitive400/409 enables safe correction/context refresh without losing form. Authorization loss/route/account changes cancel and clear private data; stale callbacks ignored. No sensitive form state stored in localStorage. A successful response is scope/shape validated before navigating Task Detail. Empty/loading/failed candidate pages, max20 dependency limit and malformed/foreign data handled explicitly.

## Verification

V009 migration must retain old rows and enforce dependency/receipt integrity in PostgreSQL. Native concurrent same-key and two-key stale-version probes; persistence/error rollback/scope/revocation/CSRF tests; rendered responsive/form/dialog/ambiguous retry coverage; full baseline regression suite. See docs/qc/TASK_CREATE_QC_REPORT.md and TASK_CREATE_TEST_CASES.md for executed evidence. Sonar remains paused, not passed. Production scale/full release acceptance not implied.
