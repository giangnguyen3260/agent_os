# Task Detail read contract

Branch feature/task-detail stacks on unmerged Mission Control PR15 (0e6fe07122dba0bc8578586ee5868accb9cc5079).
Forest S13 references: desktop46:3044, mobile60:6279, tablet60:12895. All three high-fidelity contexts/screenshots inspected. No static images/SVG assets used in these frames. Simple Design System React Code Connect buttons are unavailable in this Angular project; reuse Forest native controls/tokens.

## Scope

Mission Control task titles open `/workspace/:id/teams/:teamId/missions/:missionId/tasks/:taskId`. Native sidebar/compactMenu, heading, action row, waiting banner, Overview / acceptance and Runs & output history follow S13. Additional recorded activity is a safe metadata read, not an execution trace. Desktop224px sidebar/32px padding; compact navigation below1024px, tablet24px and mobile16px padding. No sample names, task IDs or approval timestamps ship.

Exact scoped GET `/api/v1/organizations/{org}/teams/{team}/missions/{mission}/tasks/{task}` returns `{generatedAt,task:{id,organizationId,teamId,missionId,title,status,pendingReason,waitingSince,resolver,createdAt,updatedAt},mission:{id,title},team:{id,name}}`. Fresh account/credential version and organization ADMIN/MEMBER authorization required. Foreign workspace403, absent or mismatched resource404. Read-only REPEATABLE_READ. No migration or writes.

GET same path `/activity?page=0` returns fixed20 `{items:[{id,eventType,actorType,createdAt}],page,size,total}`; page0..100000, createdAtDESC/id order. Only current organization+exact task references; mission reference must be null or this exact Mission. Mission-only/referenceless/mixed references excluded. No payload, actor IDs, raw goals or cross-scope names.

FE validates exact detail shape, current scope, nine stored task states, timestamps and pending invariants. Event keys/pages validated. Loading/error/denial/route/account changes clear private state, cancel previous requests and ignore stale responses. Activity page uses URL state and supports reload/back. Invalid addresses/query/page reject locally; retry reauthorizes. NativeMenu supports Escape and focus return.

Status is stored state. Resolver is pending resolver, never owner. Description, acceptance criteria, output target, assignment owner, run/attempt/input/output history, artifact and approval decisions are unavailable because authoritative storage/runtime does not exist. View approval/View run/Retry task disabled with explanatory text. Back to mission and Back to team navigate real scoped pages. No activation/execution/provider calls.

## Verification

See TASK_DETAIL_TEST_CASES and TASK_DETAIL_QC_REPORT for executed evidence and remaining limits. Sonar remains paused; production security/scale/full product acceptance not claimed.
