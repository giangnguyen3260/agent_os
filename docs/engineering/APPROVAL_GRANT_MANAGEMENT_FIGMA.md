# Approval grant management — Option A Figma delivery

Approved policy: APPROVAL_GRANT_MANAGEMENT_CONTRACT.md. File `5wUjDKXg8vtRaJcO9XHfml`, page `6:2`, continuing session `b8750e05-73fe-4379-934b-bd10aa4ee5aa`. Primary delivery ready.

| View | 1440 | 768 | 390 |
|---|---|---|---|
| edit | 117:7043 | 117:7044 | 117:7045 |
| review | 117:7046 | 117:7047 | 117:7048 |
| revoke | 117:7049 | 117:7050 | 117:7051 |
| unknown | 117:7052 | 117:7053 | 117:7054 |
| recorded | 117:7055 | 117:7056 | 117:7057 |

Open [primary edit](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=117-7043); substitute the table IDs for other views. Frame names: Option A / state / width.

## Historical source
Original simulated grant-edit 71:7033, 71:7837, 71:8641, review 71:7063 and saved 71:7089 remain unchanged and historical. Their sample identities, provisional policy, proposed scope and simulated save are superseded by Option A.

## Retrieved context and screenshots
High-fidelity get_design_context with screenshots retrieved for desktop edit 117:7043, review 117:7046, revoke 117:7049, unknown 117:7052, recorded 117:7055, plus mobile edit 117:7045. Full reference code returned, not sparse metadata. Stable node IDs are the screenshot/context retrieval handles; do not use screenshots as implementation assets. Desktop edit/revoke/unknown and mobile edit visually inspected without clipping. All 15 frames structurally checked: 20–39 editable descendants each, existing button instances, no IMAGE fills, all text Inter.

## Implementation context
- Desktop content 1000px; 220px outer padding at 1440. Tablet 24px and mobile 16px outer padding. Single-column, fill-width fields, vertical scrolling. Card padding 24px desktop/tablet, 16px mobile; section gap 16px; page gap 24px; vertical padding 32px.
- Existing Forest semantic variables canvas/surface/ink/muted/brand/soft/warning/warningInk/danger/dangerInk/border, spacing 8/12/16/24/32 and radius reused. Inter headings 25/36 semibold, body 14/22 regular, labels 14/22 semibold, help 12/22. Existing action instances cloned from source. Code Connect maps React Simple Design System buttons unavailable in Angular; accepted contract requires existing native Forest controls/styles instead. No invented assets.
- Braced values are real-data bindings, never business defaults. Team/risk/duration/reason initially blank. Team is actual searchable paginated selector with ARCHIVED visible disabled. Fixed PUBLISH_PREVIEW; LOW/MEDIUM/HIGH only; explicit integer duration 1–168 hours; required reason per contract. Disabled action until valid. No self-issue.
- Review binds selected scope/duration/reason to fresh checkedAt/revision. Unchecked acknowledgement enabled only after successful fresh review. Selection/revision/authority changes invalidate it. Final action alone writes.
- Revoke targets persisted grant ID/hash, requires reason/ack, warns that other matching grants can remain, and includes restricted paginated history. Empty legacy history states provenance unavailable; never fabricate actor/reason. Disabled/removed recipients and unsupported legacy grants remain revocable when context permits.
- Unknown permits manual receipt GET and identical captured-command/key POST retry only while authorized. Never auto-write or replace command. Null receipt stays unknown. Denial erases DTO/reason/body and removes POST retry, retaining minimum same-account correlation for GET recovery. Account switch erases all correlation. Navigation/reload warns before loss.
- Recorded result binds exact operation/grant/event/actor/time/expiry/revokedAt; omits reason; separate inventory refresh. Receipt is historical, not current authority. Supports ISSUE and REVOKE.

## Scope
Only Figma and this document changed. No app/backend/test/workflow modifications.

## Final handoff — aligned labels and exception variants

All 15 primary frames now have fresh high-fidelity design context plus screenshots after label alignment. Main may implement now using the stable IDs above. Labels match: Cấp quyền duyệt Approval; Thu hồi grant; Tìm team; Chọn team; Rủi ro; Thời hạn (giờ); Lý do; Xem lại grant; Xem lại thu hồi; Tôi xác nhận đúng phạm vi và người nhận.; Lưu quyền duyệt; Xác nhận thu hồi; Thử lại đúng thao tác; Kiểm tra biên nhận. Team selection has an explicit action. Revoke review is before acknowledgement and final confirmation. Final buttons remain disabled in unchecked/blank variants; enable only after valid fresh context plus checked acknowledgement.

| Supplement | 1440 | 768 | 390 |
|---|---|---|---|
| Exception variant sheet | 119:7133 | 119:7135 | 119:7137 |
| Recovery denied | 119:7134 | 119:7136 | 119:7138 |

Exception sheets are design specimens of mutually exclusive states, not a product page showing every error together. They cover loading, read error/retry, empty Team search/directory, self-issue, disabled recipient, archived Team, duplicate scope, stale review/reset acknowledgement, already revoked, authority denial and dirty navigation warning. Apply one relevant variant within the primary layout. No new component library was created. Recovery-denied is an independent complete view with no recipient identity/private snapshot, no POST retry, minimum opaque correlation only. All six supplements have full design context and screenshots; denied contexts refreshed again after privacy correction.

Visual review covered primary desktop edit/review/revoke/unknown/recorded and mobile edit/review/revoke/unknown, plus exception and recovery-denied mobile views. No clipping/overflow found in reviewed renders. All widths returned successful screenshot/context responses. Single-column auto-layout and Inter assertions apply throughout. Longer frames intentionally scroll; do not squeeze into a fixed viewport height. Context output includes React/Tailwind reference markup only; translate to Angular and existing native controls. No screenshots or transient asset URLs belong in runtime code.

