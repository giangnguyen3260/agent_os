# Configured Mission plan acceptance contract

Date: 21 November 2026 (Asia/Ho_Chi_Minh). PM/BA contract after read-only Mission plan review. This is explicit human acceptance of the full current configured graph, not Agent-generated planning or execution admission. Implementation, matching test/CI evidence, publication, merge and deployment are separate statuses.

## User outcome and boundary

An organization ADMIN reviews all configured Tasks and their complete dependency graph, explicitly accepts that exact current configuration, and can inspect the immutable accepted version afterward. First acceptance moves Mission CREATED → READY. Editing the draft configuration later makes the accepted version stale; the ADMIN reviews and accepts a new immutable version while the Mission remains READY.

Acceptance authorizes no provider or tool action. It does not admit Tasks, make a Task READY, create Runs/Attempts/claims/dispatch, initialize execution controls, reserve budget/capacity or start workers. `executionAuthorized` remains exactly false in every acceptance/current-plan response and in the stored review snapshot. No model response can accept a plan or write Mission state.

The input is the existing [complete configuration review](MISSION_PLAN_REVIEW_CONTRACT.md): 1–200 Tasks, at most 1,000 edges, per-Task description/acceptance limits 8,000/4,000 UTF-16 code units and aggregate original input text at most 1 MiB. All Tasks must be DRAFT with current ACTIVE owners in the same organization/Team; required content/priority/revisions must be valid and the full graph acyclic with valid scoped endpoints. Preserve every Task and edge; no optional/required subset inference is introduced.

The review-only eligibility rule is deliberately extended from CREATED to **CREATED or READY** for this feature. Update backend and frontend checks consistently; never ignore an incompatible review blocker merely to force acceptance. Other existing Task-create/edit/intake rules remain in force.

## Eligibility and lifecycle

| Mission/current acceptance | New command behavior |
| --- | --- |
| CREATED or READY without any prior plan, valid complete configuration, no execution records | Accept revision 1 and move to READY |
| READY, current accepted version matches unchanged configuration | No new revision; use the original matching command receipt for replay |
| READY, valid changed configuration, prior accepted version stale | Accept the next revision, preserve all prior versions, remain READY |
| Any Mission with an existing Run or execution control | Block new acceptance/reacceptance |
| PLANNING, RUNNING, WAITING_APPROVAL, REVIEW, COMPLETED, FAILED, CANCELLED or unknown | Block new acceptance/reacceptance |
| Matching historical actor/key/body | Return that original receipt after fresh authorization; never restore its old state |

Current organization and Team must be ACTIVE for a new command. A current enabled credential-matching human account and organization ADMIN membership are required for every read, new acceptance and historical receipt replay. All identifiers resolve within the exact organization/Team/Mission scope.

The absence of Runs and execution controls is checked under the same organization serialization lock as the acceptance write. A graph fingerprint is configuration identity, not proof that execution never began. This version never overwrites a runtime-owned plan, attempts to repair a partially admitted Mission or adopts legacy execution records.

Prior V036 accepted-plan rows must belong to a valid receipt-backed version sequence from this acceptance flow. Unreceipted, inconsistent or unsupported legacy accepted plans are unavailable; do not silently adopt them by selecting `max(plan_revision)` and continuing. Current-plan selection must agree with the latest internally consistent version and the V036 revision records. A separately reviewed adoption/migration contract would be needed to widen this behavior.

## Command and transaction

Expose a scoped acceptance resource with GET current accepted plan and POST explicit acceptance; backend opt-in is `agentos.mission-plan-acceptance.enabled=true`, disabled by default. The configuration-review capability must also be installed/enabled. The concrete route follows the scoped Mission API convention and must be shared by backend, frontend and wire tests. No legacy generic Mission state-update API is reopened.

POST accepts exactly two textual fields:

```json
{
  "idempotencyKey": "<canonical UUID shape>",
  "expectedFingerprint": "<64 lowercase hexadecimal characters>"
}
```

Require the current ADMIN session and CSRF. Reject duplicate/unknown fields, coercions, null/missing data, malformed UUID/fingerprint and trailing JSON with bounded safe errors. The command's canonical body/scope/actor is immutable once a send may have occurred.

Use an organization-first write transaction consistent with existing Task/owner/dependency/intake and runtime writers. Freshly validate account/membership, resolve the scope and complete review snapshot, verify supported parent lifecycle and no Runs/controls, then compare the supplied fingerprint to the exact current review. Any stale configuration, changed content/owner/edge/revision, lost authority, invalid graph, unsupported scope or write failure rejects the entire command with no partial acceptance.

Atomically:

1. Allocate the next positive, non-exhausted plan revision under the organization lock, after verifying prior accepted-version provenance.
2. Insert V036 `accepted_mission_plans`, all `accepted_mission_plan_tasks` with exact owner/edit/owner/dependency revisions, and every dependency edge of the complete reviewed snapshot.
3. Persist a new versioned acceptance receipt with the full immutable source review snapshot JSON, its exact stored UTF-8 SHA-256, source fingerprint, actor/key/scope, accepted revision and resulting READY configuration fingerprint. A safe audit event belongs to the same transaction.
4. Change CREATED → READY, or retain READY for legitimate changed-configuration reacceptance. Recompute the READY fingerprint from the exact post-transition current configuration while retaining the original source snapshot unchanged.

Do not update Task status/content/owner/dependency rows. Do not insert or update any Run, Attempt, claim, budget/capacity hold, execution control, outbox/dispatch or provider/tool state. A failure in graph insertion, snapshot/receipt persistence, status change or audit must roll back the entire acceptance, including the allocated revision.

## Two fingerprints and immutability

The **source fingerprint** binds what the human actually reviewed and confirmed. It includes the Mission's pre-command lifecycle, so a CREATED source fingerprint legitimately differs from the post-command READY fingerprint even when Tasks and edges are unchanged.

The **READY fingerprint** binds the resulting READY configuration and is used for current-version freshness/unchanged detection. Compare a new READY review against the stored READY fingerprint; comparing it only to the old CREATED source fingerprint would incorrectly create revisions for unchanged configuration.

The source JSON is immutable historical evidence. Never rebuild its text, owners, graph or revisions by joining current mutable Tasks/Agents. Versioned schema identifiers must be recognized before decode/display; verify the exact stored JSON SHA-256, bounded size and strict internal scope/field/graph consistency. A bad digest, unknown schema version, malformed snapshot or mismatch against its V036 membership/owner/revision/edge records makes that accepted evidence unavailable. Do not substitute current data or quietly repair history.

Source snapshot content stays bound to all graph/input limits. Stored bytes are bounded by the known serialized snapshot schema and these content limits; reject excessive/corrupt data before decoding. Do not log the snapshot or arbitrary Task input in exceptions/audit payloads.

## Reads, stale plans and receipt replay

GET returns the latest valid accepted revision and its immutable source snapshot, or an explicit no-accepted-plan result. It also reports whether that version matches the current configured READY graph. Current configuration and historical accepted content must be labeled separately. `matchesCurrent` is a freshness comparison, not a model/worker permission and not proof that execution is admitted.

Changing any Task membership/content/priority/status/revision, owner eligibility, graph edge or relevant parent configuration must make the accepted version stale or unavailable. Human-readable labels cannot conceal a missing accepted snapshot. Mission READY by itself does not mean its currently edited graph is accepted.

The existing DRAFT Task create/edit/owner/dependency commands permit READY Missions under their own intake/current-authority gates. Keep those gates unchanged. Edits do not overwrite historical accepted rows or auto-accept a new version. Reacceptance remains an explicit separate command. PLANNING editor rules are not widened, and no generated proposal/regeneration command is added.

A matching actor/key/scope/body replay returns the original accepted revision/snapshot even after edits, later acceptance or later lifecycle progression, provided current authority still permits historical access. Reusing that key with changed scope/body conflicts. Another actor cannot recover it through the original actor's key. A new key against an unchanged already accepted READY configuration cannot create a duplicate revision; the UI must inspect the current accepted version instead. Replay never reverts Mission state, overwrites the current accepted version or reintroduces a removed Task.

GET and POST responses are no-store. Safe bounded not-found/denied/conflict/unavailable errors distinguish authorization and stale configuration without exposing SQL, credential material or arbitrary stored snapshot errors.

## UI behavior

The configuration-review page offers an explicit confirmation to accept the exact complete displayed graph when eligible. It identifies Mission and snapshot fingerprint, and states that acceptance does not start execution. A successful receipt is presented as historical command evidence; reload current configuration/accepted-plan state before offering another action.

Show accepted revision, immutable accepted source content and a clear current/stale indication. Preserve both the original CREATED source snapshot and resulting READY freshness semantics. Do not label a stale version current merely because the Mission status is READY.

Validate returned scope, version/revision/fingerprint, complete bounded graph and `executionAuthorized: false`. Render source text escaped. Clear private accepted/current content and confirmation on identity/route changes, current authorization denial, corrupt/malformed responses or stale conflicts. Fence async responses against generation/actor changes and use bounded request timeouts.

After an ambiguous send, retain the exact command key/fingerprint for explicit retry and offer a fresh current-state read. Do not silently replace the key/body, generate a new acceptance, or automatically resend. A known stale conflict requires fresh review and confirmation. No Start/Run-manager/provider/tool action appears as an effect of acceptance.

## Required Done evidence

- Complete multi-Task configured DAG acceptance inserts exactly its plan/membership/edge rows, receipt/source snapshot and audit; Mission becomes READY and Tasks remain DRAFT. Assert no Run/control/claim/budget/dispatch/provider/tool side effects.
- Source CREATED fingerprint and post-transition READY fingerprint are distinct but unchanged READY configuration is current. A new key cannot create an unchanged duplicate revision.
- READY Task content/priority/owner/dependency/membership edits preserve old accepted JSON, mark it stale, and permit one explicit new immutable acceptance; current and historical revisions remain distinguishable.
- Same-key exact historical replay after edits/later revision, body/scope/key conflict, two actors, revoked/credential-changed authority and foreign scope behave correctly without state resurrection.
- Missing/legacy receipt provenance, unknown snapshot schema, bad stored JSON digest, malformed/oversize snapshot and V036 mismatch never become current valid accepted evidence.
- Deny unsupported lifecycle, inactive parents/owners, non-DRAFT Task, invalid/missing content/priority, empty/excessive scope/input, missing/cyclic graph and any existing Run or execution control.
- Concurrent duplicate and different-key acceptance, Task create/edit/owner/dependency changes, and race with any future execution-start boundary preserve one coherent accepted snapshot. Inject failures after partial graph/status/receipt writes to prove atomic rollback and no consumed revision.
- HTTP current ADMIN/session/CSRF, strict wire/no-store and supported-method tests; responsive browser complete/stale/reaccept/replay, escaped input, malformed response, denied preflight, ambiguous exact retry and navigation/identity fencing.
- Java/build/lint/source review and matching-head CI; explicit isolated PostgreSQL tests and real-backend browser acceptance are separate evidence from H2/mocked tests. No real provider call is required to validate acceptance, and none may be claimed from this feature.

## Subsequent work

This feature does not create an AI proposal, enter PLANNING, regenerate Tasks, accept an Agent's unsupported output, start a Mission or release dependencies. Later contracts must bind a complete accepted plan to explicit atomic Mission start/admission, fresh model/Skill/Tool authority, budget/capacity, durable dispatch and fenced workers. Required/optional Task semantics, generated-proposal correction and generalized multi-Task Mission completion remain separately specified work.


## Implemented API and activation

Enable both `agentos.mission-plan-review.enabled=true` and `agentos.mission-plan-acceptance.enabled=true`; both default off. Enabling acceptance without review fails startup because its required review service is absent.

Under `/api/v1/organizations/{org}/teams/{team}/missions/{mission}`:

- GET `/plan-review` includes `X-AgentOS-Plan-Acceptance: true` only for enabled acceptance; this controls UI discovery, never authority.
- POST `/plan-review/accept` accepts exactly textual `idempotencyKey` (canonical UUID) and `expectedFingerprint` (lowercase SHA-256). Unknown/duplicate fields and trailing JSON are rejected. Session/ADMIN/CSRF checks remain required.
- GET `/accepted-plan` returns the selected revision and original stored review, plus current configuration match. It does not rebuild old content from current Tasks.

Responses use `Cache-Control: no-store`; conflicts return 409. Receipt/current responses use string plan revisions and `executionAuthorized:false`.

Sequence integrity uses bounded aggregate counts: V036 revision count and receipt count must both equal maximum revision, selection must point to that maximum, and the selected snapshot schema/SHA/membership/edges must agree. Historical replay validates the requested old version's snapshot independently. Prior content is not all fetched on every read. New READY Missions without any accepted plan are eligible for first acceptance only after the same full validation and no-runtime checks.
