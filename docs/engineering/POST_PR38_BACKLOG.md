# BA proposed delivery backlog after PR38

Date: 31 October 2026. Proposed by the coordinator acting in the BA role; not an independent BA/SA/QC approval. Source baseline: PR38 documentation closure `cef8d77b402f9fb9473c9458579a9a3cc06c6fb3`. No implementation, merge or deployment authorized by this document alone. Existing user authorization for ordinary implementation work is unaffected. Prior navigation/dependency recommendations are superseded by delivered PR36–38 work.

## Execution checkpoint

BA-01 independent source/evidence review completed on31 October2026 with no blocker identified for frozen PR38; see [independent report](../qc/TASK_DEPENDENCY_HISTORY_INDEPENDENT_REVIEW.md). Reviewer corroborated completed CI, ran the local production DTO/state harness, checked15 capture hashes/dimensions and visually inspected5 captures. No fresh full-runtime rerun or full-product acceptance is claimed.

BA-02 proposed lifecycle/authority contract and44 requirement cases are authored. Independent review identified and retested documentation issues; its final recheck found no remaining blocker in this bounded document review. Proposed runtime policy and per-slice API/schema/implementation freeze remain separate. See [contract](RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md), [independent findings/retest](RUNTIME_LIFECYCLE_AUTHORITY_REVIEW.md) and [acceptance matrix](../qc/RUNTIME_LIFECYCLE_ACCEPTANCE_MATRIX.md). BA-03 provider connection management is next; provider/model and credential/billing ownership remain genuine inputs, not an inferred selection. No runtime coding started in this documentation slice.

## Current boundary

Authentication/recovery/workspace, persisted Team/Agent/Mission/Task configuration, draft input/owner/dependency editing and histories, member/invitation/grant management, bounded approvals, navigation and recorded-activity Trace have delivery evidence in their individual contracts and QC reports. PR38 automated gates passed; subsequent independent review found no blocker in its bounded scope as recorded above. Draft PRs are not a merged or deployed product. Configuration ownership is not runtime assignment; saved status and activity are not execution telemetry. Existing activation review is preflight, not a working activation command.

PRODUCT_SPEC.md requires a complete plan → execute → approve → review → output/usage loop. OrchestratorService.requestMissionPlanning() is still a stub and MemoryService is empty. MISSION_STATE_MACHINE.md describes PENDING/ASSIGNED/REWORK while persisted task states include DRAFT and WAITING: reconcile semantics, compatibility and migration before enabling transitions. Do not rename persisted states silently or copy the older document as an implemented API contract.

## Prioritized work packages

These are delivery slices, not screen counts or calendar estimates. Each slice must be split into reviewable PRs when its contract is frozen. Dependencies identify prerequisites, not an instruction to implement all work at once.

| ID / priority | User outcome and UI | Primary owners | Depends on | Acceptance boundary |
| --- | --- | --- | --- | --- |
| BA-01 / P0 | Close PR38 independent source/spec/visual review and audit the stacked PR integration order | BA, QC, SA | Existing C4 evidence | Record actual independent findings/retests when reviewer capacity returns; preserve tested code identity; no automatic merge/deploy or fabricated approval |
| BA-02 / P0 | One authoritative runtime lifecycle and permission specification | BA, SA, BE, QC | Current code/spec inventory | Mission/Task/Run transition tables, actors, pending reasons/resolvers, terminal/retry/cancel semantics, versioning and existing-state compatibility; explain intake pause versus execution pause; resolve old state-machine mismatch |
| BA-03 / P0 | Models/provider connection settings: create, test, disable and rotate credentials | SA, BE, FE, QC | BA-02 core authority rules | One real provider adapter first; ADMIN-only secrets, masked responses, authenticated encryption via managed key source, safe rotation/no logs, bounded timeouts/rate limits, server-controlled endpoints and outbound request protections; connection test charges/limits visible |
| BA-04 / P0 | Agent effective runtime permissions, Skills and Tools registry/assignment | BA, SA, BE, FE, QC | BA-02; BA-03 for model checks | Versioned instructions/tool schemas, scoped grants and deny-by-default server enforcement; allowlisted initial tool; permission revocation affects pending work; no prompt-based authority or fabricated effective permissions |
| BA-05 / P0 | Team/Agent activation workflow with genuine eligibility and suspend/resume behavior | BA, BE, FE, QC | BA-03, BA-04; dispatch enforcement from BA-08 | Review actual prerequisites and explicit atomic activation command; block incomplete/revoked configurations; suspension prevents new claims; contract defines treatment of in-flight work; do not enable activation before enforcement exists |
| BA-06 / P0 | Mission planning and plan review: generate, inspect, edit/reject and accept task graph | BA, SA, BE, FE, QC | BA-02, BA-03; BA-04 for manager context | Model produces a proposal only; validate complete scoped DAG, owner eligibility, plan revision and stale acceptance; accepted plan/tasks/audit atomic; regeneration never silently overwrites existing tasks or receipts; provider failure has no partial plan commit |
| BA-07 / P0 | Durable execution ledger and minimum read-only Run detail | SA, BE, FE, QC | BA-02, BA-04 | Persist run/attempt identity, immutable input/config versions, events, outputs and idempotency; no network calls under business locks; reads require fresh scope/authority and clear stale UI; running status requires an actual attempt |
| BA-08 / P0 | Start mission and safely dispatch tasks to workers | SA, BE, FE, QC | BA-05, BA-06, BA-07 | Eligibility checked at claim and execution; atomic durable dispatch/outbox, leases/fencing, bounded concurrency and duplicate-delivery protection; completed prerequisites required; crash/restart and competing-worker PostgreSQL tests |
| BA-09 / P0 | Real model execution with bounded context and one harmless tool | BE, SA, QC; FE for run progress | BA-03, BA-04, BA-07, BA-08 | Persist actual model result/failure and usage; explicit tool authorization at invocation; timeouts, cancellations and uncertain outcomes handled without duplicate side effects; first demo tool must not mutate repositories or run arbitrary shell |
| BA-10 / P0 | Runtime approval request → decision → exactly controlled continuation | BA, BE, FE, QC | BA-04, BA-07–09 | Connect a real runtime action to a supported approval contract; immutable action/payload digest, scoped expiring grant and current authority; approved action revalidated and not executed twice; stale/rejected/expired decisions cannot resume work; existing PUBLISH_PREVIEW does not authorize arbitrary tools |
| BA-11 / P0 | Reviewer accepts/rejects output, requests rework and completes mission | BA, BE, FE, QC | BA-07–10 | Reviewer identity/authority and output version bound to decision; no Agent self-declared DONE; rejection/retry limits enforced; mission completion derived from accepted required outputs; review failure and rework remain traceable |
| BA-12 / P1 | Run/Task/Mission outputs and artifact browser | BE, FE, QC | BA-07, BA-09; BA-11 for accepted state | Real durable artifact metadata/object storage, tenant authorization per download, integrity and safe content handling; retries preserve attempt outputs; distinguish generated versus accepted versions |
| BA-13 / P0 for enforcement; P1 for full UI | Token/cost budgets, rate controls and real Team/Agent capacity statistics | SA, BE, FE, QC | BA-03, BA-07–09 | Pre-call budget reservation and bounded concurrency before live execution; settle against actual provider usage without double counting; label unknown cost explicitly; dashboard counts/latency/capacity derive from run ledger, with documented denominators/time windows |
| BA-14 / P1 | Execution Trace, pending drilldown and retry/pause/cancel controls | BA, BE, FE, QC | BA-02, BA-07–11 | Correlate actual runs/model/tool/approval events without exposing secrets or unrestricted prompts; show exact pending reason/resolver; retry creates a new attempt; pause/cancel behavior for queued/in-flight work follows the frozen lifecycle, with fresh permission checks |
| BA-15 / P1 | Scoped Memory, Knowledge attachments and context builder inspection | BA, SA, BE, FE, QC | BA-04, BA-07 | Explicit org/team/mission/agent scope and retention; versioned context provenance; no cross-tenant retrieval or arbitrary memory instructions as authority; selected approved inputs support the initial run before broader memory UI; vector search remains outside original v0.1 scope |
| BA-16 / P0 release gate | Integrated release hardening and reproducible local Docker demo | SA, BE, FE, QC | Selected end-to-end slices | Migration/backup/restore and rollback rehearsal, secret handling, multitenancy/CSRF/session and artifact checks, actual responsive accessibility review, load/worker-failure tests and observable limits; run on the user's Mac only through an available authorized environment; Sonar paused, not passed |

BA-13 budget/rate enforcement is required before any live provider execution, even though its full statistics UI is later. BA-07 must include minimal run visibility immediately; do not run work invisibly until the later Trace expansion. BA-05 and BA-08 can be co-delivered to satisfy the activation/enforcement dependency without exposing an unenforced intermediate activation state.

## First implementable sequence

1. BA-02: publish a frozen lifecycle/authority contract, compatibility mapping and testcase matrix. BA-01 independent review proceeds separately when available.
2. BA-03: one provider connection, actual bounded connection test and secure credential lifecycle. Provider/model choice, billing account and available credentials are genuine inputs; never infer them from the illustrative product demo.
3. BA-04 plus minimal BA-07/BA-13: one versioned Agent configuration, harmless allowlisted tool, durable run ledger, context snapshots and enforced limits.
4. BA-05/06/08/09: activate an eligible Agent, review a small plan and execute a single safe task through the real worker.
5. BA-10/11 with minimal BA-12: demonstrate approval, reviewer rejection once, rework, acceptance and actual artifact/usage readback. Complete broader management/observability surfaces afterward.

Initial successful vertical slice: authorized human configures one provider/Agent → creates a Mission → reviews a proposed plan → explicitly starts one eligible task → worker records actual execution/output/usage → authorized reviewer accepts. Approval and rework then extend the same persisted run flow; do not claim the complete product loop until those paths pass too.

## Shared definition of done

Each slice has a frozen BA contract, reviewed API/DTO/error and authority boundary, migration/rollback plan when applicable, responsive Figma flow review before implementation, real persisted UI/API behavior and independent QC findings/retests. Cover normal/empty/error/denied/stale/unknown outcomes where relevant, scope and role revocation, concurrency/idempotency/rollback and secret/privacy boundaries. Use actual PostgreSQL and suitable browser/restart tests with exact commit provenance; authored or collected tests are not passes. Maintain clean Google coding style and existing CI gates; Sonar stays paused. No production sample records or hardcoded success statistics.

Passwords and verification capabilities use one-way hashes; provider API keys need authenticated reversible encryption because the server must use them. Retain this distinction in requirements rather than imposing one-way hashing on every sensitive field.

## Inputs to resolve at the relevant contract

- First supported provider/model and environment/credential owner; do not require all demo providers for the first run.
- Initial harmless tool and supported artifact type, with explicit execution isolation and retention.
- Initial reviewer policy, retry/rework limits and pending-input handling; document defaults as proposals until frozen.
- Explicit budget ceilings, rate/concurrency limits, retention and expected load before calling the system scalable or production-ready.

No day estimates or completed Figma coverage are asserted: this review inspected repository documents/routes/source, not the current live Figma file. Count of work packages must not be presented as count of remaining screens.

Sources: PRODUCT_SPEC.md; MISSION_STATE_MACHINE.md; IMPLEMENTATION_STATUS.md; docs/qc/TASK_DEPENDENCY_HISTORY_DELIVERY_CHECKPOINT.md; docs/engineering/POST_PR35_DELIVERY_REVIEW.md (historical); frontend/src/app/app.routes.ts; backend/src/main/java/com/agentos/orchestration/application/OrchestratorService.java; backend/src/main/java/com/agentos/memory/application/MemoryService.java; backend/src/main/java/com/agentos/taskdependencies/TaskDependenciesGraph.java.
