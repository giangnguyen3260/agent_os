# Member role management — Candidate A implementation design handoff

Implementation follow-up: candidate81bc489 passed development runtime gates and all21 final responsive captures were inspected; see [final QC](../qc/MEMBER_ROLE_MANAGEMENT_QC_REPORT.md). The designer handoff and its pending statements below are the historical design-stage record, not the current implementation status. PR24 stays draft/unmerged.

Status: READY FOR FE DESIGN CONTEXT — Candidate A / implementation draft. Active authority is MEMBER_ROLE_MANAGEMENT_CONTRACT.md. Main proceeds with recommended A, inferred from the user's continuation after A/B; this is not a fabricated explicit “I choose A” quote. Historical proposal-unapproved / A/B-open claims in the earlier version of this handoff are superseded for this continuation. Implementation, acceptance and QC remain pending; no actual role save, application behavior, endpoint registration, test execution or deployment is claimed. Invitations remain separate and OPEN.

File: https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=124-7180

Page `6:2`; isolated wrapper `124:7180`, named **Member role / Candidate A / implementation draft**, positioned at x44900 beyond the previously observed rightmost edge x44670. Task session `fa7d59e3-f46e-4a6c-ba2b-80e2596929e6`.

## Exact frame IDs

All 15 existing screen IDs are unchanged and ready for FE get_design_context. Wrapper and state names use **Candidate A / implementation draft**. All 15 visible **ĐỀ XUẤT — CHƯA DUYỆT** annotation nodes were removed; existing vertical auto-layout reflowed without a vacant banner slot. Rows are 1440, 768 and 390 px; columns are the five states below. Content height grows with text reflow.

| State | 1440 | 768 | 390 |
| --- | --- | --- | --- |
| Edit | `125:7178` | `125:7282` | `125:7386` |
| Review | `125:7201` | `125:7305` | `125:7409` |
| Blocked self / disabled | `125:7224` | `125:7328` | `125:7432` |
| Lost response / UNKNOWN | `125:7242` | `125:7346` | `125:7450` |
| Recorded / historical | `125:7264` | `125:7368` | `125:7472` |

Open a specific view by replacing the URL node-id with its ID using a hyphen, e.g. [mobile review](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7409), [desktop edit](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7178), [tablet unknown](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7346).

## Flow and active contract

Edit shows readonly `{context.member.currentRole}`, blank new-role selection with ADMIN/MEMBER options, required reason and disabled review action until valid. Review shows before/after, reason, checkedAt and `{context.revision}`, unchecked acknowledgement and disabled final save until acknowledged. Final save is the only POST in the implementation design; review/back/target/transition changes invalidate acknowledgement. These are design states, not interactive or implemented controls.

Organization ADMIN capabilities are distinguished from separately granted approval authority. The review warning explicitly preserves grants on promotion/demotion: demoting ADMIN does not revoke separately granted approval rights. Grant inspection is a separate action and remains ADMIN-only after fresh authorization; holding an approval grant does not authorize inventory inspection.

Blocked state explains self-change and disabled-target restrictions without a save button, and calls out lost ADMIN, removed target, same role, stale revision and enabled-Admin invariant. Refresh requires new context and review.

UNKNOWN retains original key/frozen body for an authorized identical manual retry or exact receipt GET. Null receipt/current role/advanced revision never establishes success or failure. No replacement command. Authority loss clears private body/reason and POST retry; same-actor minimum correlation may support authorized GET recovery. Account changes erase correlation.

Recorded uses `{receipt.beforeRole}`, `{receipt.afterRole}`, actor/event/time and `{receipt.revision}` as historical bindings, excludes reason and separates current-member refresh. Readonly ADMIN-only history uses `{history.items}` and pagination, restricted actual events only, with no invented legacy provenance. Braced field names are bindings, not sample successful operations.

Dirty/review/unknown navigation warnings, accepted-navigation cleanup and stale-result privacy guards are annotated in each frame. Fresh server/session checks and DTO validation remain implementation gates under the active contract; no auto-POST on focus/refresh is intended.

## Reuse and checks

- Existing Forest semantic color variables reused through paint bindings: canvas `VariableID:41:1384`, surface `41:1385`, brand `41:1386`, ink `41:1388`, muted `41:1389`, warning `41:1391`, warningInk `41:1392` (all latter IDs also have the `VariableID:` prefix).
- Existing text styles reused: Forest/Title, Forest/Section, Forest/Body, Forest/Caption; all inspected text uses Inter. Flat surfaces, no effects or new assets. Existing button component `19:90`, resolved from grant reference instance `120:7160`, provides the 21 existing role instances. Existing components/tokens/styles were not modified. Layout uses auto-layout containers with local sizing/spacing overrides; no new token or component definitions.
- Structural readback: 255 descendants = 42 FRAME, 192 TEXT, 21 INSTANCE; zero image-filled nodes. Fifteen top-level screen frames at exact requested widths. Text-width check after repair found no text wider than its parent.
- Continuation visual check: desktop review `125:7201` and mobile review `125:7409` screenshots inspected after the update. No overlap, cropped text or banner gap observed; acknowledgement and expanded ADMIN-only grant warning reflow correctly. Screenshots are tool-rendered checks, not image assets. Prior wrapper/tablet checks above are historical; not every frame was separately rendered in this continuation.
- Continuation readback: all 15 exact frame IDs remain at 1440/768/390 px, with vertical auto-layout. No stale proposal text; no text wider than its parent; all text fonts remain Inter; zero image-filled nodes. Desktop heights (Edit/Review/Blocked/Unknown/Recorded): 676/706/534/598/618; tablet: 716/768/574/660/636; mobile: 792/950/716/842/840.
- Prior grant endpoint frames `117:7043` and `117:7057` read back as present. All mutation targets were within wrapper `124:7180`; other frames, components, styles and tokens were not targeted. No independent file-wide before/after hash was collected.

## Exact labels and FE relay

Edit heading **Sửa vai trò tổ chức**; review heading/action **Xem lại thay đổi**; final action **Lưu vai trò**; unchecked acknowledgement **Tôi xác nhận thay đổi vai trò tổ chức này.**; receipt heading **Thay đổi đã được ghi nhận**. Recovery actions: **Kiểm tra biên nhận** and **Gửi lại đúng yêu cầu này**. Recorded is a conditional historical receipt design, not evidence of an actual save.

Blank new-role selection, readonly current role, required reason, before/after, checkedAt/revision and actual braced bindings remain. Member display uses `{context.member.displayName}`; recorded actor uses `{receipt.actorName}` and `{receipt.actorId}`. History keeps restricted actual-event/reason labels and pagination. Grant warning preserves separately granted approval authority on demotion; grant inspection remains freshly authorized ADMIN-only. Existing member-card/grant-inventory entry-link placement outside this wrapper is owned by implementation and must use **Sửa vai trò tổ chức** for nonself enabled members; no external frames were changed.

Main may relay this handoff to FE now and use the unchanged frame IDs above for design context. Implementation/acceptance evidence must be supplied by the implementation owners.

Only this handoff file is authored by this designer task. Proposal and QC files are concurrent work owned elsewhere. No app/backend/migration/test files changed by this task; no tests run and no commits made. Implementation, acceptance and QC gates remain outstanding.


## Product-copy cleanup after mobile FE context review

Updated only visible text within wrapper `124:7180`: 15 policy notes now read **Chỉ quản trị viên hiện tại được đổi vai trò của thành viên khác đang hoạt động.**; three review save notes read **Chỉ lưu sau khi bạn xem lại và xác nhận. Quay lại chỉnh sửa sẽ bỏ xác nhận trước đó.**; 15 navigation footers read **Rời trang có thể làm mất nội dung chưa lưu hoặc khả năng kiểm tra thao tác đang chờ.** These replace the earlier technical policy/POST/UNKNOWN footer prose.

Readback confirms 33 text edits, all 15 frame IDs retained, and every braced binding unchanged, including review `{context.checkedAt}` and `{context.revision}`. Mobile review `125:7409` is now 390 × 896 with no text wider than its parent; its screenshot was inspected for footer reflow. Previous height measurements above describe the earlier design pass and are superseded: desktop heights remain 676/706/534/598/618; tablet heights are now 698/750/556/642/618; mobile heights are now 756/896/680/806/804 (Edit/Review/Blocked/Unknown/Recorded). Contract behavior and implementation gates remain unchanged. No app changes, tests or commits.
