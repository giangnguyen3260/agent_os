# Provider Create bounded acceptance checkpoint

Checkpoint: 2026-11-07. Independent source review plus coordinator-verified execution evidence.

**Verdict:** the baseline bounded Create implementation and regression passed on PR73
`e7760e1`. The strict-UTF-8 corrective criterion passed targeted Java21 acceptance on PR74
`1c639df`. Broad Quality/Docker regression for that correction remains pending. Deployment, the
remaining provider lifecycle and runtime acceptance remain open.

## Evidence and provenance

| Evidence | Evaluated revision and result | Boundary |
| --- | --- | --- |
| Native upload and live browser | Native ACK head `2b8eca7`: [Vault run 42364691052, job 128380164452](https://github.com/giangnguyen3260/agent_os/actions/runs/42364691052/job/128380164452) passed both direct TLS phases and `ProviderCreateVaultHttpIT`: 2 tests, 0 failures/errors/skips | Coordinator read the matching log; this reviewer did not execute the native suite |
| Focused UI browser contracts | Local UI `f085c04` / remote `217f541`: 45 cases passed using disposable Chromium 153, comprising 33 new and 12 retained cases; same UI included in `849831d` | Coordinator execution; synthetic API fixtures, distinct from the real backend browser test |
| Managed integration baseline | PR71 documentation head `9819f56`: all six workflows green; [Vault 42342324047](https://github.com/giangnguyen3260/agent_os/actions/runs/42342324047), [Quality 42342324045](https://github.com/giangnguyen3260/agent_os/actions/runs/42342324045), [Docker 42342324095](https://github.com/giangnguyen3260/agent_os/actions/runs/42342324095) | Earlier accepted baseline, not a substitute for latest-head regression |
| Latest matching regression | `849831d`: [Vault 42364778916 / job 128380425576](https://github.com/giangnguyen3260/agent_os/actions/runs/42364778916/job/128380425576) passed both direct TLS phases and 2 HTTP tests, zero failures/errors/skips; [Quality 42364778889](https://github.com/giangnguyen3260/agent_os/actions/runs/42364778889) and [Docker 42364778979](https://github.com/giangnguyen3260/agent_os/actions/runs/42364778979) pending | Coordinator read matching Vault log; remaining regression pending |

## Completed baseline and corrective gate

PR72 `2b8eca7` completed all six workflows, including Quality 42364691013 and Docker
42364690941. PR73 `e7760e1` completed all three: [Quality 42365148382](https://github.com/giangnguyen3260/agent_os/actions/runs/42365148382),
[Docker 42365148345](https://github.com/giangnguyen3260/agent_os/actions/runs/42365148345)
and [Vault 42365148132](https://github.com/giangnguyen3260/agent_os/actions/runs/42365148132).
Earlier pending rows below are historical checkpoints, not current failures.

The UTF-8 decoder had discarded its checked characters before Jackson parsed the
original bytes with charset detection enabled. A pinned Jackson 2.17.2 probe accepted
four alternate ASCII UTF-16/32 encodings before the fix and zero afterward. The
isolated factory now disables detection and preserves one leading UTF-8 BOM.
Regressions reject both endian forms with/without BOM and check erasure. The combined
Create/Rename body suites passed 16 standalone Java17 JUnit tests locally; this does
not replace matching Java21/native CI. That earlier corrective HOLD is superseded by the targeted PASS below. Existing historical DB/Vault/security evidence is retained.

The first focused Java21 run on the correction passed both body suites and actual
Create HTTP cases but exposed an exact-error-envelope issue in the new Rename
response tests: `NON_EMPTY` omitted the empty `details` map. A property-level
`JsonInclude(ALWAYS)` on the common error DTO preserves the declared `{}` without
changing global mapper settings. Both response suites then passed 14 standalone
Java17 tests locally; that local result preceded the matching Java21 pass below.

## Corrected PR74 targeted acceptance

Implementation head `1c639dfbde44b6ab3275e27dd541e2f68c13bbab` passed
[focused Java21 run 42370723609, job 128398096431](https://github.com/giangnguyen3260/agent_os/actions/runs/42370723609/job/128398096431).
The suite executed 237 tests with zero failures/errors and one intentional H2-only
compatibility skip. Native PostgreSQL completion executed 73 cases and actual
Create HTTP executed two cases successfully. The four Create/Rename body/response
classes executed 30 targeted cases with zero failures/errors/skips. The strict
UTF-8 corrective criterion is PASS. Rename R01–R11 isolated parser/projector/response
acceptance is PASS.

[Vault run 42370723601, job 128398096464](https://github.com/giangnguyen3260/agent_os/actions/runs/42370723601/job/128398096464) also succeeded.
The coordinator read the matching log: both direct TLS/restart phases passed,
and `ProviderCreateVaultHttpIT` ran both browser receipt-recovery and native idle
upload/all-slot recovery cases with zero failures/errors/skips. SQL foundation,
options and guarded publication also passed.
Matching broad regression remains pending in [Quality 42370723622](https://github.com/giangnguyen3260/agent_os/actions/runs/42370723622)
and [Docker 42370723586](https://github.com/giangnguyen3260/agent_os/actions/runs/42370723586).
These results belong to the implementation head above. A later documentation-only
checkpoint requires its own source identity and must not replace that attribution.
Public Rename ingress, coordinator, Create-or-Rename lookup routing and UI remain
subsequent work. Merge, deployment and overall provider/runtime completion remain
open.

## Source-to-test mapping

- `VaultCredentialSecretsVaultIT::realCreateAndReplay` invokes the real journaled
  Vault/command coordinator and its `realConcurrentCreate` and
  `realCreateWithLostCommitAck` cases. These use actual PostgreSQL/Vault and an
  injected acknowledgement failure after commit; they do not establish a physical
  database-network failure. The direct prepare/restart phases above execute this
  harness separately from browser HTTP acceptance.

- `backend/src/test/java/com/agentos/providervault/ProviderCreateVaultHttpIT.java`,
  `browserCreatesAndRecoversCommittedReceiptThroughRealVault`: actual browser login and Create,
  committed PostgreSQL receipt, TLS Vault storage, malformed response after upstream completion,
  explicit own-receipt GET, refreshed persisted list, exactly one browser POST and no secret/key
  persistence in browser storage. Exact journal/receipt/reference correlation, row counts and
  credential readback use real PostgreSQL and TLS Vault.
- The same class, `idleUploadsTimeOutAndReleaseEveryCreateAdmissionSlot`: authenticated malformed
  HTTP and raw-socket 400 baselines; eight admitted partial bodies; saturation 503; bounded server
  termination; reacquisition of all eight slots and completed malformed-body 400 responses;
  six scoped tables remain empty before a final valid 201 and exact Vault readback.
- Its test-only Tomcat `onRead` acknowledgement timing and `Expect: 100-continue` establish that
  each partial request reached body reading after acquiring its permit. The ninth probe follows
  all eight acknowledgements. The production upload timeout remains unchanged.
- `frontend/tests/e2e/provider-create-live.spec.mjs` is the real browser scenario above and has no
  API route mocks. `frontend/scripts/provider-vault-server.mjs` deliberately returns malformed
  JSON after the backend response completes. This proves response-parse ambiguity and receipt
  recovery; it does not prove TCP reset handling.
- `frontend/tests/e2e/provider-connections.spec.mjs` retains list/scope/UNKNOWN contracts and adds
  nine 429 header cases plus 401/403 cases across three viewports. A held POST proves in-flight
  password erasure; virtual 65-second advances prove no automatic POST or receipt GET. A fresh
  explicit resubmission requires a freshly entered credential and new idempotency key; denial unmounts the form.
- `provider-create-wire.ts::retryAfterSeconds` accepts canonical integer delta seconds 1–60 only.
  Missing, zero, oversized, fractional, padded, date and arbitrary values use the fixed fallback.
  `ProviderCreateComponent::knownRejection` renders the parsed number without echoing raw headers.

## Historical security evidence

[PR66](https://github.com/giangnguyen3260/agent_os/pull/66), head `174864b`, previously passed six
workflows, including [completion-boundary run 42150311493](https://github.com/giangnguyen3260/agent_os/actions/runs/42150311493).
`ProviderCreateHttpPostgresIT::sessionAuthorityCsrfAndWireGatesPrecedeTheCommand`
exercises actual login/session CSRF and current authority with a test coordinator.
[PR69](https://github.com/giangnguyen3260/agent_os/pull/69), head `1771a5a`, supplies own-receipt
scope evidence and previously passed six workflows, including
[completion-boundary run 42256219763](https://github.com/giangnguyen3260/agent_os/actions/runs/42256219763).
`receiptLookupUsesCurrentSessionAndOnlyReturnsOwnCommittedCreate` checks historical
configuration, current actor/org authority, explicit missing receipt and malformed/unavailable data.
These are historical evidence references; this checkpoint does not claim they were rerun locally.

## Interpretation limits

- The first idle-upload cycle may terminate with connector-mediated 403, 400, 408, EOF or reset.
  Pinned Tomcat 10.1.31 marks read timeouts as container errors; Boot 3.3.5 dispatches `/error`
  through security. That 403 is an abort outcome, not evidence of an authorization rejection.
  The completed raw baseline, admission acknowledgements, second-cycle strict 400 results and
  persisted final success prevent it from replacing the authentication/slot-recovery checks.
- This is a bounded idle-upload release test, not a total trickle-body deadline guarantee.
- `JournaledVaultSecrets` retains durable preparation rows; uncertain journal transitions keep
  existing PENDING evidence pinned. `ProviderPreparationJournal::observeCreate` requires exact
  scoped actor/key/reference evidence. Neither source review nor these passes establish cleanup
  eligibility or deletion safety; no cleanup completion is claimed.
- Deployment credentials/operations, model discovery, models-list checks, provider generation and
  the remaining lifecycle/runtime gates require their own acceptance evidence.
