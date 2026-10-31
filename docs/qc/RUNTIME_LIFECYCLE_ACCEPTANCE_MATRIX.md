# BA-02 runtime lifecycle acceptance matrix

Requirements only, not executed tests. Coordinator-authored against RUNTIME_LIFECYCLE_AUTHORITY_CONTRACT.md; independent review status belongs to the separate review. No runtime implementation exists in this slice. This matrix is an input to later BE/FE/native QC work, not evidence of passing behavior.

| ID | Scenario | Required oracle / expected result | Delivery owner |
| --- | --- | --- | --- |
| LC-01 | Existing rows in each of9 V004 states and legacy receipts | No migration/status rename/baseline or synthetic run; existing reads/receipts unchanged | BE/QC |
| LC-02 | Legacy non-DRAFT task has no admitted plan/run | Runtime mutation safely blocked; no inferred completion/assignment from saved status | BE/QC |
| LC-03 | Model proposes Task status or direct tool action | Proposal cannot mutate lifecycle or bypass gateways | SA/BE/QC |
| LC-04 | Plan regenerated while inputs/owner/dependencies change | Stale acceptance conflicts; no overwritten tasks/receipts or partial plan commit | BE/native QC |
| LC-05 | Manual completed prerequisite has no compatible accepted output | Unsupported legacy prerequisite rejects acceptance/start with no writes; explicit legacy boundary displayed | BA/BE/FE/QC |
| LC-06 | Two workers claim same task; old lease later returns | One current claim; stale worker cannot commit output/state or settle resources | BE/native QC |
| LC-07 | Queue redelivers start or command reply lost after commit | Same scoped command replays original result; no duplicate committed transition/outbox | BE/native QC |
| LC-08 | Provider/tool timeout after possible remote effect | UNKNOWN remains visible; no automatic unsafe retry or assumption of zero cost | BE/FE/QC |
| LC-09 | Provider generation succeeds | Only current eligible non-cancelling claim publishes IN_REVIEW; late verified success under revocation/cancellation records evidence/accounting without progression; never auto-COMPLETED | BE/QC |
| LC-10 | Producing/reviewer Agent says accepted | Recommendation only; proposed v1 current-human-ADMIN policy accepts exact output version, pending product freeze | BE/QC |
| LC-11 | Reviewer accepts stale output or duplicated decision | Conflict or exact original replay; one acceptance, no overwritten newer output | BE/native QC |
| LC-12 | Reviewer requests bounded rework | Prior attempt/output retained, new attempt identity, enforced rework budget | BE/FE/QC |
| LC-13 | One task waits approval while another can execute | Mission does not falsely become wholly WAITING_APPROVAL | BE/QC |
| LC-14 | Runtime approval rejected/expired or payload changed | No resume/side effect; typed blocker; no automatic whole-Mission failure without policy | BE/QC |
| LC-15 | Human ADMIN has no explicit action approval grant | Runtime approval denied; ADMIN alone is insufficient under approval contract | BE/QC |
| LC-16 | Approval from another task/tenant, stale action or grant loss | No cross-scope/expired action execution; decision and continuation reauthorize | BE/native QC |
| LC-17 | Intake pause during a run | Existing intake/edit contracts apply; no implied execution pause | BE/FE/QC |
| LC-18 | Execution pause or cancellation requested during outbound action | Stop future calls at safe boundary; show requested state until reconciled; no fake instantaneous cancellation | BE/FE/QC |
| LC-19 | Agent suspended/grant revoked after claim but before call | No new invocation; resolve/preserve current attempt and accounting | BE/native QC |
| LC-20 | Revoke while remote provider already accepted request | Do not promise remote rollback; reconcile result privately and prevent new unauthorized calls | BE/QC |
| LC-21 | Concurrent reservations exhaust shared budget/concurrency | Atomic reserved maxima and provider usage bounds enforced; no double settlement/release; unknown/unbounded external charges remain explicit accounting risk, not a guaranteed financial ceiling | BE/native QC |
| LC-22 | Usage or pricing missing | Unknown displayed; reservation held conservatively pending reconciliation, no fabricated zero | BE/FE/QC |
| LC-23 | State/history/audit/outbox insertion fails | Entire transition rolls back atomically | BE/native QC |
| LC-24 | Worker restart between claim, outbound call and result commit | Recover via fenced ledger/reconciliation; no lost command or unbounded duplicate side effects | SA/BE/native QC |
| LC-25 | WAITING/BLOCKED versus other Task states | Exact V004 pending-field invariant; typed runtime blocker separate from legacy display text | BE/native QC |
| LC-26 | Old reason string resembles an approval/task ID | Display only; no inferred capability, trusted resolver identity or guessed link | BE/FE/QC |
| LC-27 | Role/account/scope changes while UI request in flight | Clear private data and ignore late success/error; fresh server authority on next action | FE/browser QC |
| LC-28 | Terminal Mission completion/cancel/failure | No live/UNKNOWN attempt; all required outputs accepted for completion; no silent terminal reopen | BE/native QC |
| LC-29 | Artifact download/read or Trace inspection | Fresh scoped access; no secrets or unrestricted input/output in safe metadata | BE/FE/QC |
| LC-30 | Provider settings edit/test/rotation | Masked readback, encrypted retrievable key, bounded explicitly triggered test; no secret in logs | BA-03 BE/FE/QC |
| LC-31 | UI review/start/retry/controls at1440/768/390 | Actual state/cost/reason, keyboard/focus, no hardcoded success or inaccessible required actions | FE/browser QC |
| LC-32 | Execution settings differ from accepted plan snapshot | Snapshot mismatch rejects whole start with zero admission/dispatch; only unchanged configuration with a current envelope blocker may be admitted BLOCKED under frozen policy | BE/native QC |
| LC-33 | Acceptance precedes start; dependent tasks remain initially DRAFT | Acceptance is snapshot-only; explicit start transitions complete membership to READY/BLOCKED atomically; no admitted dependent remains editable DRAFT after dispatch | BE/native QC |
| LC-34 | Start/worker transition races existing dependency writer | Existing organization-first graph mutex/order respected, post-wait snapshot/authority reread; no incompatible Task-first locks | SA/BE/native QC |
| LC-35 | Late result after grant revocation or stale lease | Worker cannot advance/settle; trusted reconciler accounts immutable locator once without retry/acceptance; actual cost/effect retained | BE/native QC |
| LC-36 | Independent work becomes runnable while other approval remains pending | Mission aggregate leaves WAITING_APPROVAL; mixed blockers use explicit metadata, not new Mission status | BE/native QC |
| LC-37 | No eligible human resolver / retry ceiling exhausted | Durable typed escalation and visible blocked state; no invented recipient, budget reset or automatic privilege escalation | BA/BE/FE/QC |
| LC-38 | Cancel queued CREATED or retryable FAILED logical Run | CANCELLING allowed; Task final CANCELLED only after all effects reconciled; no new attempt | BE/native QC |
| LC-39 | Safe external-input wait versus approval wait | Correct typed Task/Run reason and WAITING_INPUT/WAITING_APPROVAL attempt; no falsely labelled approval | BE/FE/QC |
| LC-40 | Valid saved DAG depends on unsupported legacy completion | Plan acceptance/start rejects unsupported prerequisite without writes; no dropped edge or permanent ordinary dependency wait | BE/native QC |
| LC-41 | Lease expires during a proven safe input/approval wait | Old token fenced; new continuation claim binds same attempt/boundary after authority/control check; no duplicate call or new attempt | BE/native QC |
| LC-42 | UNKNOWN resolves to verified successful output while Task blocked | Trusted service accounts once; authorized explicit disposition submits exact output without a call or records discard/retry; no automatic progression | BE/native QC |
| LC-43 | Cancel races claim, continuation, call authorization and publication | Pre-fence durable authorization may reconcile; post-fence authorization denied; no late IN_REVIEW/COMPLETED; preserve actual SUCCEEDED/FAILED outcomes; cancel-then-lease-expiry preserves CANCELLING for both uncertain call and quiescent wait with no continuation claim; expiry-then-cancel also fences continuation | BE/native QC |
| LC-44 | Whole-start insert failure/stale config/authority loss; duplicate start | One Mission+complete Task/Run+receipt/audit/outbox transaction; rollback leaves zero admission/dispatch; replay adds no Runs; recorded post-acceptance READY revision compared | BE/native QC |

Before implementation each slice must bind these rows to exact DTO/schema/error/command and deterministic test setup. PostgreSQL tests must observe real lock/claim/race conditions rather than timing-only sleeps. Report executed outcomes with exact source identity; this file carries no pass counts.
