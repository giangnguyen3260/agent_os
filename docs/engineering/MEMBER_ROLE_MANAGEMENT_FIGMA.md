# Member role management — provisional Figma handoff

Status: PROPOSAL UNAPPROVED. Authority A/B remains OPEN; only the user can approve it. The conditional candidate-A engineering contract does not approve role editing. Self-change blocking, enabled current ADMIN authority and grant preservation are proposed together with A. Invitations remain OPEN and separate. No application functionality, registered endpoint, test execution or deployment is claimed.

File: https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=124-7180

Page `6:2`; isolated wrapper `124:7180`, named **Member role / PROPOSAL UNAPPROVED**, positioned at x44900 beyond the previously observed rightmost edge x44670. Task session `fa7d59e3-f46e-4a6c-ba2b-80e2596929e6`.

## Exact frame IDs

Every frame visibly displays **ĐỀ XUẤT — CHƯA DUYỆT**. Rows are 1440, 768 and 390 px; columns are the five states below. Content height grows with text reflow.

| State | 1440 | 768 | 390 |
| --- | --- | --- | --- |
| Edit | `125:7178` | `125:7282` | `125:7386` |
| Review | `125:7201` | `125:7305` | `125:7409` |
| Blocked self / disabled | `125:7224` | `125:7328` | `125:7432` |
| Lost response / UNKNOWN | `125:7242` | `125:7346` | `125:7450` |
| Recorded / historical | `125:7264` | `125:7368` | `125:7472` |

Open a specific view by replacing the URL node-id with its ID using a hyphen, e.g. [mobile review](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7409), [desktop edit](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7178), [tablet unknown](https://www.figma.com/design/5wUjDKXg8vtRaJcO9XHfml?node-id=125-7346).

## Flow and conditional protocol

Edit shows readonly `{context.member.currentRole}`, blank new-role selection with ADMIN/MEMBER options, required reason and disabled review action until valid. Review shows before/after, reason, checkedAt and `{context.revision}`, unchecked acknowledgement and disabled final save until acknowledged. Final save is the only proposed POST; review/back/target/transition changes invalidate acknowledgement. These are design states, not interactive or implemented controls.

Organization ADMIN capabilities are distinguished from separately granted approval authority. The review warning explicitly preserves grants on promotion/demotion: demoting ADMIN does not revoke separately granted approval rights. Grant inspection is a separate proposed action and remains ADMIN-only after fresh authorization; holding an approval grant does not authorize inventory inspection.

Blocked state explains self-change and disabled-target restrictions without a save button, and calls out lost ADMIN, removed target, same role, stale revision and enabled-Admin invariant. Refresh requires new context and review.

UNKNOWN retains original key/frozen body for an authorized identical manual retry or exact receipt GET. Null receipt/current role/advanced revision never establishes success or failure. No replacement command. Authority loss clears private body/reason and POST retry; same-actor minimum correlation may support authorized GET recovery. Account changes erase correlation.

Recorded uses `{receipt.beforeRole}`, `{receipt.afterRole}`, actor/event/time and `{receipt.revision}` as historical bindings, excludes reason and separates current-member refresh. Optional readonly history uses `{history.items}` and pagination, restricted actual events only, with no invented legacy provenance. Braced field names are bindings, not sample successful operations.

Dirty/review/unknown navigation warnings, accepted-navigation cleanup and stale-result privacy guards are annotated in each frame. Fresh server/session checks and DTO validation remain implementation gates under the appended conditional proposal; no auto-POST on focus/refresh is intended.

## Reuse and checks

- Existing Forest semantic color variables reused through paint bindings: canvas `VariableID:41:1384`, surface `41:1385`, brand `41:1386`, ink `41:1388`, muted `41:1389`, warning `41:1391`, warningInk `41:1392` (all latter IDs also have the `VariableID:` prefix).
- Existing text styles reused: Forest/Title, Forest/Section, Forest/Body, Forest/Caption; all inspected text uses Inter. Flat surfaces, no effects or new assets. Existing button component `19:90`, resolved from grant reference instance `120:7160`, provides 21 new instances. Existing components/tokens/styles were not modified. Layout uses auto-layout containers with local sizing/spacing overrides; no new token or component definitions.
- Structural readback: 270 descendants = 42 FRAME, 207 TEXT, 21 INSTANCE; zero image-filled nodes. Fifteen top-level screen frames at exact requested widths. Text-width check after repair found no text wider than its parent.
- Screenshot composition rendered for wrapper `124:7180`; readable views checked: desktop edit `125:7178`, tablet review `125:7305`, mobile review `125:7409`. Text reflow and inherited button label width defects were corrected; final mobile review screenshot was inspected after the repair. Screenshots are tool-rendered checks, not committed image artifacts; not every individual frame received a separate screenshot.
- Existing grant frames `117:7043` through `117:7057` were read back as present. No mutation targeted any pre-existing node; only new wrapper and descendants were changed. No independent before/after file-wide hash was collected.

Only this handoff file is authored by this designer task. Proposal and QC files are concurrent work owned elsewhere. No app/backend/migration/test files changed by this task; no commits made. Product approval and implementation/QC gates remain outstanding.
