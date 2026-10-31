# Independent BA-02 lifecycle/authority documentation review

Date: 31 October 2026. Disposition: **changes required before freezing BA-02 as an authoritative implementation contract**. This is a documentation review only, not runtime implementation approval. No runtime tests were executed or claimed. No other agents were spawned. Only this review file was written.

## Scope and evidence

Reviewed the proposed `RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md` against the current working-tree V004/V009/V018/V020/V021 migrations, `MissionStatus`, `AgentStatus`, `AgentActivationService`, `TaskDependenciesGraph`, and the existing Task Create/Draft Edit/Owner/Dependencies, Mission Intake, Agent Activation Review/Permissions, Approval Detail/Grant Management contracts. Also compared `PRODUCT_SPEC.md`, `MISSION_STATE_MACHINE.md` and `POST_PR38_BACKLOG.md`. References below are repository-relative; proposed-contract section references identify the reviewed statements. Existing uncommitted documents were treated as proposals, not proof of prior product approval. The baseline hash in the proposal was not independently verified against a remote PR.

The persisted Task vocabulary and V004 pending-field invariant are correctly retained. V009 composite dependency scope is respected; V020 ownership and V021 dependencies remain configuration provenance. Existing intake pause is correctly distinguished from execution pause. The proposal correctly preserves ADMIN/MEMBER explicit-grant approval eligibility and does not expand PUBLISH_PREVIEW to arbitrary runtime actions. `AgentActivationService.activate` still always throws `Blocked` after current-revision validation; configuration checks do not provide a positive activation path. Nothing in this review authorizes changing that boundary.

## Blocking findings and exact recommended fixes

### B1 — Run and Attempt states cannot represent the Task transitions promised by the document

**Evidence:** Proposed §§2–3. Run CREATED has only RUNNING as an exit, although admission can produce Task BLOCKED and queued work can be cancelled. Run RUNNING has no BLOCKED transition, although OUTCOME_UNKNOWN and revoked authority need a safe blocked representation. External-input Task WAITING has no corresponding Run wait state: WAITING_APPROVAL cannot truthfully represent INPUT_REQUIRED. Task BLOCKED → READY contradicts Run BLOCKED → RUNNING, bypassing the READY claim boundary. FAILED Tasks/Runs are retryable, yet cancellation is allowed only from “Nonterminal” without defining whether FAILED qualifies. Attempts have a vocabulary but no transition or reconciliation relation.

**Impact:** The same command can require an illegal Run transition, mislabel an input wait as approval, or resume without a claim. Deferring exact guards to BA-07 does not resolve these BA-02 semantic contradictions.

**Fix:** Replace the Run paragraph with a Task/Run/Attempt mapping table covering admission, queued blocking, claim, typed wait, unknown outcome, review, rework, retry and cancellation. Specify a pre-claim blocked Run state/path; typed waits (or a general WAITING state with reason); BLOCKED → CREATED/queued before a fresh claim; and cancellation from every unfinished Run, explicitly including retryable FAILED and CREATED. Define Attempt terminality, UNKNOWN resolution and whether a safe wait retains or closes the attempt. State explicitly that Task state, Run state and associated pending fields change atomically. DTO names and detailed wire guards may remain deferred.

### B2 — Acceptance/admission leaves a window for admitted blocked drafts to be edited

**Evidence:** Proposed §1 says existing editor gates remain frozen; §2 forbids configuration writes “while running,” invalidates start after configuration changes, and admits DRAFT tasks; §3 allows DRAFT → BLOCKED on admission/start. Existing Task editor contracts permit DRAFT edits in Mission READY/RUNNING/WAITING_APPROVAL, while rejecting PLANNING. V020/V021 revisions record configuration only.

**Impact:** If acceptance leaves a dependent task DRAFT while its root begins execution, existing editors can change the dependent task's owner/input/edges after Mission start. “Invalidates start” is no longer sufficient. Conversely, inventing an accepted-plan editor gate would change a frozen contract. The timing of admission versus acceptance versus start is not specified.

**Fix:** Specify that acceptance records an immutable snapshot without making tasks executable. At explicit Mission start, atomically validate and admit the complete executable membership, transitioning every admitted DRAFT to READY or BLOCKED before any dispatch becomes visible. BLOCKED tasks must already be non-DRAFT. Any pre-start configuration change rejects the whole stale start with no partial admission. If a different approach is intended, explicitly identify and separately contract the required editor-gate change. State how planning-time corrections occur given the existing PLANNING editor prohibition; do not silently widen existing gates.

### B3 — Atomic validation does not specify compatibility with the existing graph serialization protocol

**Evidence:** Proposed §§2,6 require atomic snapshots and fresh checks but do not require the established mutex/lock order. `TASK_DEPENDENCIES_CONTRACT.md` §6 explicitly requires every future task/status/edge/graph-metadata writer to take `organizations(id) FOR UPDATE` before graph reads, with parent and canonically ordered Task locks and fresh post-wait authorization. Runtime Task status/timestamp changes affect the existing graph fingerprints even when edges do not change.

**Impact:** A runtime writer using only Run/Task CAS can race existing dependency/create/configuration writers, invalidate the acceptance snapshot after checking it, or introduce incompatible lock ordering. Fingerprints and “transactional application service” alone do not serialize these operations.

**Fix:** Add a normative compatibility paragraph: all runtime writers of graph-bound Task metadata, plan acceptance/admission and configuration validation join the existing organization-first mutex protocol; acquire parent and Task locks in the established deterministic order; re-read scope, revisions and authority after waits; and never acquire organization locks after Run/claim/Task locks. Multi-account/Agent/resource locking must be frozen compatibly in the affected slice. A more concurrent protocol requires a separately reviewed replacement covering all participating existing writers, not a runtime-only exception. Keep network calls outside these transactions.

### B4 — Fencing and revoked-authority checks can prevent accounting for an already executed action

**Evidence:** Proposed §2 says stale workers cannot publish state/output or settle resources; §6 rechecks grants/credential version “before accepting fenced results.” §§4,6 simultaneously require reconciliation of in-flight actions and conservative reservation retention after revocation/lease loss.

**Impact:** If a provider completes after its worker loses the lease or grant, denying the report is safe for progression but insufficient for recording actual usage/effects. Without a distinct reconciliation authority, reservations and UNKNOWN attempts can remain unresolved indefinitely, preventing cancellation and enabling duplicate work if later treated as failure.

**Fix:** Distinguish untrusted observations from authoritative settlement. A stale/revoked worker cannot advance lifecycle or authorize another effect; a trusted reconciliation service may verify immutable action/provider locators and ingest evidence, record usage exactly once, reconcile reservations and close UNKNOWN/in-flight work under its own narrow service authority. Current execution grants remain mandatory for any new outbound action. Freeze idempotent settlement identity and prohibit reconciliation from implicitly retrying or accepting output. Specify that revocation does not erase evidence or the duty to account for already incurred effects.

### B5 — A valid existing graph may contain permanently unsatisfiable prerequisites for v1 admission

**Evidence:** Proposed §§1–3 admit only DRAFT tasks, reject runtime use of legacy non-DRAFT rows, and require accepted completion from a compatible plan/run. `TASK_DEPENDENCIES_CONTRACT.md` §1 deliberately permits targets in all nine persisted statuses; V009 enforces scope and non-self edges, not runtime provenance. `TaskDependenciesGraph` validates the complete saved graph rather than assigning execution semantics.

**Impact:** A DRAFT task depending on a legacy COMPLETED/FAILED/CANCELLED task can pass configuration DAG validation but cannot obtain the required runtime completion. It must neither execute from the old label nor appear as an ordinarily resolvable dependency wait. The proposal does not define whether such a plan is rejectable or admissible but permanently blocked.

**Fix:** Define runtime-plan eligibility separately from saved-graph validity: every required dependency must be in the admitted DRAFT execution closure or carry explicitly supported compatible runtime completion provenance. Until an adoption/import contract exists, reject acceptance/start for unsupported legacy prerequisites with a safe unsupported-prerequisite conflict and no writes. Explain that manual DRAFT configuration correction follows existing gates, with no inferred completion, dropped edge or synthesized Run. If intentionally permitting permanent blockers instead, specify an explicit human abandonment route and make the unsupported condition visible before acceptance.

### B6 — Mission WAITING_APPROVAL can remain stale when independent work becomes executable

**Evidence:** Proposed §4 enters WAITING_APPROVAL when no independent task can proceed but exits only “after relevant waits resolve and executable work exists.” Concurrent blocker resolution, scheduled eligibility or reconciliation can make an independent task runnable without resolving an approval. The proposal correctly says one waiting task must not label concurrent work paused, but gives no exit for this case.

**Impact:** Mission state can incorrectly continue to announce approval-only waiting or become an accidental dispatch gate while independent work is ready.

**Fix:** Define the aggregate predicate and reevaluation events. WAITING_APPROVAL → RUNNING whenever independent executable or active work exists, regardless of whether other approvals remain pending. Reevaluate atomically on relevant task/claim/blocker/control/approval changes. Specify how mixed approval/input/resource waits are displayed when no work can proceed, without introducing a persisted Mission status absent from `MissionStatus`; use typed runtime blockers/control metadata. Mission status must not itself grant a claim or bypass pause.

### B7 — Proposed reviewer/control policy is presented as settled; escalation has no authority contract

**Evidence:** Proposed §5 mandates ADMIN-only review, retry/input and lifecycle controls and human ADMIN output acceptance “in initial runtime version.” `POST_PR38_BACKLOG.md` explicitly leaves initial reviewer policy, retry/rework limits and input handling as proposals until frozen. `MISSION_STATE_MACHINE.md` describes Manager → human escalation on exhausted limits; the proposal calls that document historical but replaces no escalation responsibility. Existing human PUBLISH_PREVIEW grants and Agent requested permissions cannot supply the missing authority.

**Impact:** A reasonable conservative default becomes unsupported product policy. Rejection, exhausted retry/planning limits, missing input and unresolved UNKNOWN outcomes have reasons but no typed escalation recipient/capability or rule for what the Manager may propose versus what a human may decide.

**Fix:** Label the new ADMIN-only runtime/reviewer/control rules as proposed v1 policy, explicitly pending product freeze; retain current endpoint authority unchanged. Add a typed escalation contract requirement: triggering reason/limit, exact scope and Run/attempt/output reference, responsible capability, durable notification/work-item identity, allowed human resolution commands, and no automatic permission increase, retry-budget reset or new side effect. A Manager may recommend replanning/resolution but cannot grant authority, accept its own output or override ceilings. If no eligible resolver exists, remain blocked with a visible unresolved escalation; do not fabricate a recipient or silently fail/retry. Preserve the already accepted PUBLISH_PREVIEW grant-management/decision policy, including its distinct self-issuance/self-decision restrictions.

## Nonblocking clarifications

1. Proposed §4 refers to “Team/Agent suspension,” but the stated Team vocabulary is DRAFT/ACTIVE/ARCHIVED. Say “Team becoming ineligible (including ARCHIVED), Agent suspension, or grant revocation”; specify runtime eligibility explicitly without adding Team SUSPENDED.
2. Proposed §2 “one command receipt per scoped actor/key/body” should say one receipt per defined actor/scope/key namespace, with a canonical body digest: matching body replays, different body conflicts. Existing V009, V018, V020 and V021 use different scoped keys; preserve them. A body must not create an additional receipt slot under the same key.
3. State explicitly that BA-03 connection management cannot enable Agent activation. The activation path stays blocked until its authoritative model/tool/runtime integrations and separate positive-activation contract exist; a connection test or stored key is not that approval.

## Acceptance boundary

The fixes above are documentation prerequisites, not requests to implement runtime in this slice. BA-02 can be reconsidered once the state/admission/serialization/reconciliation/escalation rules are internally consistent and proposed product decisions are clearly identified. Subsequent slices still need their own frozen schemas, guards, authority and implementation evidence. This review establishes no runtime test result, PR acceptance, merge, deployment or production readiness.


## Documentation retest — revised shared contract, 31 October 2026

**Disposition: changes still required before BA-02 freeze.** This retest preserves all original findings above. It independently re-read the current contract and the new `docs/qc/RUNTIME_LIFECYCLE_ACCEPTANCE_MATRIX.md` as requirements only. No code tests, implementation acceptance, or runtime behavior are asserted. No other agents were spawned; only this review was appended.

Reviewed source identities (SHA-256):

- Contract: `cb2d7e570a675362d4f2d7cad19a162ce1380cfcf081f3550f92872006b93f50`.
- Acceptance matrix: `ebd01dc4247a02f8ca1afb4e114133f22ad87aea0559d64343870878ffccecdb`.

### Original finding retest

| Original item | Documentation result |
| --- | --- |
| B1 state/attempt mapping | Substantially improved; remains open for R1–R3 below. Typed input waits, queued blocking and retryable-FAILED cancellation are now expressly represented. |
| B2 complete admission | Core editable-DRAFT window addressed: acceptance is separate, all admitted DRAFT tasks leave DRAFT in one start, stale start rejects entirely. R4 remains on exact atomic boundary and matrix consistency. |
| B3 graph serialization | Addressed at BA-02 requirements level: organization-first mutex, established lock order and fresh post-wait reads are explicit. Detailed compatible resource locking remains a later-slice freeze requirement, not proof of implementation. |
| B4 trusted reconciliation | Addressed at authority/accounting level: narrow independent service may ingest late observations and settle once without new effects or acceptance. R1–R3 concern lifecycle recovery, not restoration of worker settlement authority. |
| B5 legacy prerequisites | Addressed: unsupported prerequisites reject acceptance/start with no writes; saved-DAG validity is explicitly distinct from runtime eligibility. |
| B6 aggregate reevaluation | Largely addressed; independent-work exit and mixed-blocker metadata are explicit. See clarification below for the residual no-executable-work exit. |
| B7 policy/escalation | Addressed at BA-02 requirements level: new policy is labelled proposed and typed escalation binds scope/capability/allowed resolution without privilege inflation. This is not product-policy approval. |
| Clarifications 1–3 | Addressed: Team ineligibility vocabulary, scoped-key/body-digest receipt uniqueness and continued activation block are explicit. |

### R1 — Lease loss has no complete recovery rule, particularly for a retained waiting attempt (blocking)

**Evidence:** Contract §2 says one valid claim/attempt per task, no claim while blocked, a fresh claim creates a new attempt from READY/CREATED, and a safe continuation needs a valid current claim while retaining the waiting attempt. §6 correctly says lease expiry alone does not prove retry safety. There is no explicit lease-loss transition or rule to recover a WAITING_APPROVAL/WAITING_INPUT attempt whose bounded lease expires while a human is deciding. A RUNNING attempt can likewise remain active after losing its only valid claim; obtaining another claim is not defined separately from starting another attempt.

**Fix:** Add a normative lease-loss branch. Revoke/fence the expired claim atomically. For a possibly in-flight action, block the Run/Task with OUTCOME_UNKNOWN, mark the unresolved attempt UNKNOWN and prohibit replacement outbound execution until trusted reconciliation. For a proven quiescent safe wait, explicitly choose either (a) a new fenced continuation claim bound to the same retained attempt and exact continuation, with no new action/attempt, or (b) close the old attempt through a legal terminal transition and require explicit bounded retry. Distinguish renewal/reacquisition from the fresh-attempt claim row. Do not let a new lease silently supersede a live/UNKNOWN attempt. State how a reconciled SUCCEEDED attempt on a blocked Run is dispositioned by an authorized command: review the verified exact output without another provider call, or explicitly discard/retry under policy; reconciliation itself still cannot accept or progress output.

### R2 — The two transition tables still disagree (blocking)

**Evidence:** §2 now requires RUNNING Task/Run → BLOCKED on ambiguous call/authority loss, but §3 has no RUNNING → BLOCKED row. §2's rejected/ineligible wait row permits directly closing a quiescent attempt as FAILED/CANCELLED, while the attempt transition relation allows typed wait → RUNNING/FAILED/CANCELLING, not direct CANCELLED. §2 says BLOCKED → CREATED cannot resume without a claim, whereas §3's blocker-resolution guard excludes live/UNKNOWN attempts but does not explicitly require the prior retained waiting attempt to be terminal.

**Fix:** Add RUNNING → BLOCKED to §3 with exact ambiguity/authority/lease-loss guards and atomic V004 pending-field population. Route waiting cancellation through CANCELLING → CANCELLED, or explicitly add a guarded quiescent typed-wait → CANCELLED edge consistently. Require all prior attempts to be terminal and effects accounted for before BLOCKED → READY creates eligibility for a new attempt; same-attempt safe-wait continuation must use R1's separate rule. Declare one complete mapping authoritative and make the summary table match it, rather than leaving implementers to select contradictory edges.

### R3 — Cancellation needs an explicit commit fence and must preserve actual terminal outcomes (blocking)

**Evidence:** §2 says cancellation stops new calls, while §4 and LC-18 say stop future calls at safe boundaries. §6 lists grant/credential checks immediately before calls but does not explicitly include the durable cancellation control revision. Ordinary generation success still maps RUNNING → IN_REVIEW; it is not expressly guarded against a concurrently committed Run CANCELLING. The cancellation-acknowledgment row ends with “cancelled attempts terminal,” although a CANCELLING/UNKNOWN attempt may reconcile to a verified SUCCEEDED/FAILED result and terminal outcomes are immutable.

**Fix:** State that the cancellation-request transaction commits the control revision and fences every not-yet-authorized dispatch/call and ordinary progression. Every claim, continuation, call authorization and result-publication transaction must check that control under the same serialization protocol. Define the call-authorization linearization point: actions durably authorized before cancellation may still reach the provider and require reconciliation; actions ordered after it are denied. This avoids promising atomic cancellation across a network gap. Results after cancellation may be recorded/settled by the trusted path but cannot move the Task to IN_REVIEW/COMPLETED. A cancelled logical Run/Task may retain immutable SUCCEEDED/FAILED attempts reflecting already-incurred outcomes; only actually cancelled attempts become CANCELLED. Acknowledgment requires all attempts terminal, no live claim or pending dispatch capable of execution, no UNKNOWN effects and reconciled accounting. Never relabel successful remote work CANCELLED merely to satisfy the aggregate.

### R4 — Make the complete-start transaction explicit and remove the matrix's partial-start alternative (blocking)

**Evidence:** §2 correctly requires complete admission and all-or-nothing stale rejection. §4 separately describes Mission READY → RUNNING; §2 separately requires transition receipts/outbox. Their membership in one Mission-start transaction is implied but not enumerated. LC-32 explicitly permits “Start conflicts or task blocked” for execution settings differing from the accepted snapshot. That permits a stale plan to be partially admitted, contrary to §2. LC-04 covers acceptance races rather than the complete start boundary.

**Fix:** Specify one start transaction containing: fresh locked authority/scope and complete accepted membership/configuration validation; Mission transition/control revision; every admitted Task and Run creation/state/pending projection; command receipt, audit and durable dispatch/outbox records. No claim or network call may occur before this commits; a failure rolls back all these effects. Distinguish snapshot mismatch (whole-start conflict, zero admission/dispatch) from a current execution-envelope blocker under unchanged accepted configuration (admission as BLOCKED where the frozen start policy permits it). Update LC-32 accordingly. Freeze how the plan's bound parent lifecycle is compared after acceptance itself changes PLANNING → READY, so the expected transition neither invalidates every start nor masks an unrelated later parent change.

### Acceptance matrix requirements review

The matrix correctly identifies itself as unexecuted requirements. Existing rows supply useful coverage but do not close the blockers merely by listing lease races or cancellation. Exact recommended additions/amendments, for its author to make separately:

| Matrix item | Required refinement |
| --- | --- |
| LC-05 | Unsupported legacy prerequisite rejects acceptance/start with no writes; not merely “no false readiness.” |
| LC-06/08/24 | Expire a lease during both a possibly dispatched call and a proven safe human wait; assert R1's distinct reconciliation/continuation path, old-token fencing, no overlapping attempts and no duplicate call. Include UNKNOWN → verified success while the Task stays blocked pending authorized disposition. |
| LC-09 | Restrict ordinary IN_REVIEW progression to a current eligible non-cancelling Run/claim. Late verified success under revocation/cancellation records evidence/accounting without ordinary progression. |
| LC-10 | Mark human ADMIN acceptance as proposed v1 policy pending product freeze, consistent with §5; this row must not silently approve that policy. |
| LC-18/20/28 | Include cancel versus claim/continuation/call authorization/result/reconciliation races, cancel from CREATED/BLOCKED/retryable FAILED, rejection of post-fence calls, preserved SUCCEEDED/FAILED attempt outcomes, and cancellation remaining requested while any effect/accounting is UNKNOWN. |
| LC-21 | Tie “no aggregate overspend” to enforceable reserved maxima and defined provider usage bounds. A reservation cannot guarantee a provider's unbounded/unknown final charge; retain conservative unknown accounting and avoid an unsupported financial guarantee. |
| LC-23/32 plus a new start row | Assert one atomic Mission+complete Task/Run+receipt/audit/outbox commit, stale configuration/authority-loss/insert-failure rollback with no partial admission or dispatch, and races with existing editor/create/intake writers under the organization mutex. Duplicate start replays without duplicate membership/Runs. |
| New escalation row | Exhausted limit/no eligible resolver creates visible durable scoped escalation; no automatic authority/ceiling increase, retry or invented recipient. |

### Remaining nonblocking clarification

For §4, explicitly reevaluate WAITING_APPROVAL → RUNNING-with-blocked-metadata when the sole-approval predicate becomes false even if no task is executable (for example, the last approval is rejected and becomes APPROVAL_REJECTED, or an additional resource/input blocker appears). Current text states the mixed-wait representation and predicate but explicitly describes only the executable/active-work exit. This should be a deterministic aggregate projection, not a status left stale until a later runnable task appears.

### Retest boundary

B3/B4/B5/B7 and all three original clarifications are addressed as documentation requirements; B1/B2 retain the specific open points above, and B6 needs the aggregate-exit clarification. R1–R4 block documentation freeze until reconciled. Subsequent API/schema/implementation contracts, product-policy decisions and executable evidence remain separate gates. This retest does not request code changes or certify any runtime behavior.


## Final bounded documentation retest — 31 October 2026

**Disposition: one concrete cancellation/lease precedence blocker remains; otherwise the prior retest fixes are addressed at documentation level.** Original findings and both prior dispositions remain historical above. This append reviews actual current text, not the change summary. No code was edited or tested, no other agents were spawned, and no implementation acceptance is claimed.

Source identities (SHA-256): contract `2425f8f20884349476089048b37ca13d40e26115cee44092752d1cdcf9bf169e`; matrix `986af71b49d2784682a90df051e11a2248a72742a11fb0a4368a2f7c41b51184`.

### Retest of R1–R4 and aggregate exit

| Item | Current documentation assessment |
| --- | --- |
| R1 | Safe-wait continuation leasing is now explicitly separate from new-attempt claiming; UNKNOWN reconciliation and authorized exact-output disposition are specified. Addressed except for the cancellation precedence case F1 below. |
| R2 | §3 now contains RUNNING → BLOCKED and BLOCKED → IN_REVIEW; rejected waits use FAILED or CANCELLING → CANCELLED. §2 expressly takes precedence over its summary. The live/UNKNOWN guard and accounted-effects requirement prevent ordinary replacement of unresolved work. No independent blocker retained for the former table omissions. |
| R3 | Cancellation has a durable revision, serialized claim/call/publication fence, outbound-authorization linearization point, and immutable actual terminal outcomes. Addressed except F1. |
| R4 | One complete Mission/Task/Run/control/receipt/audit/outbox start transaction is explicit; no precommit claim/call; whole rollback on stale snapshot, authority loss or insertion failure. Recorded post-acceptance READY revision avoids self-invalidating parent comparison. LC-32 and LC-44 now match whole-start rejection. Addressed at requirements level. |
| Residual aggregate exit | Explicit exit from WAITING_APPROVAL when the sole-approval predicate becomes false without runnable work is now present. Addressed. |

The complete-start wording no longer permits stale configuration to produce partial admission. An unchanged configuration with a current envelope blocker is separately identified and remains subject to the later frozen start policy; this is not authority to invent that policy now. Existing persisted status, configuration-history, receipt and blocked-activation boundaries remain intact.

### F1 — Lease expiry can overwrite an already committed CANCELLING Run (blocking)

**Exact current evidence:** §2's lease paragraph says, without a state/control qualifier: “If a call may be in flight, Task/Run become BLOCKED with OUTCOME_UNKNOWN and the attempt UNKNOWN.” The same section says cancellation moves every unfinished Run to CANCELLING, retains the Task's current state until acknowledgment, and acknowledgment uses CANCELLING → CANCELLED. The cancellation fence paragraph prevents ordinary progression, but it does not expressly give CANCELLING precedence over this unconditional lease-expiry transition.

**Concrete sequence:** A RUNNING attempt has a potentially in-flight call. Cancellation commits, leaving the Task RUNNING and Run CANCELLING. The bounded worker lease then expires while the provider outcome is unresolved. Applying the lease rule literally changes the Run to BLOCKED, losing the state from which the specified cancellation acknowledgment occurs. It can also incorrectly replace a cancellation-requested safe wait with a newly continuable WAITING state under the quiescent branch. The durable control should prevent new calls, but the lifecycle projection and completion path remain contradictory.

**Exact recommended fix:** Qualify both lease-loss branches: if cancellation is already committed, preserve Run CANCELLING and the retained Task projection; fence the claim and mark the attempt UNKNOWN only when its effect is uncertain, or reconcile/close a proven quiescent attempt without issuing a continuation claim. Record OUTCOME_UNKNOWN on the attempt/runtime blocker ledger while preserving the V004 pending-field invariant of the retained Task state. Trusted reconciliation may settle the attempt, then acknowledge CANCELLING → CANCELLED under the existing all-terminal/effects/accounting/no-live-claim/no-executable-dispatch guard. The normal Task/Run BLOCKED and safe-WAITING lease branches apply only when cancellation is not pending. Add this cancel-then-lease-expiry ordering to LC-43 (both in-flight and quiescent-wait variants). Cancellation must also win if its transaction is serialized after lease-loss processing.

### Matrix review as requirements only

Inspected all LC-01–LC-44 source rows. LC-05/09/10/21/32 and LC-41–44 now contain the requested legacy, non-cancelling publication, proposed-policy, financial-bound, continuation, reconciliation, cancellation-fence and atomic-start qualifications. LC-33–40 cover the earlier admission/serialization/escalation additions. They remain requirements, not executed cases or pass evidence. F1 is not explicitly covered by a cancellation-followed-by-expiry oracle.

Two nonblocking precision/presentation fixes remain:

1. The summary BLOCKED → READY guard still literally says “no unresolved live/UNKNOWN attempt,” rather than an explicit “all prior attempts terminal” guard. Make terminality explicit and name retained WAITING_INPUT/WAITING_APPROVAL attempts as ineligible for the fresh-attempt path; their only same-attempt route is the separately authorized WAITING continuation. This removes reliance on interpreting “live” broadly without changing the intended rule.
2. Blank lines before LC-33 and LC-41 terminate the preceding Markdown table, and the later row groups have no header/separator. Remove those blank lines or repeat table headers so all 44 requirements render as table rows. This is presentation only, not a missing source requirement.

### Final review boundary

No further concrete contradiction was identified in the bounded re-read of authority/escalation, legacy compatibility, complete-start atomicity or aggregate reevaluation. F1 still requires a documentation correction before an unconditional BA-02 freeze disposition. The detailed API/schema/transaction contracts and proposed product-policy freeze remain future gates. This append certifies no runtime behavior, test outcome, implementation approval, merge or deployment.


## Final targeted recheck — F1 and remaining clarifications, 31 October 2026

**Disposition: the remaining documentation blocker F1 and both nonblocking clarifications are closed. No outstanding blocking finding remains from this bounded independent documentation review.** Prior findings/dispositions are preserved above as history; this disposition supersedes their open status for the reviewed revisions only.

Inspected the actual cancellation/lease paragraph and adjacent cancellation/transition guards, the BLOCKED → READY summary row, and the acceptance matrix's row continuity and LC-43 oracle. Source SHA-256 identities:

- Contract: `9bab0970b16543bc420a18b87347b2a2629ceb3a14ff612279970b915861d0b8`.
- Matrix: `6a885c4144e5f17c1be1fe28746c711c55fc0e06e63e27c23bd4b1757823002f`.

| Rechecked item | Documentation outcome |
| --- | --- |
| F1 cancellation before lease expiry | Explicit cancellation precedence preserves Run CANCELLING and the retained Task projection; uncertain effects are recorded on the attempt/runtime blocker ledger without violating the retained Task's pending-field invariant. No continuation claim is issued for a quiescent cancelled wait. Normal BLOCKED/WAITING lease branches expressly exclude pending cancellation. Closed. |
| F1 lease expiry before cancellation | The later serialized cancellation moves the resulting unfinished Run to CANCELLING and fences continuations. Trusted reconciliation precedes guarded acknowledgment; existing terminal/effects/accounting/claim/dispatch checks remain in force. Closed. |
| Prior-attempt terminality | BLOCKED → READY now expressly requires every prior attempt terminal with effects accounted for, and excludes retained typed-wait attempts from fresh-attempt eligibility. Same-attempt continuation remains a separate WAITING path. Closed. |
| Matrix presentation and coverage | LC-01–LC-44 are contiguous table rows. LC-43 explicitly covers cancel-then-expiry for both an uncertain call and a quiescent wait, as well as expiry-then-cancel continuation fencing. Closed as requirements coverage, not executed evidence. |

No concrete remaining contradiction was identified in these exact changes or their interaction with the adjacent previously reviewed guards. The documentation is suitable for BA-02 freeze from this review's bounded lifecycle/authority perspective, while proposed product policy and each later slice's exact API/schema/transaction contracts remain separate freeze requirements.

Only this review was appended; all prior content was preserved. No other agents were spawned. No code tests, runtime behavior, implementation approval, PR acceptance, merge, deployment or production readiness are claimed. The matrix remains requirements only.
