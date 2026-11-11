# Rename UI PM acceptance checklist

Candidate only, 10 November 2026. All execution rows remain pending until exact
head/run evidence is inspected. Frozen semantics derive from the Rename transport
contract and existing Figma handoff; this checklist adds no unrelated gates.

| Check | Required evidence | Current status |
| --- | --- | --- |
| Capability | Header true enables action independently of Create and disabled adapter/state; missing/false/malformed hides it; native flag assertions | Source in progress; execution pending |
| Review | Actual selected revision retained as int64 string, normalized different name, current/proposed name shown, explicit confirm and cancellation | UI integration pending |
| Historical wire | Exact envelope/receipt/snapshot allowlists, matching actor/org/target/revision/name, adjacent revision without overflow, UTC6 ordering and unchanged metadata | Strict validator source present; execution pending |
| Known rejections | Stale/NO_CHANGE refresh before new review, canonical 1–60 Retry-After, authority loss clears state | Mocked contracts authored; execution pending |
| UNKNOWN | Manual GET only, null unresolved, malformed/mismatched receipt rejected, no resend/poll/automatic retry | UI integration and execution pending |
| Scope lifecycle | Org/actor/selection changes and unmount cancel stale work and clear pending state; no local/session storage persistence | Independent final source/browser review pending |
| Responsive | Existing Forest Rename/review/UNKNOWN patterns at 1440/768/390, usable labels/focus and no horizontal overflow | Browser checks pending; no new Figma claim |
| Managed browser | Actual HTTP/PG normal Rename then second committed Rename with malformed response, manual historical recovery and readback; exact request counts/no mock API | Managed test authored by separate agent; execution pending |
| Compatibility | Existing Create/list behavior, native capability assertions and matching focused/Vault/Quality/Docker evidence at candidate head | Execution pending |

The current slice deliberately provides no exact resend control. Do not mark it
missing solely because the broader metadata recovery contract permits one.
Historical receipt confirmation and current list readback are distinct facts.
No provider call, generation authorization, merge or deployment follows from UI success.
