# Real worker unsent failure → explicit preparation → fresh Start gate

The controlled Vault graph application enables unsent recovery. Its test-only pre-poll injector waits for the first queued Graph A source-revision0 command, permanently revokes its original Tool grant and creates a distinct fresh grant. It never edits Task/Run/Attempt/claim/evidence state or resets the old grant. The approved operation still pins the revoked grant, so the actual worker must fail before provider transport and atomically create its own unsent attestation.

Browser flow waits for the BLOCKED Run, opens the condition-check page, requires explicit preparation acknowledgement, verifies the2→3 historical preparation receipt and returns to Run details. It approves a new source-revision Tool selection against the replacement grant and separately Starts. Existing real-worker approved Tool, PAUSE/RESUME, accepted dependency outputs, four-Task closure and reviewed import flow continue.

Native assertions require injection observed, exactly one actual worker unsent proof linked to a FAILED Attempt with no provider handoff, one preparation receipt, two immutable Tool approvals, exactly one Tool effect linked by approval key and zero-cost settlement, five FINISHED dispatches, exactly four model sends/usage receipts and unchanged52-microUSD expense. The extra failed admission is local; it is not an extra provider request. Historical first approval stays recorded and is not reused for the fresh source revision.

Disposable combined web/worker role gets SELECT+INSERT only on worker proof and recovery receipts; no UPDATE/DELETE. Grants remain SELECT-only to that login; only the privileged fixture admin changes grant setup for fault injection. No deployed permissions or runtime product behavior changed by this test slice.

Local Java fixture compile/format/import, JavaScript syntax/diff and retained mocked preparation/history UI checks are separate from execution evidence. Docker/PostgreSQL unavailable locally: the new real worker proof path must pass matching-head Vault CI before claiming acceptance. Provider is controlled/simulated, not paid OpenAI. No merge or deployment.
