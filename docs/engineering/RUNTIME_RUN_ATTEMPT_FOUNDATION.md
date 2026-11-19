# Run and Attempt persistence foundation

V036 creates empty tables for accepted plan revisions, their scoped Task and dependency snapshots, logical Runs, and numbered Attempts. Existing Missions and Tasks keep their current state. The migration adds no seed rows, dispatcher, lease, or runtime endpoint.

A Run references the exact organization, Team, Mission, Task, and accepted plan revision. The first runtime version has one logical Run per Task across retries and rework. Attempts have distinct identities and numbers within that Run. An unresolved Attempt reserves the Run's single unresolved slot, including a safe wait or an unknown outcome. An Attempt that has reached a verified terminal outcome occupies its own numbered slot.

The database enforces row shape, scope and uniqueness. The future acceptance command owns the immutable snapshot, full DAG validation, membership, revision checks and the accepted post-transition parent/control revision. The future orchestration service owns atomic admission, Task/Run state transitions, next attempt numbering, terminal immutability, and reconciliation. Before a worker can act, the service must implement claim/lease fencing, grant checks and resource reservations under the [runtime lifecycle authority contract](RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md).

## Claim leases

V037 adds a claim history scoped to Run and Attempt. The internal claim store locks the organization first, then the Run and the relevant Attempt/claim rows. Each fresh claim advances the Run's fencing token. A check binds the token to the organization, Run, Attempt and worker, requires running state and uses the database clock after taking locks. Renewal extends the current lease within its original deadline. Expiry does not release the Attempt or prove the outcome. Explicit invalidation fences the claim, and fresh issuance cannot reuse that Attempt.

Use the claim store within the orchestration transaction that verifies the authenticated worker and current eligibility, updates Task/Run/Attempt state, and records reconciliation or authorization of outbound effects. The caller supplies the policy-approved deadline. This slice provides persistence and fencing primitives. It does not wire claim issuance into Mission start, support continuation claims, process expiry, reserve budget or dispatch an outbound call.

## Budget holds and verified usage

V038 adds independently approved budget windows and action reservations. The internal store resolves the organization, Team, accepted-plan Agent and Mission from the current fenced Run and requires exactly one open budget window at each scope. Missing or overlapping windows deny the hold. One transaction reserves the same estimated amount against all four windows, stored as integer micro-USD. A replay of the same action, claim and amount returns the existing hold without allocating again. Dispatch still requires its own durable authorization and replay handling.

An unknown outcome retains the full hold. A trusted reconciliation transaction records verified actual usage once against the original windows, even after expiry or when actual usage exceeds the estimate. A conflicting settlement fails. An overrun remains visible in spend and reduces or exhausts capacity for new holds. Budget policy configuration must use the organization lock shared with runtime writers. This slice adds no production limits, window provisioning API, rate/concurrency admission, token metering, or provider dispatch.

## Rate and concurrency

V039 requires an explicit capacity policy for each organization, Team, Agent and Mission matched by the reservation. The budget store checks all four policies under the organization lock in the same transaction as the claim and budget hold. Rate counts committed action admissions in a rolling window. Concurrency counts held and unknown reservations until verified settlement. Both counts span budget-window changes. A matching hold replay creates no admission, and a rolled-back hold consumes no capacity. Existing reservations without admission timestamps count from migration time for rate purposes.

Policy writers must use the same organization lock as admission. The migration creates no default policies. Capacity represents locally admitted actions and is conservative for uncertain provider outcomes. Provider dispatch remains gated.

## Verified model usage

V040 records model input and output token counts for a settled action. A trusted reconciliation caller verifies the provider response against the action before submitting it. The store writes the token receipt and settles the corresponding budget reservation in one transaction. An exact replay preserves the first result, and a conflicting replay or reuse of the same provider request for another action fails. The receipt stores a SHA-256 digest of the request identifier and a same-organization provider connection reference. Cost-only settlements remain readable and cannot be retroactively turned into model receipts.

This slice adds accounting storage. Provider response verification and dispatch require the future outbound authorization ledger.

## Model action authorization

V041 records the exact Run, Attempt, claim fence, accepted owner, provider connection and credential revision, model and canonical request digest. The internal store checks the running Task and accepted revisions, current grant and provider evidence, then acquires the budget hold and writes the decision in the same transaction. Only a fresh hold can authorize an action. Reconciliation must use the recorded connection when a decision exists. Legacy reservations retain their existing reconciliation behavior.

The gateway remains blocked. Before enabling transport, the runtime caller must provide the authenticated worker, serialize Mission execution controls with this decision, and dispatch only the exact committed request. An authorization row alone is insufficient to infer whether a provider received a request.

## Mission execution controls

V042 adds a Mission runtime control revision independent of Task intake. The internal start transaction initializes the control explicitly. New claims and model authorizations require OPEN and fail closed when the control is absent. Model authorization records the control revision in the same transaction. Control updates use the same organization lock and reject stale revisions. A pause request stops new claims and model authorizations while existing claims can report already-incurred effects. Resume can reopen a pause request, while cancellation is irreversible.

A cancellation request atomically moves unfinished Runs to CANCELLING, fences ordinary claim operations and retains Task and Mission statuses. Active and waiting Attempts move to CANCELLING, unstarted Attempts close as CANCELLED, and UNKNOWN and terminal evidence are preserved. Budget holds and model authorizations persist for reconciliation. A later acknowledgement must verify that every effect has been accounted for before changing Task and Mission statuses. These internal operations are for authorized lifecycle transactions. This migration adds no public start, pause, resume, cancel or dispatch entry point.

Cancellation acknowledgment runs under the organization and Mission locks. It requires the exact cancel-request revision, terminal Attempts, no unexpired and uninvalidated claim, and settled reservations across all Runs in the Mission. The acknowledgment atomically cancels the remaining admitted Tasks and Runs, clears pending Task fields, and sets the Mission status to CANCELLED. It preserves completed work, unadmitted Tasks, and Attempt outcomes. Mission status records completion of the durable cancel request. The revision advances once, and a stale or repeated direct store call fails.

There is no runtime dispatch queue in this slice. A future dispatch queue must join this acknowledgment transaction so queued work cannot survive cancellation.

## Model request handoff

V043 records a one-time handoff for an authorized model action. The internal store hashes the exact request bytes, rechecks the current claim, Mission revision, model grant, connection credential revision and held reservation, and returns a read-only copy of the verified body. A rolled-back handoff is available to retry. The store rejects a repeat handoff or an authorization issued at a different Mission control revision.

The transport must use the handoff only after its transaction commits. A committed marker shows that a request was handed off for sending. A control change committed afterward can still race with an already-handed-off send. Provider delivery and uncertain outcomes require reconciliation. This change introduces no HTTP transport or runtime worker entry point.

## Model delivery reconciliation

V044 records the first uncertain delivery observation and the eventual accounting disposition. For an authorized model action, uncertain delivery requires a committed handoff and preserves the budget hold. Verified model usage requires the same connection and a handoff, and commits the receipt and disposition with the charge. A trusted cost-only settlement of an authorized model action requires verified zero cost and distinguishes actions that were never handed off from handed-off actions verified to carry no charge. This accounting disposition alone does not prove an Attempt succeeded or that all other effects are resolved.

Historical settlements remain replayable, and reservations outside the model authorization ledger retain their existing behavior. There is no automatic resend or network-based reconciliation in this change.

## Lease expiry

The internal expiry transaction matches the latest fence and reads the database clock after locking. A stale, renewed or already-invalidated claim is a no-op. A proven unhanded model action releases its hold at zero cost. A possibly sent action with unsettled accounting is marked unknown and retains its hold. A running Attempt with a possible remote effect requires reconciliation even if accounting has already settled. A recorded safe wait with no unresolved accounting remains WAITING.

Expiry blocks an active Task and Run and retains the original Attempt outcome for reconciliation. If cancellation already owns the Run, expiry leaves it CANCELLING and preserves the Task projection. The transaction adds no scheduler, automatic retry, or provider call.

## Scheduled lease expiry

The backend scans for the latest expired claims every five seconds by default. It processes up to 16 candidates per scan, with one transaction per claim. A rotating cursor lets later claims progress when an earlier claim consistently fails. Each expiry transaction rechecks the fence and the database clock, so multiple backend instances and concurrent renewal remain safe. Scan progress is local to the process and can restart from the oldest candidate after a restart. V045 adds the scan index.

Set `agentos.execution.lease-expiry.enabled=false` to disable the scanner or change the interval with `agentos.execution.lease-expiry.delay-ms`. The scheduler processes lease transitions only. Provider dispatch and retries remain separate.

## Verified Attempt reconciliation

V046 records one verified outcome and evidence digest per Attempt. A trusted caller verifies the evidence before recording it. The transaction requires an UNKNOWN Attempt on a BLOCKED Run, or an UNKNOWN or CANCELLING Attempt on a CANCELLING Run. An unexpired, uninvalidated claim or unsettled reservation for the Attempt blocks the transition. Recording an outcome closes the Attempt in its numbered terminal slot. The exact receipt is replayable, while conflicting evidence or a different outcome is rejected.

The Run, Task and Mission stay at their current lifecycle states. Submission for review, retry and cancellation acknowledgement remain distinct operations. The store persists the digest only and introduces no external evidence verification or public endpoint.

## Review submission after reconciliation

V047 lets trusted reconciliation bind a successful Attempt to the digest of its verified output. Historical or cost-only reconciliation receipts without an output digest remain valid. An authorized caller can submit the latest reconciled successful Attempt when its exact output digest and Run revision match. The transaction requires a blocked Run and a Task blocked by an unknown outcome, no live claim or unsettled reservation, and a Mission without a cancellation request. Pausing new execution does not prohibit review of an existing output.

The submission advances the Run and Task to IN_REVIEW and preserves an idempotent receipt. It records output identity only. Artifact storage and retrieval, reviewer decisions, and public commands remain separate.

## Review decisions

V048 records one human ADMIN decision for the exact review submission. The command rechecks the account credential and organization role before receipt replay. It binds the decision to the reviewed output digest and Run revision. Acceptance completes the Run and Task. Rework requires a reason and an explicitly configured remaining per-Run limit. It returns the Task to READY and the Run to CREATED while retaining the successful Attempt for history. A new claim and Attempt still require admission. A null rework limit keeps rework unavailable.

The decision and audit event commit with the Task and Run transitions. Public endpoints, reviewer UI, dispatch, and recomputation of the aggregate Mission status remain separate.

## Output review API

Current workspace ADMINs can GET `/api/v1/organizations/{organizationId}/execution-runs/{runId}/review` for the pending Attempt ID, Run revision, verified output digest, and rework availability. When no current review exists, the endpoint returns 404. POST to the same path plus `/decision` with `attemptId`, `expectedRevision`, `outputSha256`, `decision` (ACCEPT or REWORK), and an optional `reason` required for rework. An exact repeat by the original authorized reviewer returns 204, the same as the initial decision. Conflicts return 409. Mutations require the existing CSRF token. The API exposes output identity, while content retrieval and reviewer UI remain separate.

## Verified text output

V049 stores verified text for a reconciled successful Attempt. The internal writer checks the exact UTF-8 SHA-256 digest against reconciliation evidence and permits at most 256 KiB of valid UTF-8 text. Matching writes replay. Raw content stays outside audit and Trace metadata.

ADMIN can GET the current review's `/output?attemptId=...&expectedRevision=...` endpoint. It returns JSON text with a no-store response header and rechecks current authority, review identity, revision and content integrity. Missing historical content returns 404. A stale review returns 409. This slice chooses bounded text as the first output format. Artifact files and reviewer UI remain separate.

## Execution review page

The frontend route `/workspace/{organizationId}/execution-runs/{runId}/review` loads current review metadata and its exact output version. It displays output as escaped text and offers a separate confirmation for acceptance or a reasoned rework request when available. The page clears private output on identity or route changes and revalidates on focus. Conflicts and ambiguous mutation outcomes require a refresh before another decision. Browser acceptance uses intercepted fixture responses for this page. Task and Mission navigation still need Run discovery to link into the route.

## Task review navigation

Task detail checks the scoped `/execution-review` endpoint when a current ADMIN opens an IN_REVIEW Task. A matching pending review exposes its Run ID and enables the review link. Missing or foreign Task, Team, Mission or Run scope returns 404. Member reads are denied. Review decisions continue to recheck current authority and revision at mutation time.

## Atomic verified result publication

`ExecutionResultPublicationService` is an internal trusted-reconciliation boundary. After evidence verification and usage settlement, a worker outside an existing transaction may publish the exact successful evidence digest, output digest and bounded text against an expected Run revision. One independent transaction records reconciliation, verifies/stores text and submits the Run/Task to review. Existing claim, unresolved budget, lifecycle and scope gates remain enforced by the stores. A mismatch in any stage rolls back earlier writes. Exact replay does not advance the Run revision twice.

Calls under an ambient transaction are rejected before writes, avoiding a suspended transaction holding business locks while the publication waits for its own Run lock. No HTTP ingress, worker scheduler or provider transport is enabled. Caller trust, provider evidence verification, usage settlement and live model dispatch remain separate prerequisites; this service must not accept arbitrary client claims of success.

The H2 persistence integration exercises invalid content and stale revision rollback, unchanged UNKNOWN attempt and missing partial output/evidence, ambient transaction rejection, successful publication and exact replay. Native PostgreSQL concurrency acceptance for this new composite boundary remains open.
