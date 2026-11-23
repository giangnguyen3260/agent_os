# Recorded Run accounting

23 November2026. Existing current-ADMIN Run/Task-Run reads add an accounting object in the same repeatable-read scoped transaction. Aggregates cover the full Run ledger/usage history independently of the100-attempt display limit. Settled actual microUSD, HELD reserved microUSD, UNKNOWN reserved microUSD and unresolved reservation count remain separate. Input/output tokens are recorded usage, not estimates or a claim that unrecorded usage is zero.

All numeric fields use nonnegative decimal strings. Frontend converts microUSD with BigInt (one USD=1000000 microUSD), avoiding IEEE754 rounding; missing accounting from an older server is tolerated, malformed accounting is not displayed. Fresh authority checks and existing identity/focus generation fencing apply. No provider request, reservation mutation, new billing policy or external bill reconciliation is introduced.

Local validation includes ledger SETTLED/HELD/UNKNOWN transitions, recorded tokens and revoked ADMIN access, inherited by the required native MissionGraphCompletionPostgresIT suite. Browser cases cover exact large-integer formatting, UNKNOWN visibility, malformed numbers and focus-time revocation across three viewports. Matching-head native CI remains pending at publication. Stacked on PR128 head2755634650b67b0f3bd5c465687db2368957bea0, whose six workflows passed. No merge/deployment.
