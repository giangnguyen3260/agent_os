# Mission graph admission validation checkpoint

21 November 2026, Asia/Ho_Chi_Minh. Stacked on PR122 head `dca273b165a5422aedddcc8afcd50bb8bcfb0303`, which has six successful workflows. PR122 Vault needed one unchanged-head retry after a disposable mount initialization HTTP500; the retry passed.

Implemented locally: strict scoped ADMIN/CSRF admission API, full source snapshot validator, V054 durable admission and Task/Run mapping, atomic RUNNING/control/root/dependent initialization, source-specific historical replay, exact same-plan dependency output proof, dependency-only release and UI confirmation/current Run links/pending exact retry. Start, claim, model authorization and handoff recheck admitted configuration and prerequisite evidence. Legacy single-Task prepare rejects full-plan selection. No auto enqueue or provider call occurs from admission or release.

Local validation:

- 10 graph integration cases on H2 PostgreSQL mode: diamond release, complete immutable replay mapping, missing/legacy/tampered output and UNKNOWN accounting rejection, REWORK/pause behavior, stale/new-Task denial, partial-mapping insertion rollback, disabled runtime/current authority, same-command concurrency, new-key duplicate denial, schema/count validation and CSRF/strict wire format.
- 21 retained backend tests covering runtime persistence, prior plan acceptance and single-Task Mission completion.
- 39 mocked browser cases on desktop/tablet/mobile (30 prior review/acceptance cases plus 9 graph cases): explicit graph confirmation, complete mapping/readiness links, ambiguous response exact retry, authority erasure and malformed-receipt rejection.
- Production frontend build, lint/format, Java compilation/import/format and diff checks.

Native/external checks authored but not yet executed locally:

- `MissionGraphAdmissionPostgresIT` inherits all 10 graph cases; requires native PostgreSQL settings and isolated random schema, with no H2 fallback. Included in Quality's initial publication.
- `ProviderCreateVaultHttpIT.browserAdmitsAndExecutesDiamondGraphWithManagedVaultCredential` and `provider-graph-admission-live.spec.mjs` use real PostgreSQL/session/CSRF/backend, real TLS Vault-managed credential and worker, with a local simulated OpenAI endpoint. Browser accepts the configured plan, admits all four Runs, explicitly Starts/ACCEPTs A/B/C/D, and proves D remains BLOCKED after only B. Java asserts four provider sends, four settled usage records, exact handoff-before-call, three release records and Mission remaining RUNNING. Included in existing Vault workflow; this is simulated provider acceptance, not paid live OpenAI.

Matching-head CI remains required before claiming this candidate verified beyond the local scope. No merge/deployment. Generalized full-graph Mission completion and passing prerequisite outputs into model context remain future work.
