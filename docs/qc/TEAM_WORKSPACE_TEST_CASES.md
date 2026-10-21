# Team Workspace acceptance cases

Read workspace scope only. Mission/task mutations, assignment/runtime enforcement and complete trace/history remain unavailable. Dedicated disposable fixtures never become application seed data.

| ID | Action | Expected result | Verification |
|---|---|---|---|
| TW-01 | Open overview with31tasks across all states | Eight lanes with correct totals, at most5 newest rows each; counts31/1Running/24Pending/1Review; no Agent/task multiplication | Backend/browser/PostgreSQL |
| TW-02 | Open Running/Review/Pending and paginate | Correct lane mapping and scoped order; Pending20/4 with actual reason, waitingSince and resolver; browser Back/reload restores state | Backend/browser/PostgreSQL/mock |
| TW-03 | Read assigned Agents | FixedteamId/org on every row;24Agents page20/4; existing Agent navigation; runtime/current task remain unavailable | Browser/PostgreSQL/mock |
| TW-04 | Read Activity metadata |25consistent Team events page20/5; four allowed fields only; no payload/actor ID or mixed-reference foreign event | Backend/browser/PostgreSQL/mock |
| TW-05 | MEMBER/admin role refresh and membership revocation | Fresh account/role required; MEMBER has no Add Agent; invalid session401, membership denial403, workspace lookup404; all prior rows cleared | Backend/browser/mock |
| TW-06 | Summary/tab API503, null or injected wrong-scope/extra fields | No stale snapshot/task/Agent/event data; error and GET retry; bounded metadata validation; zero business writes | Browser/mock |
| TW-07 | Empty/null legacy configuration and invalid/foreign URLs | Valid zero counts; unavailable purpose/manager/configuration; rejected lane/page/tab/scope and readable recovery | Backend/browser/mock |
| TW-08 | Inspect1440/768/390 and compact keyboard menu | No horizontal overflow; readable responsive lanes/cards, working tabs and Escape/focus restoration | Browser/mock rendering |
| TW-09 | Native read snapshot comparison | Tasks exactly match PostgreSQL lane/order, paged reads complete; safe activity fields/order; Team/Agent/task/audit/version/receipt state unchanged | PostgreSQL probe |

Six browser cases run across three widths. Backend integration uses H2; native PostgreSQL and real-backend CI are separate evidence. Local mock checks prove rendering/interactions only. Final actual counts/revisions/run/job links and source/runtime QC disposition belong in TEAM_WORKSPACE_QC_REPORT.md. Broad accessibility, production load/failover/security acceptance and positive execution are not certified.
