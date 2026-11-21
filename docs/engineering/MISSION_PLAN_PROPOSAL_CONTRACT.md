# Human-reviewed plan proposals v1

Date: 2026-11-21. Contract for the next implementation; this document does not establish implementation, migration, CI, or real-provider evidence.

## Product outcome and scope

An ADMIN can derive a proposed Task graph from one exact accepted planner Run output in a different, completed planning Mission in the same organization and Team. The ADMIN previews the proposal, optionally supplies corrected structured content and human owner bindings, and explicitly applies the reviewed configuration to an empty target Mission. Application atomically creates DRAFT Tasks and their dependencies. The target remains CREATED. Existing configured-plan review, acceptance, graph admission, per-Run Start, output acceptance, and Mission completion remain separate decisions.

This feature performs no model call and grants no execution authority. The planner Run uses the existing explicit, budgeted, reviewed execution flow. Calling its output a planning proposal describes the human-selected artifact; it does not create a new trusted agent role or prove that model instructions were followed. Existing manual Task creation remains supported; applying a proposal to a target containing any manual Task is rejected without modification.

V1 has read-only ephemeral preview and one durable apply command. It has no durable STAGED proposal, autosave, proposal editing history, background planning, retrying model call, or plan regeneration. The immutable proposal snapshot is stored when apply succeeds.

## Inputs and bounds

The source is selected by a canonical UUID Run identifier, not caller-provided output text, Attempt identity, arbitrary URL, or a model-generated Run identifier. The server resolves its Mission, accepted plan revision, latest accepted Attempt and exact verified content. The source Mission differs from the target, belongs to the same organization and Team, and is currently COMPLETED. Source authority requires `AcceptedRunOutputEvidence` through a narrow orchestration bridge: exact current ACCEPT, verified content hash, immutable request/handoff/usage, settled accounting and no unsettled historical attempts, live claims, or unfinished dispatches. Closure should use the existing receipt-backed completion invariants; a legacy COMPLETED label alone is insufficient completion evidence.

The proposal is strict JSON with an explicit schema version and an array of 1..20 Tasks. Each Task has a unique bounded local key, title, description, acceptance criteria, priority, and a list of prerequisite local keys. No owner, UUID, status, Run, provider, budget, lifecycle, acceptance, or execution fields are accepted from proposal JSON. Dependencies refer only to keys in that same complete proposal. Self edges, duplicates, missing references and cycles fail closed. The full graph has at most 100 edges and no Task has more than 20 prerequisites.

Reuse current manual Task bounds: title 255, description 8000, acceptance criteria 4000 Java characters; nonblank required text; priorities LOW/NORMAL/HIGH/URGENT. Local keys are restricted identifiers with a documented bounded grammar (recommended ASCII `[A-Za-z][A-Za-z0-9_-]{0,31}`). Reject malformed Unicode, duplicate/unknown JSON fields, coercions, trailing JSON and unsupported schema versions. No Markdown-fence extraction, prose scanning, partial salvage, truncation or implicit missing values. Source and corrected JSON have explicit UTF-8 byte bounds no greater than existing verified output's 256 KiB; check bounds before parsing. Preserve exact source SHA independently of canonical parsed proposal SHA.

Owner bindings are a separate human command input mapping every proposal local key exactly once to a current ACTIVE agent in the target Team. Duplicate or extraneous bindings and absent/unavailable owners fail closed. A planner cannot assign authority through its text. Canonical binding order is stable and participates in the reviewed fingerprint. All owners are revalidated at apply.

`AGENT_OUTPUT` means the structured proposal is parsed from the exact source content. `HUMAN_CORRECTED` means the ADMIN supplied different complete JSON for review. Both retain the same explicit source identity and proof; corrected content is attributed to the human and never presented as the model's exact output. Invalid source JSON may be shown as data for correction, but never silently treated as a valid graph. The API must not permit an AGENT_OUTPUT label paired with caller-replaced content.

## Preview and apply API

Use an opt-in `agentos.mission-plan-proposal.enabled` flag, default false. Service/controller naming may follow repository conventions. All endpoints require fresh ADMIN authorization; commands use session CSRF and strict canonical UUID/SHA-256 wire validation.

A read-only preview endpoint under the target Mission accepts source Run selection, mode, optional corrected JSON and separate owner bindings. If the bounded input is too large for a safe GET query, use a POST preview endpoint explicitly implemented read-only, with no receipt, audit, status or intake writes. A simple source GET may supply the unmodified output and human review data separately. Never put private source content into URL parameters.

Preview returns source Mission/Task/Run/Attempt/revision/output and provenance hashes, the exact source output as escaped data, mode, complete canonical proposed Tasks/edges, owner bindings/current owner labels, deterministic topological order, target identity and input context, intake version, explicit blockers, `canApply`, and a stable fingerprint. It always reports `executionAuthorized=false`. A preview error or overflow returns no partial graph claimed ready.

Apply takes a UUID idempotency key, expected preview fingerprint and the exact reviewed source/mode/corrected content/owner inputs required to recompute that preview. No caller-authored expected hashes replace server source resolution. No second confirmation of a materially changed proposal is inferred from an earlier fingerprint. If the UI changes any input, it must invalidate the existing preview and require a fresh review.

The fingerprint binds a schema/domain version, organization/Team/target identities, complete current target definition (title/goal/required output/context and relevant configuration), target CREATED status/intake version/open state and absence of setup/runtime, exact source Mission status/completion identity, selected plan/Run/Attempt/revision/output/request/provenance identities, proposal mode and exact content identity, canonical graph, complete owner bindings and relevant current owner state. SHA-256 covers canonical server serialization; no client-selected field omission or current-clock value makes equivalent previews unstable.

## Atomic application and concurrency

Use the existing organization-first authorization lock, then deterministic parent/owner/source lock order consistent with creation and runtime operations. Re-read current authority before writes. Under that transaction, recompute the exact preview and source proof and require a byte-equivalent fingerprint and no blockers.

The target must be CREATED, Team and organization ACTIVE, intake open and version below MAX. It must contain zero Tasks and dependencies, no accepted plan or full-plan selection, no graph admission, no Runs or execution controls, and no prior successful proposal application. Do not silently delete, merge with, or replace target configuration. Intake pause/resume, manual Task creation, other apply commands, owner changes, source review/provenance changes, and acceptance/admission must participate in the same locks/current-state validation so incompatible commands cannot both win.

Generate all Task UUIDs server-side, insert the entire graph as DRAFT, then edges using the local-key-to-UUID mapping. Do not call the public per-Task create command in a loop: its individual receipts, existing-dependency checks and multiple intake increments do not represent one reviewed bulk operation. Share or reproduce its validation deliberately. Increment target intake version exactly once with a guarded CAS and leave Mission status CREATED. Task revision defaults follow existing manual creation. Do not create accepted plans, controls, claims, budgets, dispatches, provider calls, or output-review decisions.

Store immutable canonical proposal/source proof/owner bindings and resulting local-key-to-Task mapping, with scope and schema identity and SHA-256, in the same transaction as Tasks, edges, single intake CAS, and audit. Audit identifies the importing human and source identity without logging raw source/proposal text. Any insert, receipt, audit, owner check or CAS failure rolls back the entire operation. The receipt must permit a reviewer to reconstruct exactly what configuration was imported and its human/agent attribution.

## Schema and historical replay

V057 should introduce a dedicated proposal application receipt/snapshot, with scoped foreign keys to target Mission, source Run/Attempt and created Tasks as appropriate, actor/key uniqueness, target application uniqueness, and immutable local-key mappings. Store original response and canonical command digest as well as snapshot SHA; validate scoped mappings and stored digest on read/replay. Prefer separate normalized mapping rows if that provides enforceable cross-organization/target constraints. Do not represent this import as an accepted mission plan.

Historical replay follows fresh current ADMIN/account/credential authority and exact canonical actor/key/body matching, before mutable target/source lifecycle gates. Same actor/key/exact command returns the original verified receipt even after later manual edits, intake changes, plan acceptance, execution, source archival or feature-policy changes allowed by routing. It does not recompute or reapply a graph, generate new IDs, increment intake, rewrite historical attribution or enqueue work. Changed scope/body under the same key conflicts. A new key against a nonempty or previously applied target conflicts. Feature-disabled routing may deny requests; no hidden fallback applies a proposal.

## UI and user interpretation

Provide a target-Mission proposal review page reachable from setup. Let the ADMIN choose a completed source planning Mission/Run, inspect exact accepted output, see source proof identity and proposal parse errors, and enter corrected JSON explicitly labeled HUMAN_CORRECTED when needed. Supply a documented schema/example that can be included in the planner's ordinary Task prompt without automatically starting a model.

Show every proposed Task, owner binding and dependency before the apply button. State that applying creates DRAFT configuration and that configured-plan acceptance and execution still require explicit later actions. Present blockers clearly, retain exact pending command bytes for ambiguous network retries only in component memory, recheck workspace authority on focus/retry and expire stale previews. Treat all source and proposal text as escaped data; do not interpret HTML, external URLs, commands or tool instructions. Do not persist private payloads in browser storage.

After success, link the immutable import receipt and created Tasks and lead into the existing configured-plan review. Do not label a proposal as accepted, execution-ready, or an automatically generated/accepted plan. Existing manual Tasks remain visible and editable by their existing flows.

## Definition of done and evidence

- A controlled accepted planning output yields a reviewed 3- or 4-Task DAG, human owner bindings and exactly one atomic import; graph review reads all created configuration and later acceptance/admission remains unchanged and separate.
- AGENT_OUTPUT is exact-source-derived; corrected invalid-output recovery is attributed HUMAN_CORRECTED. Unknown/duplicate/coerced fields, fences/prose, hostile text, malformed Unicode, byte limits, 20/21 Tasks, 100/101 edges, missing/self/duplicate dependencies and cycles are exercised without partial application.
- Source wrong organization/Team/Mission, target=self, legacy/unfinished/stale source, hash/provenance/accounting tampering, UNKNOWN/HELD history and revoked authority fail closed. Source proof is revalidated between preview and apply.
- Empty-target guard covers manual Tasks, stray edges, prior plans/selections/admissions/Runs/controls, closed intake, terminal/max versions and parent availability. Race manual Task creation versus apply, same/different idempotency keys, intake pause/resume and owner changes have at most one compatible winner.
- An actual database failure after initial Task/edge insertion and a receipt/audit failure prove full rollback, including the single intake increment. Exact replay after later lifecycle changes returns original IDs and no additional writes; changed command conflicts.
- Native PostgreSQL suite covers the same transactional/schema behavior with isolated fail-fast database setup. Mocked browser cases cover complete review, owner binding, correction attribution, staleness, denied authority and exact retry. Controlled Vault/backend browser evidence derives JSON from an actually reviewed existing runtime output and proves no extra provider send during preview/apply.
- Report source inspection, local tests, native CI, controlled provider fixture and real paid-provider evidence separately. This feature introduces no new paid provider run or publication authorization.

## Implemented v1 boundary

The source reader specifically accepts graph-admitted, receipt-backed graph-COMPLETED Missions using the existing full-manifest verifier. A single Task is supported when it followed graph admission/completion; historical legacy single-Task closure is not an import source. Source and corrected JSON are each bounded at 64 KiB; the expanded preview/immutable snapshot has a separate 128 KiB cap, so a valid large JSON document may still need to be shortened before review. No truncation is applied.

The source and apply records redact diagnostic text. The source original, exact submitted correction, canonical graph, human owner bindings and immutable normalized Task mapping are retained at apply. There is no persisted pre-apply proposal lifecycle. Historical receipt replay validates stored hashes, scope and normalized mapping without rebuilding live source or target state.

Feature flag: `agentos.mission-plan-proposal.enabled=true` (default off); sources additionally require graph review/acceptance/admission/completion capability. All user POSTs require ordinary session and CSRF. The import is available only to current ADMIN users.
