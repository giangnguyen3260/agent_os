# Accepted dependency output context validation

21 November 2026, Asia/Ho_Chi_Minh. Stacked on PR124 head `69f064f76c16f7b5ec96acf1c189365e0b7750ac`; both PR123 corrected head `477ce690d92a1a2b0b123cfe2a46160cee3069b3` and PR124 have all six workflows successful.

Implemented locally: immutable admission-pinned ORDER_ONLY/REQUIRED policy, complete bounded direct-predecessor context capture, exact preview and fingerprint, transactional V056 dispatch evidence with scoped foreign keys, fresh claim/authorize/handoff verification and explicit outgoing-body SHA binding. Accepted output provenance includes its historical context digest. Historical Start replay rechecks stored integrity but creates no new effect and does not rebuild changed source data. Context is encoded as user reference data before the final current Task; no Tools or authority are introduced.

Local evidence:

- 10 new H2 cases: explicit empty roots/direct-only chain, unavailable accepted sources, pinned mode and feature-off denial, admission-mode fingerprint race, immutable request/context tamper, oversized output without dispatch/truncation, insert rollback, changed provenance with identical text, instruction-like source text staying user data, disabled-feature historical replay without transport, and actual ModelAuthorizationStore authorize/handoff rejecting an alternate body despite valid stored context.
- 43 retained backend cases covering graph admission/closure, old single-Task closure, runtime persistence and configured acceptance. Final selected total: 53 cases, zero failures/errors/skips.
- 90 mocked browser cases across desktop/tablet/mobile: 9 new context-preview cases and 81 retained OpenAI/review/acceptance/admission/closure cases. Exact escaped preview before explicit Start, inconsistent digest/count rejection and authority erasure are verified.
- Frontend production build, lint/format, Java compilation/import/format and whitespace checks.

Required external evidence is authored/compiled and wired from initial publication, not yet executed locally:

- `ExecutionDependencyContextPostgresIT` requires native PostgreSQL settings and runs all 10 new cases in an isolated random schema with no H2 fallback.
- The native PostgreSQL/TLS Vault/real-backend worker diamond fixture now admits REQUIRED mode. Provider and browser independently check B/C receive exact A text; D receives exact B+C text without separately injecting A. Outputs are distinct per Task. It verifies four immutable context records, four explicit provider sends, unchanged settled usage and full graph REVIEW/COMPLETED with output reread. The provider endpoint is simulated, never paid OpenAI.

Bounds remain 8 KiB canonical context, 16 KiB total message content, 32 KiB request and at most eight messages; input reservation remains 17408 tokens. Oversize is a deliberate fail-closed limit, not an automatic repair/replan feature. Existing admissions remain order-only and are never silently converted. No merge or deployment.
