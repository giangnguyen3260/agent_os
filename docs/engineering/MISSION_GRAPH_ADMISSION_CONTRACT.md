# Mission graph admission: next implementation contract

21 November 2026, Asia/Ho_Chi_Minh. Implemented candidate after Draft PR122 acceptance. API/UI, V054 admission records, runtime guards and dependency release are now implemented locally; matching-head native PostgreSQL and Vault/browser CI evidence is tracked separately. This does not claim merge, deployment or paid-provider acceptance.

## User outcome

An ADMIN explicitly admits the selected, current accepted complete Task graph. One atomic transaction creates one Run per required Task, moves the Mission READY to RUNNING and initializes its OPEN execution control. Root Tasks become READY with CREATED Runs. Dependent Tasks and Runs remain BLOCKED until every prerequisite has exact accepted output from the same admitted plan.

All snapshot Tasks are required. Admission does not enqueue dispatch, create Attempts/claims/reservations, invoke providers or guarantee whole-Mission budget availability. Model execution retains explicit per-Run preflight and human Start, plus current worker authority, accounting and capacity checks. Dependency order does not yet imply transferring prerequisite output into model context.

## Admission invariants

1. Use the organization serialization lock and current enabled credential-matching ADMIN membership. Organization and Team must be ACTIVE; exact scoped Mission must be READY.
2. Require the selected accepted revision to be the latest valid receipt-backed revision. Validate schema, digest, scope, source content, counts, field/aggregate bounds, owners, priorities, revisions, complete graph and dependency-first order on the server. Verify V036 membership and edges, then compare the normalized original snapshot against the entire current READY configuration. Neither a client fingerprint nor a stored `canReviewConfiguration` boolean grants runtime authority.
3. Require all current scoped Tasks to be exactly the admitted set, still DRAFT, with accepted content, owner and edit/owner/dependency revisions. No pre-existing Run or execution control may be adopted. Concurrent edit/create/acceptance must serialize and either precede successful fresh validation or make the admission command stale.
4. Write the Mission lifecycle, OPEN control, complete Task-to-Run mapping, Tasks and Runs, durable admission receipt and audit event in one transaction. Failure anywhere rolls everything back. All roots become Task READY / Run CREATED. Dependents become Task BLOCKED with nonblank reason/resolver and waiting timestamp, and Run BLOCKED with null wait_reason as required by existing schema.
5. Freeze selected membership after admission. New out-of-plan Tasks are never implicitly admitted. Reject the legacy single-Task plan creation path inside a Mission with a selected/admitted full plan.
6. Bind a separate Mission admission receipt to organization/Team/Mission, actor, canonical idempotency key, expected selected revision and configuration fingerprint, plus immutable Task-to-Run mapping. Matching historical replay rechecks current authority and returns the original receipt without resetting lifecycle, control, Tasks or Runs. Changed body or a new key after admission conflicts.
7. UI shows full admitted scope and root/dependency readiness, requires explicit confirmation and retains the exact pending command on ambiguous outcome. Readiness is distinct from per-Run execution authorization; no automatic retry or paid enqueue is implied.

## Dependency release

After ACCEPT, under the same organization serialization boundary, evaluate affected descendants against the admitted revision. Every prerequisite must have a COMPLETED Run from that exact plan and exact latest ACCEPT decision with valid reconciliation/output content and settled execution/accounting provenance. A Task label COMPLETED, a cross-plan output or a legacy fixture alone is insufficient.

Release only a Run blocked specifically by this admission's unmet dependencies, with no Attempt, dispatch, active claim or reservation. Never reset UNKNOWN, failed or previously executed work as an implicit retry. Once every prerequisite qualifies, atomically set Task READY and Run CREATED, clear all Task pending fields and record release evidence. REWORK and unresolved output do not release descendants. Control pause/cancel must continue to block Start, claim and model handoff.

## Existing integration points

| Code | Required change or retained boundary |
| --- | --- |
| MissionPlanAcceptanceService | Reusable fully validated accepted snapshot; current projection is not admission authority |
| New MissionExecutionAdmissionService/API/UI | Scoped atomic admission and durable replay receipt |
| MissionExecutionControlStore.initialize | Initialize OPEN after Mission lifecycle update within the same transaction |
| ExecutionStartService.requireEligible | Replace prerequisite status-only proof with same-plan exact accepted evidence |
| ExecutionReviewDecisionStore.decide | Trigger dependency release after ACCEPT; preserve REWORK behavior |
| OpenAiExecutionFlow.prepareTask | Block single-Task plan creation within selected full-plan Mission |
| OpenAiExecutionFlow.preflight/start and claim/model/budget stores | Retain explicit per-Run Start and fresh authority; bind admitted revision |

## Required evidence before claiming implementation complete

- Diamond DAG A to B/C to D: all Runs created atomically; only A starts eligible; ACCEPT A releases B/C; D waits until both B and C have exact accepted output.
- Stale full configuration, invalid snapshots, cross-scope authority, missing receipt provenance and existing runtime all fail without partial writes.
- Same-key and competing-key concurrency, database insertion rollback and races with edits/create/reaccept/revocation/pause/cancel.
- REWORK, UNKNOWN, legacy COMPLETED and cross-plan output never release dependent work. Admission replay after progression never resets runtime.
- Native PostgreSQL plus real-backend browser acceptance from configured plan acceptance through admission, explicit Starts and dependency release. Simulated provider evidence remains labeled.

Generalized full-graph Mission completion, automatic scheduling/enqueue, generated plans and prerequisite-output context assembly are subsequent work. Existing Mission completion remains bounded to one Task until separately expanded and verified.


## Activation and concrete API

Enable `agentos.mission-plan-review.enabled`, `agentos.mission-plan-acceptance.enabled` and `agentos.mission-graph-admission.enabled` together. All default false; missing required acceptance/review services fail admission startup. Plan review advertises the graph capability header and its existing screen hosts confirmation, state and Run links.

GET/POST `/api/v1/organizations/{org}/teams/{team}/missions/{mission}/graph-admission` require current scoped ADMIN. POST additionally requires session CSRF and exactly textual `idempotencyKey`, `expectedPlanRevision` and `expectedFingerprint`. Canonical UUID, positive Java-long revision and lowercase SHA-256 are required. Reads and command responses are no-store; stale/unsupported admission is a 409. Receipt mapping preserves original root/blocked statuses on replay, while GET exposes current runtime statuses separately.

Snapshot validation runs both before new plan acceptance and when consuming saved content. Runtime checks pin the selected admitted revision and compare current content, owners/revisions and edges to the full immutable source. Extra out-of-plan Tasks do not join execution; edits to admitted inputs or dependencies block Start/claim/authorization/handoff. Guards remain installed even when the admission flag is off; persisted admitted work fails closed instead of entering the legacy path.

New graph review decisions are denied while the feature flag is off, preserving the pending review until re-enabled. Historical receipt replay remains side-effect free. For an admitted graph, new ACCEPT must prove its own exact accepted/settled output inside the decision transaction; invalid own evidence rolls back the decision. A different prerequisite that has not accepted merely keeps dependents blocked. Pause permits dependency-only readiness metadata, and existing OPEN-control checks still block actual Start/claim/handoff. Cancel does not release dependents.

Dependency release increments Run revision from zero to one, clears all pending Task fields, saves immutable prerequisite evidence digest and audit, and never enqueues model work. Roots and released dependents retain at most one explicit rework through the current Run flow.
