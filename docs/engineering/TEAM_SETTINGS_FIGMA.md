# S06 Figma contract alignment

Normative source: `TEAM_SETTINGS_CONTRACT.md`, read in full including the manager eligibility correction on 2026-10-25. This handoff describes design intent; the contract remains authoritative.

File: https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=6-2

Page `6:2`; every file operation reused session `3544f30d-f698-4aed-baf3-4393b5f3028e`. No new session, other page, component library, or shared token was created or changed.

## Frame inventory

| State | Desktop 1440 | Mobile 390 | Tablet 768 |
|---|---|---|---|
| Information | 43:1833 | 60:4155 | 60:10771 |
| Members & manager | 43:1900 | 60:4232 | 60:10848 |
| Saved limits | 43:1956 | 60:4298 | 60:10914 |
| Historical saved receipt | 47:3275 | 60:8213 | 60:14829 |
| Review | 47:3304 | 60:8252 | 60:14868 |
| Blocked, stale, availability annotations | 47:3340 | 60:8298 | 60:14914 |
| Unknown outcome — new | 157:7421 | 157:7422 | 157:7423 |

All 18 existing top-level frame IDs and canvas positions were retained. Their old children were replaced with native editable text and auto-layout containers. Existing root viewport sizes remain 1440×1000, 390×844 and 768×1024. Long content scrolls vertically rather than overlapping adjacent frames. New unknown frames are at x56050, y2400/4500/6600, to the right of the previous rightmost bound x55890. Final geometry read found no overlap with any other page child for all 21 roots.

## Contract adaptations

- Only the actual current, enabled, credential-version-matching organization ADMIN can read, save, check receipts, or replay. Agent MANAGER level never grants human permission. No human Team Manager binding is illustrated.
- DRAFT/ACTIVE permit metadata and saved-limit edits. ARCHIVED is read-only. Membership and manager-pointer deltas require DRAFT. Existing status never changes.
- Newly selected/changed manager requires MANAGER and DRAFT/ACTIVE and belongs to the desired selected set. An unchanged current manager with legacy ineligible level/status is retained for metadata/limit edits, including DEGRADED/SUSPENDED. Never auto-drop or reactivate it. Existing unchanged members preserve recognized statuses. Unknown legacy labels remain opaque and blocked according to Context.
- Current member rows come from Context, including canRemove/removalBlockers; the picker searches actual eligible candidates with pagination, size20. Manager is included in the selected set but excluded from wire agentIds. Replacing manager retains the old member unless explicitly removed. A null manager is allowed for DRAFT. ACTIVE disables membership controls while retaining info/limits editing.
- Removal requires a DRAFT Agent without reporting links or protected historical Mission/Task/Approval/AGENT audit references. Generic reasons disclose no foreign record. Existing receipts/history are immutable. The former “active task/run only” explanation was removed.
- Limits are configured concurrency and monthly USD budget, with contract bounds. Legacy null values remain blank. Runtime slots, effective concurrency and spend are explicitly unavailable. Removed fabricated business counts, costs, routing, accepting/paused, dispatch and automatic stop claims.
- Required warning: `Các giới hạn này là cấu hình đã lưu. Số liệu thực thi hiện chưa có.` Intake is MANUAL, read-only; no grant, activation or organization membership controls.
- Info/Members/Limits retain an in-memory draft. `Xem lại thay đổi` opens review without POST. Review includes before/after name, purpose, description, manager, explicit added/removed/retained members, configured concurrency, monthly budget, intake and unchanged status. Dynamic values are placeholders for actual snapshot/draft values, never business examples.
- Review starts with unchecked `Tôi đã xem lại thay đổi cấu hình Team.` and disabled final `Lưu thay đổi`. Checked behavior: enable the same final button only with actual canonical changes and valid form; checking alone does not POST. Edit/back/refresh clears acknowledgement. No-op cannot submit. The 21-view set uses the initial unchecked rendering; checked behavior is this explicit state annotation rather than another three roots.
- Before final POST, refresh current session/workspace and scoped Context. A different actor, ADMIN loss, revision/fingerprint change clears acknowledgement and blocks submission. Do not silently replace the draft. Revalidate on focus/visibility. Dirty Back/reload/RouterLink navigation asks before losing the in-memory draft.
- Saved is a historical receipt: `Đã ghi nhận thay đổi`, original actor/name/time/revision/event/key/before/after. It does not assert that current Team equals receipt.after. Expand the placeholder before/after groups into all SettingsState fields in implementation; do not render serialized JSON as the production UI. Load current configuration explicitly.
- Blocked frames annotate mutually exclusive runtime conditions, not five simultaneous errors: ARCHIVED, ACTIVE membership restriction, protected removal, unsupported size/currency/integrity, stale snapshot and read availability. Select the relevant current message. Transport/malformed reads offer GET retry without falsely declaring permission loss. Scoped 404 reveals no names/details. Definite stale conflict requires fresh source/review, never an automatic new key.
- Unknown follows timeout/network/5xx/malformed2xx. Keep the frozen original body/key only while authority remains valid; no automatic writes. `Kiểm tra biên nhận` performs GET for that exact key. Null/unavailable/malformed receipt remains unknown. `Gửi lại đúng yêu cầu này` explicitly replays identical body/key after current authorization, without refreshing revision inside the body. Historical exact replay may recover success after later changes/archival.
- On account/org/authority loss clear names/configuration/members/acknowledgement/frozen body and fence late replies. Retain only opaque actor/org/team/key recovery locator; disable write replay. Never persist draft/body in URL or browser storage.

## Visual system and implementation mapping

Native editable Figma auto-layout frames/text represent existing Angular native controls. No React SDS or Tailwind dependency, raster UI substitute, or Code Connect import was introduced. Implementation notes shown in these handoff views are design annotations, not production copy.

Inter Regular/Semi Bold throughout; final read found zero font mismatches in all21 frames. Desktop sidebar224; content margins desktop32/tablet24/mobile16. Controls have minimum44 touch height. Mobile tabs stack/wrap to preserve touch width; tablet/desktop tabs share a row. Text wraps at fixed content width with height auto-sizing. Existing frame viewport bounds are preserved and vertical overflow is intentional.

Existing Forest semantic variable bindings used:

| Token | ID |
|---|---|
| canvas | VariableID:41:1384 |
| surface | VariableID:41:1385 |
| brand | VariableID:41:1386 |
| soft | VariableID:41:1387 |
| ink | VariableID:41:1388 |
| muted | VariableID:41:1389 |
| border | VariableID:41:1390 |
| disabled | VariableID:41:1395 |
| radius | VariableID:41:1401 |

Spacing dimensions follow existing 8/12/16/24/32 scale; colors and card radius use existing variable bindings. No token values or definitions changed. No static image fills/assets were found in the 18 original assigned frame subtrees, so no asset positions needed preservation. No assets were generated/imported.

## Verification and ownership

Screenshot-reviewed all seven rows at all three widths using get_screenshot with the exact session above. Final screenshots were taken after multiline height reflow was corrected. Review covered typography, wrapping, card borders, sidebar/tab layout, warning copy, disabled initial save and unknown-recovery actions. Mobile and some Members content extends below the initial viewport and is intentionally vertically scrollable; this screenshot review does not claim browser interaction verification or a working Figma prototype.

Screenshot download URLs returned HTML in the local environment, so review used inline screenshot responses. Short-lived URLs are not durable evidence links; the precise frame IDs above identify the reviewed designs. Structural read additionally confirmed all21 viewports, vertical overflow, zero font mismatches and zero page-child intersections. Before the text reflow correction, a separate read found no horizontal overflow; final screenshots verified wrapping visually.

Only the assigned 18 S06 root subtrees and three new unknown roots were written in Figma. No other node or page edits were issued. Locally this worker created only this handoff document: no application, contract, tests, dependency or asset edits and no commits. Parallel workers' existing working-tree changes were left untouched.
