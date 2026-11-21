# Accepted prerequisite output context for explicit Run Start

Date: 21 November 2026 (Asia/Ho_Chi_Minh). PM/BA contract following admitted graph execution and completion. A local candidate now implements this contract. Matching-head native PostgreSQL and Vault/browser context evidence remains separate from local validation and does not imply a paid provider call, merge or deployment.

## User outcome and supported boundary

For a Mission admitted in required-context mode, an ADMIN opening an eligible dependent Run's Start preview sees the exact accepted output text of every **direct prerequisite** in the model request. The human explicitly confirms that precise request. The worker sends the stored bytes only after fresh scope, dependency, model, credential, budget/capacity and context checks.

This adds actual content collaboration to the existing dependency ordering. It does not introduce AI planning, tool execution, broader Memory/Knowledge, automatic Start/enqueue, provider retries, extra providers or a new network route. Current Task instructions and the existing OpenAI text-only transport remain in force.

Only direct edges of the pinned admitted plan select sources. Do not recursively insert ancestor outputs, unrelated completed Tasks, another plan's version, Team history or model-selected context. A direct predecessor's output may already reflect its own inputs, but the context builder does not expand that lineage recursively.

## Immutable policy at Mission admission

V056 introduces `mission_graph_admissions.dependency_context_required boolean not null default false`. Existing admissions remain explicit **order-only**. A new admission pins the value from `agentos.execution.dependency-context.enabled` at admission time; the feature flag defaults false.

- Admission assessment/fingerprint, durable receipt and UI bind and display the proposed/stored mode. A flag change between admission preview and POST requires fresh assessment rather than silently selecting another mode.
- The mode is immutable after admission. First Start, a later flag change or a different replica does not convert an existing order-only Mission. Conversion/replanning is separately scoped; no partial adoption or migration of earlier effects is inferred.
- Historical admission replay exposes the stored mode, not the flag's current value, and never updates policy.
- ORDER_ONLY admissions retain their existing no-context behavior even if the flag later turns on. This remains visibly distinct from content collaboration.
- REQUIRED admissions need the enabled context capability for every **new** preflight/Start, claim, authorization and handoff, including root Runs. When disabled or uninstalled, they fail closed instead of silently building an order-only request.
- Any dispatch already carrying context-bound evidence must retain its required policy checks; a flag change cannot reinterpret its body as a legacy request.

Execution replicas must use coordinated installer settings. A flag is a capability gate; persisted policy determines what the particular Mission requires. Runtime/model/budget controls continue to apply independently.

## Exact accepted source proof

For each direct predecessor of the target Task:

1. Resolve its one pinned admission mapping in the same organization/Team/Mission/accepted plan revision. Do not select a globally latest Run, another Mission's identical plan number or a Task label alone.
2. Require the current latest successful Attempt and exact ACCEPT/reconciliation/output identity, including the existing source Run revision relationship.
3. Reuse `AcceptedRunOutputEvidence` for complete settled authorization/handoff/request/dispatch/usage/charge provenance and its provenance SHA. No historical unresolved Attempt, live claim, HELD/UNKNOWN reservation or unfinished dispatch may remain for that source.
4. Load the exact verified accepted text for that Run/Attempt/output hash. Check nonblank valid UTF-8, the 256 KiB stored-output ceiling and SHA-256 before using it. This stored-output ceiling does **not** mean the whole output will fit a downstream context budget.
5. Include every direct predecessor exactly once in deterministic Task-ID order. Missing, stale, cross-scope, tampered or unavailable proof makes the entire context unavailable; never filter that source out and continue.

The source set and identities are server-derived from the admitted graph. Caller-supplied source IDs, text, hashes, alternate endpoints or fabricated proof never authorize data selection.

## Model message construction

Use one dedicated canonical JSON **user-data envelope** for the complete direct-source set, distinct from both the trusted system message and the current Task's user instructions. This avoids a separate message for every predecessor and an arbitrary six-predecessor fan-in limit.

The envelope is a known versioned server schema. Each entry contains exact source Task/Run/Attempt/revision/output/provenance identity and the source text as a JSON string. Deterministic field/source ordering and exact string preservation define the bytes. Do not trim, normalize, summarize, relabel, rewrite or interpolate source text into model control fields.

Recommended message order is: existing system instructions with a dedicated fixed data-handling instruction; the separate predecessor-data user message; the unchanged current Task user message. The data-handling instruction identifies accepted outputs as reference data, not authority to replace roles, request tools, expose credentials or change server permissions. Source text is never promoted to system/developer/assistant/tool messages. JSON-looking source text remains a string inside the user-data envelope, not additional messages.

This is a data/authority boundary, not a guarantee that a model cannot be influenced by reference text. Effective model/tool/resource authority remains entirely server-side and no Tools are enabled.

Required roots persist a canonical **empty source manifest**. They do not fabricate predecessor text. The builder may retain the base system/current-Task messages without a predecessor-data message for this zero-source case; that root encoding is fixed by the stored context schema/request hash, not caller choice. Roots still carry required-mode context evidence and never fall through to legacy dispatch policy.

## Bounds and budget invariant

Retain the existing transport and storage ceilings:

| Boundary | Limit |
| --- | --- |
| Canonical context envelope content, including identity/JSON framing | 8,192 UTF-8 bytes |
| Total content of all model messages, including system/current Task/context | 16,384 UTF-8 bytes |
| Exact serialized model request body | 32,768 bytes |
| Model messages | At most 8; one context envelope avoids per-source message growth |
| Completion ceiling | Existing configured limit, at most 4,096 tokens |
| Verified input usage ceiling and reservation basis | Existing 17,408 input-token ceiling |

The byte budget is measured after constructing the canonical envelope, including metadata, delimiters and escaped source strings. The total message/body checks apply separately because JSON serialization adds framing/escaping. The envelope budget is not permission to discard the current Task's input. A node with many tiny predecessors can still exceed the framing budget and must be rejected coherently.

There is **no truncation, partial source inclusion, hidden summarization, automatic second call or fallback to order-only mode** for a required Mission. Report a stable safe context-too-large/unavailable reason before a new dispatch is accepted or any provider call occurs. Do not increase the body/DB check to 64 KiB, lower the reservation basis to 8k, or infer an exact token count from UTF-8 bytes.

The existing reservation formula, installer rates/cap, four-scope budget/capacity admission and verified usage settlement remain unchanged. The complete context-bearing request must fit the current conservative envelope. Actual usage outside verified bounds retains the existing unconfirmed-outcome/accounting behavior; configured accounting is not provider invoice reconciliation.

An oversized already ACCEPTed predecessor can make a required-context child unsupported. This first version does not reopen the source Task, change immutable policy, summarize it automatically or invent a recovery transition. Explain the blocked condition; a new reviewed Mission or separately designed recovery is needed. Do not claim every admitted DAG is guaranteed to complete. A future source-ACCEPT feasibility gate could preserve the opportunity for human REWORK, but it is not implicitly added by this contract.

## Per-dispatch immutable context evidence

For every new REQUIRED-mode dispatch, atomically persist a versioned immutable context record alongside `execution_start_dispatch` and `execution_openai_requests`, including roots with no direct sources. Bind:

- Exact target organization/Team/Mission/Task/Run, admitted plan and required policy/schema.
- Complete deterministic direct-source metadata: source Task/Run/Attempt, source Run revision, output SHA and settled provenance SHA.
- Canonical context-manifest JSON and its SHA-256, plus the exact context-message/body binding needed to prove those accepted bytes appear in the stored model request.
- Exact request SHA and request identity; the existing immutable request body retains the actual source text sent to the model.

The metadata manifest does not need to duplicate raw outputs already present in the immutable request. New records and diagnostic string representations must not leak context/request text, credentials or arbitrary source content to logs/errors/audit payloads. Use scoped bounded read responses and no-store.

Preflight returns the exact final request preview, stored-policy mode, complete source metadata/manifest hash and unchanged accounting ceiling. Its fingerprint binds Run revision, scope, current selected context mode, exact request bytes, context manifest identity and existing model/credential/rate/reservation settings. It is a concurrency assertion, never source authority.

POST Start rebuilds/validates the complete current proposal under organization-first serialization and matches that fingerprint. The dispatch, request, context record and original Start receipt commit together; failure anywhere rolls back all of them. No context policy is selected or changed by GET or Start.

## Fresh guards and historical replay

At Start, claim, model authorization and handoff, verify required policy/capability, immutable stored manifest/request integrity and the complete fresh direct-source proof. Compare exact source identities, revisions, output/provenance hashes and canonical source text/context binding against the dispatch's stored snapshot. Existing graph-input/edge/selection guards still apply. Do not only check that prerequisites remain labeled COMPLETED.

The claim check must be dispatch-specific and occur before creating an Attempt, changing Task/Run to RUNNING or reserving any resource. An authorization/handoff check is tied to the same dispatch/action and fenced Attempt. No network call occurs until the exact stored request handoff commits. A stale context is denied, not refreshed into another body under the old key.

The worker sends the request bytes stored for the human-confirmed dispatch. It never rebuilds a queued request using newer output text, silently substitutes a different source Attempt or creates a fresh provider request after restart. Existing no-resend and lease/fencing/accounting rules remain intact.

A matching historical actor/key/body Start replay follows fresh current authorization and immutable stored-record validation, then returns the original receipt without rebuilding live context, enqueueing another dispatch, changing policy or resetting state. It can acknowledge historical work while the feature is disabled because replay grants no new effect. Changed key/body/scope conflicts follow existing semantics.

Old ORDER_ONLY dispatches remain order-only under their immutable admission policy. A REQUIRED dispatch missing its mandatory record or failing a context capability/integrity check cannot execute. Known-unused queue rejection/zero settlement and post-handoff UNKNOWN retention follow existing trusted runtime paths. Turning the feature back on is not permission to resend an uncertain or already handed-off action; no new recovery/retry mechanism is added.

## UI behavior

Mission admission displays whether the new graph is order-only or requires accepted direct-output context, and preserves that mode in historical receipts. Run Start displays its pinned mode, exact complete request preview and source count/identities/hash. It clearly separates predecessor reference data from current Task instructions and model/connection/cost information.

Preflight fails safely if a required source is not accepted/current, the context cannot fit, policy is unavailable or any existing model/budget/control condition fails. Do not offer a hidden checkbox to omit required sources or switch policy. The human may inspect accepted source output through the existing scoped read surfaces.

Validate response scope, policy, source membership/uniqueness, hashes and canonical revision shapes before enabling confirmation. Render source/request text escaped. Clear private context on authority/actor/route changes and denied/malformed responses; use bounded requests and generation fencing. Keep only the exact pending command in memory after an ambiguous Start response for explicit retry; never save raw context in browser storage or create replacement commands automatically.

## Required Done evidence

- A newly admitted REQUIRED diamond: root A stores an empty manifest; B and C requests contain the exact accepted A output; D contains exact accepted B and C outputs and does not separately inject A. Four explicit Starts still produce four provider calls, not automatic extra calls.
- A controlled provider observes actual source text in the correct dedicated user-data envelope and unchanged current Task instructions, exact request hash and committed handoff/HELD budget before response. UI/backend/PostgreSQL/Vault evidence must not be replaced by route-mocked assertions alone.
- Prove complete direct-source membership, deterministic ordering/hash and distinct source content across diamond branches. JSON/instruction-looking output remains user data and cannot create model messages/roles/tools or new network routes.
- Admission mode/fingerprint race, stored-mode historical replay, old admissions remaining order-only when the flag turns on, REQUIRED mode refusing new Start/claim/auth/handoff while off, and no default-policy downgrade after restart.
- Exact envelope 8 KiB, total text 16 KiB and body 32 KiB boundaries; multibyte/escaping/framing and many predecessors; oversized whole-source failure with zero dispatch/provider send and no truncated preview.
- Source output/provenance/revision/config/selection changes between preflight→Start→claim→authorization→handoff; missing/corrupt/foreign context record, source hash/text mismatch, rollback of request/context insertion, stale fence and competing workers.
- Same-key replay after changed/live source conditions uses historical stored bytes without requeue/rebuild; wrong body/scope/key is denied. Known-unused versus post-handoff uncertainty remains distinct and never automatically resends.
- Retained order-only/single-Task runtime, current authority/CSRF, budget/capacity/usage/no-secret tests; responsive preview/blocked/ambiguous exact retry/authority-loss tests.
- Matching-head source/build/lint/CI, explicit isolated PostgreSQL native verification and real-backend browser controlled-provider acceptance. Real OpenAI execution and provider billing reconciliation remain separate evidence.


## Concrete implementation checkpoint

V056 adds the immutable admission policy bit and `execution_dependency_contexts` with composite dispatch/organization/Run and admitted-scope foreign keys. The implemented canonical context envelope contains bounded source metadata **and text** (at most 8 KiB in total); this intentionally duplicates that small data envelope from the immutable request to make exact-message verification direct. Required roots store an empty envelope. Envelope/input/capture diagnostic strings are redacted.

Run preflight exposes the envelope digest and source count alongside the exact final request preview. A non-null digest denotes REQUIRED mode; ORDER_ONLY Run preflight has null digest/count zero. New Start fingerprints include that digest. Fresh worker guards bind the incoming model authorization SHA and handoff-body digest to the verified immutable request SHA, before resource reservation or transport. Historical Run Start replay validates stored integrity without rebuilding current ancestor context.

The current fixed order is system instructions, then the canonical prerequisite user-data envelope when nonempty, then the current Task as final user message. Limits and reservation are unchanged. A context that cannot fit fails closed before dispatch; existing accepted outputs are never cut or silently omitted.
