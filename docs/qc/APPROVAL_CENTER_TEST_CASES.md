# Approval Center test cases

| Case | Verification |
| --- | --- |
| AC-B1 | Empty ledger, MEMBER safe metadata projection, no private payload/decision/user fields |
| AC-B2 | Exact group counts and closed-state mapping, deterministic tied timestamps, 20/4 paging, beyond-last page, foreign exclusion |
| AC-B3 | Fresh enabled credential-version/membership checks, foreign org, disabled/stale/revoked sessions denied |
| AC-B4 | Invalid group/page 400, anonymous401, authenticated CSRF-protected POST405 |
| AC-B5 | Moved requester ID/name scrubbed, read does not modify stored request |
| AC-B6 | Database foreign scope, unknown requester, invalid status and Task-without-Mission rejected |
| AC01–06 | Real-backend browser: paging/Inspect/Task href/reload, all groups, closed status semantics, empty/invalid pages, member/foreign boundaries, malformed/503 clearing and retry, revoked API response clearing, compact Escape/focus and overflow at all three project viewports |
| AC-PG | Native PostgreSQL fixture exact group counts, ordered IDs/page boundaries, safe linked Task/requester projection, tenant denial, whole business-table before/after snapshot |

No production fixtures. Browser interception supplies failures only; positive rows must come from actual HTTP/PostgreSQL data. Coordinator authored these cases after the independent QC agent hit its usage limit. Execution results remain separate in QC report.
