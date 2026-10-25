# Member invitations — Figma handoff

Ready for frontend design-context fetches, 2026-10-24. Engineering draft under the full current `MEMBER_INVITATIONS_CONTRACT.md`, including retained-approval rejoin warning and shared labels/hooks. This is not production approval. The contract remains normative.

File: https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml/AgentOS?node-id=6-2

Page `6:2`. Reuse session `a982d159-bef4-466e-8cea-7f31aa2b4ffa` on **every** file tool; do not create another session. Before FE implementation, load `figma-design-to-code`, obtain current `get_design_context` and screenshot for the relevant IDs with that session. Existing frame IDs are preserved, but their content is now the current contract, not the earlier Team/Operator prototype.

## Stable frame map

| View | Desktop 1440 | Tablet 768 | Mobile 390 |
|---|---|---|---|
| Public new account | `71:6505` | `71:7309` | `71:8113` |
| Public existing account, inline login | `71:6543` | `71:7347` | `71:8151` |
| Wrong account, explicit logout | `71:6567` | `71:7371` | `71:8175` |
| Generic invalid / expired / consumed | `71:6872` | `71:7676` | `71:8480` |
| Invitation list | `71:6889` | `71:7693` | `71:8497` |
| Create form | `71:6919` | `71:7723` | `71:8527` |
| Create review, unchecked acknowledgement | `71:6949` | `71:7753` | `71:8557` |
| Recorded create, mail queued | `71:6975` | `71:7779` | `71:8583` |
| Invitation detail | `153:7792` | `153:7832` | `153:7872` |
| Revoke review | `153:7912` | `153:7948` | `153:7984` |
| Resend review | `153:8020` | `153:8056` | `153:8092` |
| Management unknown / receipt recovery | `153:8128` | `153:8157` | `153:8186` |
| Public acceptance unknown | `153:8215` | `153:8237` | `153:8259` |
| Authenticated matching account, acceptance review | `153:8281` | `153:8314` | `153:8347` |
| Accepted, newly created account → login | `153:8380` | `153:8402` | `153:8424` |
| Accepted, existing account → actual organization | `153:8446` | `153:8467` | `153:8488` |

Example direct link: https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml/AgentOS?node-id=71-6949 . Substitute the IDs above, with colon converted to hyphen.

The 24 added frames are isolated to the right of the inspected existing canvas boundary (`maxX=52800` at discovery): columns start at 53000 / 54600 / 55500, rows spaced 1400. Engineering draft annotation `153:8509` is outside product flows at y=-400. Existing unrelated authentication, role and grant frames are out of scope and were not edited.

## Visual implementation

Native editable auto-layout containers, wrapping text, native field instances (`19:174`) and button instances from `19:90`. No screenshots, bitmap assets or image fills in the invitation views. Existing auth brand structure is retained; all new controls use the existing Forest system. Code Connect discovery found no matching `*.figma.*` files in the repository; native existing invitation/auth instances supplied authoritative component IDs.

Verified source font: Inter imports 400/600/700 in `frontend/src/styles.css`; Figma Regular and Semi Bold are available and were loaded before editing. Reused Forest/Brand, Forest/Title, Forest/Section, Forest/Body and Forest/Caption styles. Existing `Forest & Ivory` collection `VariableCollectionId:41:1371` supplies canvas, surface, brand, soft, ink and border bindings; paints retain resolved fallback colors. No new business variables, text styles or reusable components were invented.

Auth content maximum width 560; mobile outer width 358 at viewport 390 with 16 side margins. Card padding 24 desktop/tablet, 16 mobile; vertical gaps 16/12; buttons 48 high. Flat white surface, Forest green primary, dark ink, fine borders, 8px corners. Preserve wrapping rather than truncating email or warnings. Runtime layout must allow long content to grow and page-scroll; do not enforce screenshot viewport height as a content maximum. Long unbroken email/bindings need `overflow-wrap:anywhere` and `min-width:0`.

Braces in editable Figma text are **bindings**, not real organization/email/status/date claims. Replace them from freshly authorized DTOs; never ship literal braces. The create email, inline login fields, new name/password/confirmation are deliberately empty, with no fake entered value. Review acknowledgement starts unchecked and its final action starts disabled. Use a real labeled checkbox in HTML, not the Figma checkbox drawing. No reason input is required.

## Policy and state binding

- Invitation role is fixed organization MEMBER. There is no Team selector, role selector, approval grant selector or promotion in this flow. Existing membership/current role is preserved. Scope explanation says no Team or approval grant is added.
- Display this exact sentence in admin review and public acceptance review: **“Quyền duyệt riêng còn hạn có thể tiếp tục có hiệu lực khi tham gia lại.”** It is also present on scope panels so returning members see it early.
- Lifetime is fixed **72 hours from original creation**; resend does not extend it. Resend cooldown is separately **60 seconds from last queue request**. Enable actions only from fresh `canResend` / `canRevoke`, revision, expiry and authority checks, not from a client clock alone.
- The recorded-create view means a command receipt with mail queued. It does not assert SMTP success or human receipt. `SENT` is delivery metadata only. Revocation result should reuse the result layout with heading “Đã thu hồi lời mời”, exact recipient and historical receipt time; resend result uses “Đã ghi nhận yêu cầu gửi lại”, queued caveat and unchanged expiry. Neither result implies current invitation state after historical replay; load fresh detail separately.
- List defaults to ALL / page 0, 20 rows. The single editable row is a repeat template, not fabricated live inventory. Render actual rows/count/pagination. Empty copy: “Chưa có lời mời.” Status filter labels: “Tất cả”, “Đang chờ chấp nhận”, “Đã chấp nhận”, “Đã thu hồi”, “Đã hết hạn”. Disable unavailable pagination directions.
- Translate status bindings: PENDING → “Đang chờ chấp nhận”; ACCEPTED → “Đã chấp nhận”; REVOKED → “Đã thu hồi”; EXPIRED → “Đã hết hạn”. Delivery: QUEUED → “Đang chờ gửi”; SENT → “Đã gửi qua máy chủ thư”; FAILED → “Gửi email chưa thành công”; CANCELLED → “Đã hủy gửi”. Show delivery and invitation status independently.
- Detail binds authorized scoped email, organization context, effective status, latest delivery, creation/expiry dates and action eligibility. For terminal states hide mutation actions; show acceptedAt or revokedAt only if returned. Cooldown copy may say “Có thể gửi lại sau {remainingSeconds} giây.” Do not turn delivery failure into acceptance failure.
- Create/revoke/resend reviews show exact normalized email, fixed scope and unchecked explicit acknowledgement. Entering a review does not dispatch a command. Final buttons are “Gửi lời mời”, “Thu hồi lời mời”, “Gửi lại lời mời”. Enable after acknowledgement plus valid fresh context. Pending actions are disabled with “Đang xử lý…”. Keep the reviewed request and key frozen once dispatched.
- Unknown management result retains the same actor/org operation correlation. “Kiểm tra biên nhận” performs receipt recovery; “Gửi lại đúng yêu cầu này” is authorized manual same-key/same-body retry. A null receipt remains unknown. Never create a replacement command or claim failure/success from missing data. Recheck authority before either recovery action.
- NEW_ACCOUNT shows real name, password and confirmation fields. Name: stripped 1–255 Unicode code points, no forbidden control/surrogate characters. Password: no trim; at least 14 code points and no more than 72 UTF-8 bytes, valid Unicode. Confirmation matches locally and is not a wire field. Show specific validation next to its input, associate with `aria-describedby`, focus first invalid input. Product helper is plain Vietnamese; byte/transport details belong in engineering validation, not the main explanation.
- SIGN_IN uses inline **Email / Mật khẩu / Đăng nhập** with existing AuthService.login. Keep invitation capability only in component memory through login. Then freshly inspect and show separate READY review; logging in never accepts automatically. Only verified matching READY shows “Tham gia tổ chức”.
- MISMATCH offers **“Đăng xuất để đổi tài khoản”**, explicitly logs out, clears fields/private context and reinspects. No silent account overwrite or alternative signup. Only masked emailHint is public; do not display a guessed full recipient or signed-in identity.
- NEW_ACCOUNT final is **“Tạo tài khoản và tham gia”**. On successful new acceptance clear capability/password and navigate to normal login, with no auto-login. Existing acceptance points to the actual returned organization; alreadyMember preserves actual role.
- Invalid/expired/revoked/consumed/missing fragment uses generic invalid view without asserting that no membership was created or exposing expired organization/recipient metadata. Reopen original email / contact inviter. Public UNKNOWN has a distinct recovery view: explicit login and verification of actual accessible organizations, no repeat acceptance or password replay. Do not infer expiry/failure from a consumed capability after an uncertain response.

## Shared headings, labels and hooks

| Accessible region / repeated element | Hook | Visible heading / label |
|---|---|---|
| List row | `invitation-row` | Actual recipient email + independent invitation/delivery labels |
| Create / resend / revoke review | `invitation-review` | “Xem lại lời mời” / “Xem lại gửi lại” / “Xem lại thu hồi” |
| Recorded or confirmed result | `invitation-result` | “Đã ghi nhận lời mời” / confirmed result copy |
| Scoped detail | `invitation-detail` | “Chi tiết lời mời” |
| Management or public unknown | `invitation-unknown` | “Chưa xác định kết quả” |
| Public new/ready review (sign-in context also named) | `invitation-accept-review` | “Nhận lời mời” / “Tham gia tổ chức” |

Shared field labels: “Email người nhận”, “Họ và tên”, “Mật khẩu mới”, “Nhập lại mật khẩu”. Management recovery and final action labels are exact as above. Set meaningful accessible region names and scoped hooks in implementation; Figma names document intent and are not HTML test attributes. Avoid tests that select an arbitrary generic alert when several warnings coexist.

## Interaction and privacy requirements for FE

Use the contract's exact API envelopes, frozen commands, idempotency keys and revision gates. Routes: `/workspace/:id/invitations`, `/workspace/:id/invitations/new`, `/workspace/:id/invitations/:invitationId`, `/invitation`. Public fragment is immediately erased and kept only in component memory. Never expose POST/token/hash or URL mechanics as product copy, never persist capabilities/passwords/commands in browser storage.

Fresh actor/session/workspace checks gate private DOM on entry, focus, visibility, mutations and recovery. While checking, show “Đang kiểm tra quyền truy cập…” without private content. Denial clears email/list/detail/review/body. Same-actor dispatched-unknown recovery may retain only opaque correlation for receipt GET; account change drops all correlation. Observed account/session change clears name/password/private public context before reinspection. Fence late replies by actor/org/route/epoch. No automatic accept on load/login/focus. Dirty route/unload warns; no incidental submit on back, cancel or recovery entry.

Loading: “Đang tải lời mời…”. No permission: “Bạn không có quyền quản lý lời mời trong tổ chức này.” Availability: “Chưa thể tải lời mời. Vui lòng thử lại.” Conflict: “Lời mời đã thay đổi. Vui lòng tải lại trước khi tiếp tục.” Do not retain private review data behind a denial banner. Keep receipt recovery separate from the generic retry-loading action. Use inline field errors, status announcements, logical keyboard order, visible focus, native form controls and normal page scrolling.

## Design verification scope

All 48 views are native editable layers. Representative screenshot review covered desktop create review (`71:6949`), tablet inline login (`71:7347`), mobile new account (`71:8113`) and mobile management unknown (`153:8186`); Forest canvas fallbacks and brand alignment were corrected before the final passing screenshots. Structural verification across all 48 frames found zero root overflow, horizontal wrapper overflow, collapsed text or canvas-frame overlaps; zero image fills; 84 button instances from `19:90` and 18 field instances from `19:174`. All content bottoms fit their 1000/1024/844 frame heights. `git diff --check` passed for this document. This is Figma layout verification, not browser, service, SMTP, accessibility certification, regression or independent QC execution. FE and QC must run the contract's real implementation checks separately.

Only this handoff document is a repository source edit in this design task. No app, contract or QC source changes; no commits.
