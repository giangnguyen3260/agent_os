# Mission workspace QC report

Decision: **PASS for bounded Mission Control read delivery**. Draft PR15 stacks on unmerged PR14. Bounded delivery is read-only Mission Control; planning/execution and production acceptance are excluded.

## Exact candidate and evidence

Corrected validation candidate `1488f16eb51dec12f8a032c48410b5a0d8d3caeb` (application source unchanged from `e1da237e4010f21e967617e43f3d32ef0aded60d`); tested PR merge `da813ac4033831ca101858c46b54916aefcc730a` onto `afbd578a308cdd074556723f253f359e586c81d5`. Evidence times are UTC on21October2026; final evidence documentation is separate from tested application source.

| Gate | Actual evidence |
|---|---|
| Code quality | [Run39642526717](https://github.com/giangnguyen3260/agent_os/actions/runs/39642526717), job119369471725, success17:19:41Z:109backend tests0failures/errors/skips; Google imports/format, FE lint/format/tooling/build passed |
| Docker | [Run39642526987](https://github.com/giangnguyen3260/agent_os/actions/runs/39642526987), job119369474629: success17:28:56Z; healthy build/stack/schema/fixtures, all browser/native regressions and cleanup passed |
| Native Mission workspace probe | Step success17:20:48Z: PostgreSQL exact summary/task filters/order/pages/pending fields,25safe activity events excluding all mixed references, empty/legacy/tenant scope, invalid parameters and unchanged business-state snapshot |
| Browser regression | Completed17:28:11Z:258passed in6.9minutes across1440/768/390, including18new Mission workspace and240baseline instances, serial with no retries |
| Native regressions/session | Existing Mission create/directory, Agent creation/Studio, activation no-mutation, directory/Team Workspace and account barrier probes passed. Exact session survived backend restart; logout rejected replay17:28:47Z |
| Local rendered checks |38mock API/interactions passed across1440/768/390 after finaltabletcardbreakpointfix: task/pending/activity pages, root/task scope/count/null/error rejection, extra event field rejection, private clearing, empty state, unsupported actions disabled, nativeMenuEscape/focus and zero writes; screenshots inspected. Mock API checks are not persistence evidence |
| Independent source QC | Final read-only review found no source/selector blockers; fresh auth/scopes, strict event projection, client validators/cancellation/clearing, responsive cards and native navigation inspected. Reviewer did not independently rerun full CI; coordinator inspects actual completed logs |

## Delivered flow

Assigned Mission directory rows and saved details link to Open workspace. Forest S12 Mission Control shows saved metadata, completed/running/pending task counts, paginated task statuses, waiting reasons/timestamps/resolvers and safe activity. Desktop rows become cards at≤1023px; compact navigation supports Escape/focus restoration. URL task/pending/activity filters and pages survive reload/back. Empty Mission shows true zeros. Cost, task owner/run/spec/outputs and runtime execution are not fabricated; Resolver is only the pending resolver, not owner.

All endpoints reauthorize enabled human/current credential version and ADMIN/MEMBER membership, then require exact organization/Team/Mission. Manager ID/name resolve only inside the same org+Team. Tasks are scoped by all three IDs, fixed20-row pages ordered created_at descending/id. Count+rows share REPEATABLE_READ read-only transactions. Summary states are unique disjoint recorded task categories.

Activity returns exactly id/eventType/actorType/createdAt. Every non-null reference must match the requested Mission, including a task reference when mission reference is already valid. Task-only and Mission-only valid events are included; same-Team other-Mission, other-Team, foreign and reference-less mixed events are excluded. Payloads and actor identity never enter the response. Native probe independently compares rows/order/counts and snapshot before/after all reads.

UI clears old metadata/rows before reauthorization, cancels superseded subscriptions and guards late responses by epoch/account. Null/malformed/scoped/count/pending-shape/event-key errors clear private content. Refresh/retry are GET only. Add Task/Pause mission/Cancel mission/Plan/Outputs remain disabled for both human roles. No schema migration or production seed is introduced.

## Cases and findings

Six real-backend cases per viewport cover exact31tasks/24pending, all9statuses, page20+11 and pending20+4, metric navigation and URL reload/back, safe25events/page20+5 and all five mixed exclusions, empty Mission/moved-manager sanitization, malformed/scoped inputs, failure/malformed response clearing/retry, current membership revocation/restoration, disabled writes and compact modal navigation. See [cases](MISSION_WORKSPACE_TEST_CASES.md).

Visual source review corrected the tablet breakpoint from767px to1023px and added border-box dialog sizing before publication. Exact accessible Task status and distinct metric/tab labels were reconciled with QC. Final source review has no open blocking finding; corrected full runtime gate passed.

## Limits

Coordinator inspected completed quality and Docker logs for the exact corrected candidate/merge above. No generated plan, ownership assignment, approvals, task mutation, effective grants, provider runtime, outputs or live cost is delivered. Sonar remains paused, not passed. Broad accessibility, delayed-response race execution, production scale/failover/security and KMS acceptance remain open. No Mac deployment or image publication; all PRs remain draft/unmerged.

## First runtime gate and test synchronization correction

Run39640551568/job119362608113 completed with255passed/3failed browser instances. All18new Mission workspace cases passed; the existing Mission directory MD04 failed identically at three widths. It expected the filtered Archived Team response but its pathname-only response waiter captured the initial unfiltered20Team request. This is a test synchronization defect: the response log contains Directory Team25..06 rather than the submitted Archived query. The test now awaits initial chooser loading and matches GET path/query/page for the search response. Assertions about exact search results, disabled archived selection and no writes remain unchanged. No application behavior or retries were changed. Remaining native/session steps after the failed browser gate were skipped, not passed; corrected full gate above passed.

Corrected wait-helper source was also executed inside the rendered app with delayed initial Team loading at all three widths; all three helper runs passed alongside35directory mock checks. This proves response synchronization behavior under injected latency, not native PostgreSQL persistence. Independent read-only QC found no blocker in the correction and confirmed exact results/disabled-archive/no-write assertions unchanged.
