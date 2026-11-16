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
