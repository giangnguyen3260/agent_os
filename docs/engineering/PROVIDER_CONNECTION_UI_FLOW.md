# BA-03 provider connection UI flow

Design scope: organization-scoped connection management for OpenAI, Anthropic/Claude and Google Gemini; installer controls available provider types without recompiling. Forest light palette, Inter, readable dark text,44px actions,1440/768/390 responsive layouts. This is design, not implemented Angular/API functionality. Model generation, actual cost-bearing run tests, effective Agent binding and activation remain separate gated work.

## Navigation and routes proposed

Add an ADMIN-only Models destination `/workspace/:id/model-connections` to every implemented workspace desktop/compact shell when the feature ships. A MEMBER sees no management link; direct route/API checks still deny. Existing seven destinations and unsaved/UNKNOWN guards remain intact. Detail `/workspace/:id/model-connections/:connectionId`; create `/new`; rename/rotate/state/check as scoped detail workflows with native Back and dirty/unknown protection. These routes are proposed, not currently live. Backend paths and exact DTOs are owned by PROVIDER_CONNECTION_API_CONTRACT.md; reconcile before coding.

| View | Content / action | State and privacy requirements |
| --- | --- | --- |
| Empty list | Provider choices and “Thêm kết nối”; explain keys belong to current organization | Genuine zero; no sample connected record |
| Populated list | Name, provider, enabled/disabled and masked credential availability from List; open detail for separately loaded check revision/time | No observation invented from List; loading/empty/paging/error separate; no search/filter in this first list contract |
| Create | Name, one of3 provider options, hidden credential; optional provider-specific selectors only where supported | No preselected provider; no actual key examples, arbitrary endpoint or automatic outbound test |
| Save review | Provider/name, “Khóa mới đã nhập” marker, organization, explicit save | No secret or suffix in review; no key persistence across reload/navigation |
| Detail | Masked credential marker, configuration/credential revision, enabled state, historical check | No key reveal/copy; no automatic provider call on GET |
| Rename | New name and exact expected revision, review/save | No implicit rotation or provider switch; provider type immutable |
| Access check | Explicitly start access-checks for current credential revision, observation only; catalogue discarded, no prompt/generation | Queued/running/success/failure/UNKNOWN/superseded; no server cancellation; leaving/stop-polling does not cancel accepted work; bounded budget/rate/time/response limits; historical not runtime eligibility |
| Discover models / result | Separate explicit model-discoveries action, queued state, actual frozen entries or genuine empty, check time and COMPLETE/TRUNCATED state, fixed20 paging | Does not follow access check automatically or overwrite currentObservation; no hardcoded model IDs or assumption of runtime capability |
| Rotate key | Enter new hidden key, review impact and explicit rotation | Invalidates historical currentness; old access-check cannot overwrite new revision; save does not test |
| Disable/enable | Review effect and current revision, explicit state command | Prevent new authorizations when disabled; do not claim provider key revoked remotely or in-flight work cancelled |
| UNKNOWN save | Safe command locator and “Kiểm tra kết quả” | Receipt read first; no automatic new-key resubmission; do not keep secret in recovery state |
| Denied/error/conflict | Safe error, explicit reload/back; stale snapshot must be reviewed again | Clear connection metadata/credentials immediately on authority/actor/scope loss; ignore late replies |

Create supports distinct provider forms: direct OpenAI API key; direct Anthropic API key with explicit workspace selector if required by selected credential mode; Gemini Developer API key. Cloud wrappers, subscription login tokens and arbitrary compatible proxy endpoints are outside this first contract. UI shows safe validation errors, not provider response bodies. Provider availability is installation policy read from backend, not hardcoded successful options.

## Interaction rules

Secret is memory-only and masked on entry; password-manager behavior explicitly reviewed. Clear on submit capture, cancel, actor/scope change, hidden page, destroy or definitive failure. An UNKNOWN secret-bearing save retains only a secret-free command locator; if no committed receipt is found, explain that absence is not proof of failure. Follow the frozen API recovery protocol; require explicit re-entry/review when a new command is safe. Never retain the key in URL, route state, local/session storage, analytics or a browser draft.

Fresh raw session/current ADMIN check before reads and commands; epoch+actor+org+connection+revision fences protect late results/errors. No screenshot contains a real key or real customer metadata. All example labels in Figma are design specimens, never production seed.

On narrow screens use one-column fields, cards rather than compressed table columns, stacked full-width actions and scrollable document flow. Desktop sidebar224px; compact Menu below1024px, Escape closes and restores focus. Dialogs become full-width inset panels on mobile; avoid fixed footers obscuring key fields/errors. Focus first invalid field or safe outcome heading; accessible error association and status announcements; dark text on pale Forest actions avoids low-contrast white text.

## Acceptance and handoff

Figma frame inventory/node links, editability counts and visual-review evidence are recorded in PROVIDER_CONNECTION_FIGMA_HANDOFF.md after actual creation. Required screens include empty/list/create/provider forms/review/detail/access-check/rotation/state/UNKNOWN/error/denied at the3 target widths. Static designs do not establish keyboard, screen-reader, responsive runtime or API behavior. Independent API/security/UI review and current Figma-to-contract reconciliation precede implementation acceptance.

## Operation/data parity correction after independent review

Detail loads latest access-check and discovery separately and offers distinct “Kiểm tra truy cập” and “Khám phá model” actions. List does not claim current connection observation or test time from its Connection-only DTO. No list search field is shipped in this first contract. A local cancel/back before command acceptance abandons the form; after acceptance the UI says “Rời màn hình không hủy kiểm tra đã nhận” and stops only local polling. There is no server check cancellation action/state. Model result reads only its discovery check snapshot, labels stale/disabled history and COMPLETE/TRUNCATED, uses fixed20 next/previous navigation, and never becomes an Agent selection automatically.

Figma specimen links must preserve the selected provider through create→review→detail, rather than switching Claude/Gemini to an OpenAI example after save. Specimen-only placeholders must remain explicitly labelled. Exception/success/loading/unknown states are reviewed for each operation before implementation; a generic state frame is a visual pattern, not evidence of all dynamic behavior.

Actual editable Figma handoff: [72 frames /24 states /3 widths](PROVIDER_CONNECTION_FIGMA_HANDOFF.md), with exact ledger and bounded visual-validation scope. These are static designs, not a clickable prototype or implemented frontend.

## Nonblocking visual refinements required in implementation

Every connection card/title must be an unambiguous native link to its own scoped Detail, including Claude/Gemini; do not copy only the OpenAI specimen button. Previous/next disabled state derives from actual page/total, with an announced page counter. Use a clear hierarchy: observation/discovery actions primary, rename/rotation secondary, disable danger-styled with explicit review. Consolidate duplicate cancel/back/list destinations while preserving dirty-form and unresolved-command guards; a local navigation action never cancels accepted backend checks. These refinements were noted by independent review and are not claimed as implemented runtime behavior or independently measured accessibility.
