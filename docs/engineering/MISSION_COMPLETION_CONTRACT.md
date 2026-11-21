# Mission completion contract — single-Task v1

Date: 21 November 2026 (Asia/Ho_Chi_Minh). PM/BA delivery contract for the next feature after PR #119. This document specifies behavior; implementation and test evidence are recorded separately. It does not claim real-provider acceptance, publication, merge or deployment.

## User outcome and scope

An organization ADMIN can open a Mission's completion assessment, see why it cannot close, submit an eligible Mission for final review, then explicitly confirm completion. Accepting a Task output does not automatically complete its Mission.

This first version applies only to a Mission with **exactly one current Task and one matching execution Run with PR #119 OpenAI execution provenance**. The Run must reference an accepted plan snapshot containing exactly that Task, its saved owner/configuration revisions and no dependency edges. The current Task likewise has no configured dependencies. Extra Tasks, extra Runs, missing runtime provenance, unsupported snapshots and legacy `COMPLETED` labels are blockers. Do not infer an optional Task, ignore a cancelled Task or select only a convenient subset of the Mission. The scope restriction remains visible in the UI.

The entry condition is an already RUNNING or WAITING_APPROVAL Mission whose sole Task has the accepted runtime evidence below. Creating a fresh Mission still starts at CREATED; planning and full Mission start/admission are not delivered by this feature. Test fixtures beginning with existing accepted execution records must not be described as a complete create-new-Mission → plan → start → complete acceptance run.

PR #119 creates single-Task accepted snapshots, not a reviewed Mission-wide DAG. Its plan revision can advance when another Task starts. Consequently, do not select `max(plan_revision)` and call it a complete Mission plan; verify the exact revision referenced by the sole Run. A future multi-Task contract must define required/optional membership and accepted complete-DAG provenance before widening this endpoint.

## Lifecycle and actions

| Current Mission | Explicit action | Result |
| --- | --- | --- |
| RUNNING or WAITING_APPROVAL | SUBMIT_REVIEW | REVIEW |
| REVIEW | COMPLETE | COMPLETED |
| COMPLETED | No new action | Terminal; historical command receipts remain replayable |
| CREATED, PLANNING, READY, FAILED, CANCELLED or unknown | None | Completion blocked |

Both actions require every readiness invariant below. There is no direct RUNNING → COMPLETED command, automatic closure after Task ACCEPT, provider call, Task state rewrite, budget settlement or reconciliation performed by completion. Entering REVIEW deliberately closes the existing Task create/intake gates. REVIEW retains the same accepted output; COMPLETE requires another explicit human confirmation.

This first version offers no Mission-level reopen, rejection, replan or rework command. Before SUBMIT_REVIEW and COMPLETE, the human inspects the accepted output text returned in the completion assessment. The existing Run link supplies execution context; its earlier review-only output endpoint is not assumed to remain readable after Task ACCEPT. Once REVIEW is committed, a new scope/output requires a separately specified lifecycle rather than reopening completed work.

## Readiness evidence

The server evaluates the complete current Mission scope, including all records under its sole Run. A valid saved Task status is insufficient.

1. Current enabled human account and credential version, organization ADMIN membership, exact organization/Team/Mission scope and eligible parent state are checked. An archived/ineligible Team blocks a new lifecycle mutation.
2. Mission has exactly one scoped Task and exactly one matching scoped Run. Task and Run are both COMPLETED, pending fields are absent, and the Run has a valid accepted snapshot. Unsupported or inconsistent provenance blocks the operation.
3. Snapshot owner, Task edit/owner/dependency revisions and scope match the current saved configuration. The referenced plan contains only the selected Task and no dependency edges; no current dependency edge is ignored.
4. The latest Attempt by attempt number is SUCCEEDED with its terminal outcome slot. The exact Attempt has SUCCEEDED reconciliation, a review submission, an ACCEPT decision and stored nonempty bounded output content. Reconciliation and content digests match and content integrity is verified. The ACCEPT source Run revision equals `current Run revision - 1`; its submission source revision is one less than the ACCEPT source revision. Earlier REWORK decisions do not authorize completion.
5. The accepted Attempt has one consistent PR #119 execution chain: a model authorization for the accepted owner and exact connection/credential/model, committed handoff, a FINISHED dispatch whose ID is the authorized action, its immutable prepared OpenAI request, a usage receipt on that same action/connection, and a SETTLED reservation on the same Attempt/fence. Stored request bytes must match the authorization/request digest. The settled cost must equal the conservative rounded-up reported-token charge using the stored rates and remain within the stored cap. Zero execution records, imported recorded-output Runs and reconciliation-only fixtures do not establish this provenance. This check uses immutable historical execution evidence, not a new provider call or current grant.
6. Every historical Attempt is terminal (SUCCEEDED, FAILED or CANCELLED). UNKNOWN, CREATED, RUNNING, waiting and CANCELLING Attempts block closure even when another row claims COMPLETED.
7. No live claim remains. Every reservation is SETTLED; HELD and UNKNOWN holds block. Every dispatch is FINISHED; QUEUED, CLAIMED and UNKNOWN dispatches block. Absence of required successful execution evidence cannot be repaired by zero-row counts.
8. The exact Mission execution control exists with a valid revision. OPEN or quiescent PAUSE_REQUESTED can close; CANCEL_REQUESTED blocks. Completion never acknowledges cancellation or treats an expired lease as proof that remote effects did not occur.

Current model availability/grants are not newly required merely to accept already verified output: completion sends no model action. Revocation still prevents future execution through existing runtime controls.

Blockers must distinguish unsupported single-Task scope, missing accepted evidence, pending execution/accounting, cancellation, changed configuration, parent lifecycle and already completed state. Safe stable reason codes and concise user text replace SQL details, credential metadata, arbitrary internal exceptions or inferred resolver identities.

## Read and command contract

The endpoint is `/api/v1/organizations/{organizationId}/teams/{teamId}/missions/{missionId}/completion`. The backend is opt-in with `agentos.mission-completion.enabled=true`; default is disabled. A missing endpoint is feature unavailable, not successful assessment or a reason to infer readiness locally.

GET is side-effect free and returns current Mission identity/status, control revision, eligible action, the supported sole Task/Run/Attempt/output identity, `outputText`, safe blocker codes and a SHA-256 fingerprint of a canonical current snapshot. `outputText` is nullable: expose only stored accepted text whose UTF-8 size and SHA-256 match the exact accepted evidence. It is bounded at 256 KiB and rendered as escaped text. The endpoint does not load or append provider credentials. The client independently verifies its SHA-256 before displaying confirmation. Missing or invalid accepted output never becomes an eligible action. This scoped read remains useful after Task ACCEPT and after Mission completion; do not reuse the earlier endpoint that requires an active Task review. Use decimal strings for revisions and established UUID/time wire conventions. Responses are no-store.

POST accepts exactly three textual fields, rejecting duplicate or unknown names, numeric/ordinal actions, noncanonical UUID shapes and trailing JSON:

```json
{
  "action": "SUBMIT_REVIEW",
  "expectedFingerprint": "<64 lowercase hexadecimal characters>",
  "idempotencyKey": "<UUID>"
}
```

`action` is SUBMIT_REVIEW or COMPLETE. The fingerprint binds action, full scope, source Mission status, control revision, current Task configuration/status, accepted snapshot identity, Run revision/status and exact accepted output evidence. It is a concurrency assertion, never authority. Re-read eligibility and compare the current fingerprint within the command transaction; a stale/missing fingerprint conflicts with no partial state change.

Serialize with the existing organization-first protocol, then account/membership, parent scope/control and affected execution rows. Revalidate after lock acquisition. Task creation, Start, review, control commands and reconciliation must share the same organization serialization boundary; do not introduce a reverse lock order. A Task inserted first makes the assessment unsupported; a REVIEW committed first makes later Task creation ineligible.

Each action atomically changes only Mission status, CAS-increments the current control revision, records a safe audit event and stores an immutable command receipt containing actor/key, action, scope, request fingerprint and resulting historical snapshot. Any failure rolls back all changes. Missing/exhausted revision or inconsistent projections deny the operation. Use new durable completion records if the current schema lacks a suitable receipt store.

A matching actor/key/action/scope/fingerprint replays its original receipt after fresh account and ADMIN authorization. Replay does not require the old state still to be current and must never revert the Mission (for example, replaying SUBMIT_REVIEW after COMPLETE). Reusing the key with a different request conflicts. Another actor cannot recover that receipt. A new key cannot bypass terminal state or stale evidence.

Use current ADMIN session/CSRF enforcement for POST. Malformed commands return bounded safe validation errors; unauthenticated/unauthorized, missing scope and stale/ineligible requests use the existing API conventions. Do not convert denied reads into apparent readiness. The UI preserves an exact pending command for an explicit retry after an ambiguous response, never automatically sends either lifecycle action, and clears private state on identity/route changes.

## UI acceptance

Mission workspace links to the completion assessment. The panel shows current Mission state, supported scope, accepted Task/Run identity, the bounded, escaped accepted output text, a contextual Run link and all safe blockers. It offers SUBMIT_REVIEW only when allowed, then reloads the current assessment before offering COMPLETE. Each mutation has a clear confirmation identifying Mission and target state. A successful historical receipt and current Mission status are presented separately. MEMBER/anonymous access and disabled actions must not leak private assessment data.

A conflict clears the stale confirmation and requires a fresh assessment. An ambiguous response retains only the exact command for explicit retry and offers current-state refresh. The UI must not claim completion from a click, a Task count, a model response or an unvalidated acknowledgement.

## Required Done evidence

- Positive single-Task flow: ACCEPTed output → assessment → SUBMIT_REVIEW → persisted REVIEW → COMPLETE → persisted COMPLETED; Task/Run/evidence unchanged.
- Deny zero/multiple Tasks, extra/missing Runs, legacy completed labels, wrong accepted plan/configuration/owner/dependency revisions, current/snapshot dependencies, stale Run revision and mismatched scope.
- Deny missing PR119 dispatch/request/handoff/usage/settlement provenance, changed request bytes, mismatched usage charge/cap, missing/latest non-success Attempt, missing submission/ACCEPT/content, mismatched/tampered output digest, historical REWORK selected as acceptance, any historical unresolved Attempt, live claim, HELD/UNKNOWN reservation or unfinished dispatch.
- Deny cancellation, archived/ineligible Team, wrong Mission state, current account/member/credential revocation and cross-organization identifiers.
- Same-key replay and conflict, receipt replay after later COMPLETE, concurrent duplicate commands and Task creation, stale control/fingerprint rejection, injected failure rollback and no state resurrection.
- HTTP authentication/ADMIN/CSRF and strict wire tests; responsive UI confirmation, blocker, conflict, ambiguous retry, authority loss and navigation tests. Distinguish mocked browser coverage from real-backend acceptance.
- Native PostgreSQL transaction/race evidence and matching-head CI before claiming the feature validated. No provider call is needed for completion tests; synthetic PR119 execution/output fixtures are explicitly identified and must contain the complete joined provenance chain. Passing fixture-based tests does not establish a real OpenAI call.
