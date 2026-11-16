# Run and Attempt persistence foundation

V036 creates empty tables for accepted plan revisions, their scoped Task and dependency snapshots, logical Runs, and numbered Attempts. Existing Missions and Tasks keep their current state. The migration adds no seed rows, dispatcher, lease, or runtime endpoint.

A Run references the exact organization, Team, Mission, Task, and accepted plan revision. The first runtime version has one logical Run per Task across retries and rework. Attempts have distinct identities and numbers within that Run. An unresolved Attempt reserves the Run's single unresolved slot, including a safe wait or an unknown outcome. An Attempt that has reached a verified terminal outcome occupies its own numbered slot.

The database enforces row shape, scope and uniqueness. The future acceptance command owns the immutable snapshot, full DAG validation, membership, revision checks and the accepted post-transition parent/control revision. The future orchestration service owns atomic admission, Task/Run state transitions, next attempt numbering, terminal immutability, and reconciliation. Before a worker can act, the service must implement claim/lease fencing, grant checks and resource reservations under the [runtime lifecycle authority contract](RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md).

## Claim leases

V037 adds a claim history scoped to Run and Attempt. The internal claim store locks the organization first, then the Run and the relevant Attempt/claim rows. Each fresh claim advances the Run's fencing token. A check binds the token to the organization, Run, Attempt and worker, requires running state and uses the database clock after taking locks. Renewal extends the current lease within its original deadline. Expiry does not release the Attempt or prove the outcome. Explicit invalidation fences the claim, and fresh issuance cannot reuse that Attempt.

Use the claim store within the orchestration transaction that verifies the authenticated worker and current eligibility, updates Task/Run/Attempt state, and records reconciliation or authorization of outbound effects. The caller supplies the policy-approved deadline. This slice provides persistence and fencing primitives. It does not wire claim issuance into Mission start, support continuation claims, process expiry, reserve budget or dispatch an outbound call.
