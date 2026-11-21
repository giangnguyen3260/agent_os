# OpenAI single-Task execution flow

## Delivered behavior

Current ADMIN opens a READY text Task with no dependencies in an ACTIVE Team and RUNNING Mission, reviews the current owner/model/connection, exact prepared text request, token ceiling and reserved budget, then confirms Start. The POST explicitly accepts a single-task plan snapshot and commits the Run, scoped Start receipt, immutable prepared body and dispatch together. GET preflight never creates a plan or Run. A Task must have an ACTIVE owner, structured current model binding/grant and usable OpenAI connection. General AI planning, dependency-graph acceptance and Mission completion remain separate features.

The internal worker revalidates issuer/account credential version, accepted Task/owner/dependency revisions, prerequisites, lifecycle, current model grant and credential revision. It issues one fenced Attempt, reserves four scoped budget/capacity buckets, authorizes the body digest and commits exact-body handoff before transport. A replayed Start returns the receipt after fresh authority checks; a competing key cannot enqueue the same Run revision. A new Start after REWORK creates another Attempt under the retained Run. Pending commands are retired without a provider call when installer model/rates change or token/cost limits are tightened; Run revision advances so a fresh proposal and key are required.

OpenAI Chat Completions transport sends a single text-only request to fixed `https://api.openai.com/v1/chat/completions`. It sets `max_completion_tokens`, disables streaming/storage and accepts no tool messages or calls. It pins public DNS answers into the connector, uses normal TLS verification and disables redirects, proxies from system configuration, automatic retries, cookies and decompression. Limits: 16 KiB input text, 32 KiB serialized request, 1 MiB response, 256 KiB output text, 4096 completion tokens, at most 30 seconds transport time. Only a complete `stop` result with bounded integral usage and matching token sum can become a verified output. Duplicate JSON keys, trailing JSON, refusals, tool calls, truncation and invalid usage are unconfirmed outcomes.

Successful usage accounting, reconciliation evidence, output integrity, Task/Run review transition and dispatch completion commit together. Costs are conservative accounting from actual reported token counts and installer-provided rates for one allowlisted model, rounded up to micro-USD. They are not a provider invoice; discounted cache pricing is not modeled. The worker holds the upper reservation before a provider call. Rate and model configuration must be reviewed by the operator. Preflight checks that all four active budget/capacity configurations exist and can cover the reservation; the worker rechecks actual admission and concurrency before sending.

The reviewer opens the Run, reads the exact output and confirms ACCEPT or reasoned REWORK. New single-task Starts allow at most one rework. REWORK returns Run to CREATED and Task to READY; a separate explicit Start performs the next attempt. ACCEPT completes Task/Run; it does not declare the whole Mission complete.

Post-delivery uncertainty keeps an UNKNOWN Attempt/budget and BLOCKED Run with OUTCOME_UNKNOWN. It never automatically resends. Pre-send failures release known-unused reservation and leave a visible failed/blocked attempt. A worker crash leaves a fenced lease for the existing expiry sweeper to resolve; claimed dispatches are not automatically recycled. There is no public arbitrary evidence/reconciliation ingress. Operator reconciliation UI and automatic recovery are outside this first flow.

## Opt-in deployment

Default is disabled. Runtime requires managed Vault configuration already used by Provider Connections, enabled Model Discovery/grants and the lease-expiry sweeper. Enable only in a reviewed environment:

```yaml
agentos:
  execution:
    openai:
      enabled: true
      model: "<installer-allowlisted-chat-model>"
      max-output-tokens: 512
      input-rate-microusd-per-million: "<positive-integer>"
      output-rate-microusd-per-million: "<positive-integer>"
      maximum-microusd: "<positive-cap-at-least-reservation>"
```

The reservation is ceil((17408 × input rate + output ceiling × output rate) / 1000000). Four active budget buckets and capacity policies (ORGANIZATION, TEAM, AGENT, MISSION) must exist. They are enforced at worker admission; absence does not authorize a provider call. `poll-enabled` defaults true; disabling it queues Starts without automatically executing them and is intended for controlled test/maintenance environments. Credential metadata lookups use the managed Vault runtime's dedicated writer datasource and transaction proxy. The restricted Provider List reader receives no SELECT on `provider_credential_versions`; the enabled-runtime browser gate asserts that separation. Credentials come only from the exact tenant/connection/revision in Vault. No raw key or endpoint is accepted from an API caller.

Start endpoints:
- GET/POST `/api/v1/organizations/{org}/teams/{team}/missions/{mission}/tasks/{task}/start`: first single-task acceptance and Start.
- GET/POST `/api/v1/organizations/{org}/execution-runs/{run}/start`: subsequent Start of an accepted CREATED Run.
- POST body `{expectedRevision: decimal-string, idempotencyKey: UUID, fingerprint: SHA-256}`; first Task Start requires revision `"0"`. Fingerprints bind the reviewed body, scope, configuration and rate/reservation settings. Server revalidates rather than trusting the fingerprint as authority. POST requires the existing session CSRF token.

The ADMIN-only proposal displays the escaped prepared request without inserting connection credentials; its DTO string representation is redacted. The UI preserves an exact Start key/body after an ambiguous response. Explicit retry can only resend that command. It clears private proposal/pending state on actor or route changes and rechecks authority before POST. The Run link is always the place to verify current execution state.

## Verification and remaining release gates

Local Java tests cover a fresh READY Task without an existing Run, accepted snapshot/Start receipt replay, changed-fingerprint, changed-input and revoked-grant denial, issuer revocation before handoff with zero sends/zero charge, actual HTTP against a loopback OpenAI-compatible server, budget settlement, output publication, REWORK/new Attempt/ACCEPT, malformed response UNKNOWN/retained hold and no automatic resend. Grants and credential use are controlled doubles in that test; it is not a real OpenAI call or managed Vault acceptance.

Four transport tests exercise request bytes, parsing, unsupported/ambiguous output and redirect/no-retry behavior. A Spring-enabled HTTP test checks assembly, authentication, ADMIN, CSRF and safe conflict responses. Browser tests across desktop/tablet/mobile exercise the complete UI loop, exact retry after malformed Start acknowledgment and MEMBER denial, with intercepted API fixtures. The existing recorded-output real-backend browser suite remains retained separately.

Open gates before production rollout: matching-head full CI, including native PostgreSQL recovery and managed-Vault browser acceptance described below; actual OpenAI acceptance using an operator-configured credential. No merge, deployment, invoice reconciliation or real-provider execution is claimed by local tests.

API wire reference: https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create

Code quality explicitly selects `OpenAiExecutionPostgresIT` using its existing disposable PostgreSQL service. The test creates/drops a random isolated schema, runs all migrations, and reuses the controlled-provider flow with a same-key Start race and competing worker instances. It also checks a committed HELD reservation and exact committed handoff digest from the provider server thread before responding. It is not silently skipped and requires explicit PostgreSQL environment settings. The recovery cases rebuild workers against committed state, crash before/after handoff and expire the abandoned claim. On native PostgreSQL, a child JVM also completes an already queued command; a second child is forcibly terminated only after the loopback provider observes the committed handoff and HELD reservation. Rebuilt workers must not resend that command; expiry retains UNKNOWN, blocks review and rejects the old fence. The controlled pre-send crash settles zero instead. These native process assertions require matching-head CI; local H2 execution alone does not prove them.

`ProviderCreateVaultHttpIT.browserExecutesReworksAndAcceptsTaskUsingManagedVaultCredential` enables the real runtime assembly, uses managed Create to store a synthetic credential in disposable TLS Vault, and drives a real signed-in browser through Task Start, worker output, REWORK, a second Start and ACCEPT. No browser API interception, grant mock or credential mock is used in that case. The provider alone is redirected by test reflection to a loopback HTTP server; there is no production endpoint override. The server verifies the exact Vault-created bearer and committed body digest/HELD reservation before responding. PostgreSQL must contain two completed dispatches, usage records, settled reservations and the two review decisions. Execution-related paths trigger the existing Vault workflow explicitly. The live case skips in ordinary browser runs and must execute successfully in that dedicated workflow. A real operator-credential OpenAI call is still separate.


## Operator-credential execution acceptance

PR #119 head `4999f939d6c7d043afef4ab68a63af71abe3d3fb` passed all six matching workflows, including Docker/browser and managed TLS Vault. The pending-CI wording above describes the original gate; that baseline CI gate is now closed. The actual OpenAI execution gate remains open until the opt-in case below succeeds. Source changes require their own matching-head CI.

The disposable Vault script now supports a separate paid acceptance selection. Default CI never opts in. Run from the repository root with Java 21/Maven, Docker Compose, OpenSSL, jq, the built frontend, installed Playwright Chromium and public HTTPS egress to `api.openai.com`. Set `AGENTOS_VAULT_IMAGE` to a reviewed immutable Vault image digest, as in the existing Vault workflow. Use a dedicated operator test key in a private readable file outside the repository; do not paste it into the shell command, chat, logs or GitHub Actions.

```bash
export AGENTOS_OPENAI_EXECUTION_LIVE=true
export AGENTOS_OPENAI_EXECUTION_KEY_FILE=/absolute/private/path/openai-test-key
export AGENTOS_OPENAI_EXECUTION_MODEL='<reviewed-chat-completions-model>'
export AGENTOS_OPENAI_EXECUTION_INPUT_RATE='<positive-integer-micro-USD-per-million-tokens>'
export AGENTOS_OPENAI_EXECUTION_OUTPUT_RATE='<positive-integer-micro-USD-per-million-tokens>'
export AGENTOS_OPENAI_EXECUTION_MAXIMUM_MICROUSD='<positive-integer-at-most-100000>'
bash scripts/check-vault-local.sh --openai-execution-live
```

The rate/cap placeholders must be replaced with reviewed integers before execution. The test uses 512 completion tokens and the runtime's conservative 17,408-input-token reservation. The cap must cover that reservation and cannot exceed 100,000 micro-USD ($0.10) per attempt; there are at most two attempts, for a maximum $0.20 of configured accounting. This is a local accounting bound using operator-supplied rates, not an independent provider billing limit or invoice reconciliation. Configure provider-side spending controls separately when needed.

This selection creates the supplied credential through managed Create into disposable TLS Vault, retains the production fixed HTTPS transport unchanged, and drives real browser Start → worker → REWORK → explicit second Start → ACCEPT. The browser checks the selected model and nonempty returned output. PostgreSQL must show the completed Task, two ordered review decisions, two finished dispatches, two handoffs, two usage records and two settled reservations within the cap. Synthetic tests continue to require the exact fixture output and exact cost. No automatic provider retry, redirect, endpoint override, tools or streaming is added.

The model-discovery snapshot, grant, Task and budget policies are seeded acceptance fixtures. This case proves execution with the managed credential; it does not prove real catalogue discovery, general plan generation, Mission completion or deployment. The loopback provider must receive zero requests in the real-provider case. The existing controlled-provider case remains a separate regression and observes committed handoff/budget before responding; that server-side observation is not claimed for OpenAI.

The script selects only `browserExecutesReworksAndAcceptsTaskUsingRealOpenAi` after disposable Vault setup; it does not also run the synthetic execution case or the optional Access Check provider call. Ordinary tests skip the paid method. Do not enable JUnit/Playwright retries for this case. An uncertain outcome or failed acceptance is not permission to rerun: the fixture is disposable, and a new invocation creates fresh commands that can incur new charges. Review provider usage before an operator chooses another invocation. Never report a skipped or unexecuted case as a real-provider pass.

Keep the test source SHA, JUnit result, matching CI links and operator run date with release evidence. The disposable secret store is destroyed by the script's EXIT cleanup. No real-provider pass has been recorded by this implementation.
