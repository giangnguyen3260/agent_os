# Activation review acceptance cases

These cases accept real preflight and the unsupported-activation rejection boundary, not positive activation. Providers, model authorization, tool grants and the execution worker are absent in this release. No fake ready adapter or manual flag may bypass those missing dependencies.

CI fixtures use dedicated organization 77777777-7777-4777-8777-777777777777 and activation-admin/member accounts with a random CI password. Per-width agents are isolated from the 117 baseline tests. Tests reset current configuration through the authorized Studio API using its actual revision. Role-revocation cases restore the fixture in finally.

| ID | Action | Expected result | Automation |
|---|---|---|---|
| AR-01 | ADMIN enters from a DRAFT's Studio | Real name/status/revision/check time and nine ordered checks; missing model/limits blocked; no fictitious Validation passed | Browser + backend |
| AR-02 | Configure real model request/limits then Check again | New revision/checks loaded; configuration passes only when valid; model/tool/runtime authorization still UNAVAILABLE; Activate disabled | Browser + native PostgreSQL |
| AR-03 | Exceed Team concurrency or monthly budget, or remove Team USD currency/settings | TEAM_ENVELOPE blocks with actual values/actionable reason; no allocation or enforcement claim | Backend + browser budget case |
| AR-04 | Use Keep draft or return from an ACTIVE review | Navigation only; no POST or status change; ACTIVE uses Back to studio | Browser |
| AR-05 | Refresh fails, returns null 200 or malformed/wrong-scope data | Prior checklist cleared; error and GET retry; no stuck loading, stale pass or UI write | Browser + local malformed/scope fixtures |
| AR-06 | MEMBER, revoked account/credential/membership, or foreign scope requests review | No private review on denial; unauthorized workspace403; foreign target inside authorized workspace404 | Browser + backend |
| AR-07 | ADMIN posts current revision to activation | 409 ACTIVATION_BLOCKED with full current review; exact business rows/history/receipts/tasks unchanged | Browser + native PostgreSQL |
| AR-08 | Repeat/concurrently post blocked activation, or post an older revision | Blocked requests remain409 without writes; older revision409 CONFIGURATION_CONFLICT; no duplicate work | Backend + native PostgreSQL |
| AR-09 | Missing CSRF; null/string/boolean/fractional/out-of-range revision | 403 missing CSRF;400 invalid revision; no writes | Backend + browser/native CSRF |
| AR-10 | Legacy DRAFT without contract, missing/inactive/unassigned team, boundary limits | Only actual valid configuration checks pass; missing data does not become effective permission; no foreign Team metadata | Backend |
| AR-11 | Inspect 1440/768/390; use compact dialog and Escape | No horizontal overflow; readable actionable states; dialog dismisses and returns focus to Menu | Browser + local rendering |

Native PostgreSQL probe compares exact Agent/config rows and version/receipt/task counters after real setup, including simultaneous blocked POSTs, stale revision and CSRF denial. This is concurrent HTTP evidence, not a forced database barrier or load test. Session/audit bookkeeping outside the listed business state is not an activation mutation. Local HTTP fixtures verify only rendering/interactions. Actual final counts, revisions and run links live in AGENT_ACTIVATION_QC_REPORT.md.
