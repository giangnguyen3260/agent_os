# Agent directory acceptance cases

All business rows come from scoped persistence. Dedicated CI accounts/organizations are disposable fixtures, not product seeds. This flow reads configuration and links to existing screens; it cannot activate or execute an Agent.

| ID | Action | Expected result | Evidence type |
|---|---|---|---|
| DIR-01 | Read pages0/1 with25 Agents including tied dates | Fixed20/5, correct total, deterministic created_at DESC/id and no duplicates; no foreign rows | Backend + browser + PostgreSQL |
| DIR-02 | Search name/role including %, _, !, backslash and SQL-like text | Literal case-insensitive matches, trimmed input; no wildcard widening or injection | Backend + browser + PostgreSQL |
| DIR-03 | Combine lifecycle/Team filters | Correct scoped count/items, missing or foreign Team404, unauthorized organization403 | Backend + browser + PostgreSQL |
| DIR-04 | Reload/back/clear filters and no-results | URL state restored, page reset on filter, correct empty state and enabled pagination | Browser |
| DIR-05 | ADMIN opens create, detail, Studio and Draft activation review | Correct scoped links; current lifecycle and requested limits, no fake runtime | Browser + rendering |
| DIR-06 | MEMBER reads, membership revoked or credential/account invalidated | Member has no create/activation action; fresh server authorization denies invalid account/membership; prior rows cleared | Backend + browser |
| DIR-07 | API503, null200 or wrong-organization item after prior success | Old rows cleared, readable error and GET retry; no business writes | Browser + rendering |
| DIR-08 | Invalid page/status/Team/query parameters |400 invalid recognized parameter; UI malformed URL rejected with Clear filters recovery | Backend + browser |
| DIR-09 | Inspect1440/768/390 and compact Menu keyboard | No horizontal overflow; readable green primary action; Escape closes modal and focus returns | Browser + rendering |
| DIR-10 | Compare native projection/order and persisted state before/after reads | API equals PostgreSQL projection; exact Agent/config rows and task/version/receipt counts unchanged | PostgreSQL probe |

Local rendered checks use mock HTTP only. Backend integration tests use H2. PostgreSQL/browser execution is accepted only when actual CI logs confirm it; see AGENT_DIRECTORY_QC_REPORT.md for final counts/revisions. Concurrency allocation, runtime activity, complete permission management, production load and broad accessibility remain outside this slice.
