# BA-03 provider connection requirements

Status: requirements brief, not an implemented/frozen API or independently accepted security design. User decision on31 October2026: support all three providers—OpenAI, Anthropic/Claude and Google Gemini—and let the installer choose. No single default provider is selected on the user's behalf. This later user decision supersedes earlier one-provider-first planning suggestions; the independently reviewed lifecycle rules remain unchanged.

## Product scope

One common provider-connection feature supports all three provider types in the same installation. An authorized organization administrator creates an organization-scoped named connection, selects its provider, supplies the appropriate credential and explicitly tests it. An Agent selects a permitted connection and a provider-compatible model. Keep adapter-specific credentials/protocols behind the backend gateway; browser and Agent prompts never call providers directly. Installation setup may configure which provider types are enabled, but choosing one does not require hardcoding the installation to that provider forever.

Support multiple named connections without conflating credentials across organizations or provider types. No cross-tenant secret fallback. Provider/model fields currently stored in Agent configuration are requested settings, not a live connection or effective grant. Introduce an explicit compatible binding/migration contract before mapping them; never silently bind a legacy model label to a credential. No automatic cross-provider/model failover, since it changes data destination and cost.

## UI flow before coding

Connection list → create/select provider → provider-specific credential form → save masked connection → explicitly test → result/detail → rotate or disable. Agent configuration selects an authorized connection first, then its compatible model. All three provider options are visible with actual configured/available state; no fabricated connected indicator, successful test, model list or usage. Include empty, validating, success, timeout/unknown, denied, disabled, stale revision and missing-key states, and1440/768/390 layouts. Agent activation remains blocked until its separate runtime/permission prerequisites exist. Design the exact Figma flow before UI implementation; this brief does not claim Figma completion.

## Security and cost boundary

Human ADMIN manages connection secrets; define scoped read and Agent-use authority separately in the implementation contract. Responses expose only masked metadata and safe validation outcome, never plaintext/ciphertext/raw provider errors. Latest user constraint: provider keys must never be recoverable from the application DB. Store only scoped one-way HMAC verifier and opaque reference there; usable keys live encrypted in an external secret manager with versioning, rotation and safe recovery; passwords/capabilities remain one-way hashed. No credentials in audit, request logs, Trace, browser persistence, source, examples or reports. Disabling a connection stops new authorizations; old in-flight actions retain reconciliation/accounting obligations.

Provider endpoints are server-controlled and validated; do not accept an arbitrary URL that turns a connection test into unrestricted outbound access. Rate/timeout/response-size limits and encrypted transport apply. Exact authentication formats, official endpoint/model availability and adapter error mappings must be checked against each provider's current official documentation during detailed design and implementation, not guessed here.

Separate credential verification from a real model test call. A model test may incur charges and transmit user-selected test content; require explicit action, visible bounded cost/token policy and enforced reservations before any billable call. No automatic test after credential save/rotation or implicit startup probe. Actual returned usage is recorded; missing usage/cost stays unknown. No unlimited retries or silent provider fallback.

## Delivery packages

1. SA/BE: common connection identity/scope/metadata/revision, encrypted-secret lifecycle/key interface, authorization, masked exact DTOs and atomic idempotent commands; deployment key provisioning/runbook.
2. FE/designer: all-three-provider selection and complete create/detail/test/rotate/disable flows in Figma, then responsive Angular implementation using real APIs.
3. BE: three adapter implementations with official protocol references; preserve each provider's request/response semantics behind a common result/usage/failure abstraction without dropping uncertainty.
4. BE/FE: explicit Agent connection/model binding with compatibility and revoked/disabled behavior, preserving existing historical configuration receipts.
5. QC: common authority/tenant/privacy/revision/rollback matrix, per-adapter wire/error/timeout cases, actual approved-credential opt-in smoke tests, real PostgreSQL/browser/restart evidence and independent review. No production secret is required merely to write specs or run local mock protocol tests; those mocks must never be reported as successful live provider connections.

## Acceptance requirements

All three adapters and selection UI are present; installer/admin can choose any without rebuilding source. Scoped keys are stored encrypted and never returned. Creating/switching a connection updates the Agent only through an explicit reviewed command; provider/model mismatch and disabled connection are blocked. Tests show real backend persistence, tenant isolation, revoked-authority handling, safe failure/unknown semantics, no unintended billable calls and restart durability. Live test status is associated with the tested connection/credential revision and expires or becomes stale after rotation; a historical test is not permission or a guarantee that future calls succeed.

Detailed API/schema, key storage deployment choice, provider/model policy, budget ceilings and supported credential modes are next SA/BE/FE/QC design work. This brief records the user's all-three-provider choice; it does not assert those engineering contracts or runtime integration are completed.
