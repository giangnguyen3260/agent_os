# Admitted Mission graph completion validation

21 November 2026, Asia/Ho_Chi_Minh. Candidate is stacked on corrected PR123 head `477ce690d92a1a2b0b123cfe2a46160cee3069b3`. That parent has successful Quality, Vault, SQL/options/guarded-publication workflows; Docker retained regression is still running at this checkpoint. The original PR123 head failed a mobile mocked recovery timing assertion; corrected test waits for completed reload and malformed-receipt processing, with 45 repeated graph browser cases passed locally.

Implemented: separate opt-in graph-completion API/UI, V055 immutable phase receipts, full canonical source-pinned manifest, fresh per-Run settled accepted proof, REVIEW then COMPLETED with CAS/headroom, historical replay, scoped exact single-output reread and protocol exclusion. TaskCreate/context freezes new creation after persisted graph admission, independent of flags, preserving historical create receipts. Intake resume cannot bypass because it creates no Tasks.

Executed locally:

- 12 new H2 graph completion cases: complete diamond/two phases/stable manifest/replay; incomplete or extra scope; changed accepted output/config; unsettled costs and pause/cancel; injected receipt rollback; corrupted review payload/headroom; one-Task graph legacy denial and intake freeze/create replay; fresh ADMIN and output identity; same-key concurrency; strict JSON/CSRF/no-store; exact source digest mutation after REVIEW; successful terminal read at maximum control revision.
- 31 retained backend cases passed with the new source: graph admission/dependency release, runtime persistence, old single-Task completion and configured-plan acceptance. Total 43 cases, zero failures/errors/skips.
- 72 mocked browser cases passed across desktop/tablet/mobile: 9 new graph completion cases plus 63 retained review/acceptance/admission/single-Task completion cases. Two explicit transitions, confirmation, escaped bounded accepted-text read, exact ambiguous retry, authority erasure and tampered-text rejection are covered.
- Production frontend build, lint/format, Java compilation/import/format and whitespace checks.

Native/live checks are authored, compiled and wired from initial publication, but have not run locally:

- `MissionGraphCompletionPostgresIT` inherits all 12 new cases in an isolated required native PostgreSQL schema; no H2 fallback or silent skip.
- The existing Vault real-backend diamond browser scenario now continues from four explicit Start/ACCEPT Runs through graph REVIEW/COMPLETED, reload and accepted-output reread. It uses real native PostgreSQL/session/CSRF/TLS Vault/worker with a local simulated OpenAI endpoint. Java asserts exactly four provider calls/settled usage records, two graph completion receipts and no legacy completion receipts. No additional provider call is sent by closure.

No paid OpenAI acceptance, merge or deployment. Existing unsupported out-of-plan Tasks/Runs fail closed with an explicit scope blocker; no migration/adoption, deletion or subset inference is introduced. General recovery, generated planning, Skills/Tools and prerequisite-output context remain separate work.
