# Team directory acceptance cases

These cases accept persisted reads and scoped navigation, not runtime/activation. Dedicated disposable fixtures are isolated from all prior organizations and never become application seed data.

| ID | Action | Expected result | Verification |
|---|---|---|---|
| TD-01 | Page through25Teams, including tied dates |20/5 rows, correct total and deterministic created_at DESC,id; no duplicates | Backend/browser/PostgreSQL |
| TD-02 | Search name/purpose with %, _, !, backslash and injection-like text; combine status | Literal case-insensitive matching, trimmed query, correct filtered counts; no wildcard widening | Backend/browser/PostgreSQL |
| TD-03 | Read manager/Agent/task aggregates | Scoped manager;3Agents and6tasks produce3/6, not18; RUNNING2, WAITING/BLOCKED2, IN_REVIEW1; total includes COMPLETED | Backend/browser/PostgreSQL |
| TD-04 | Null legacy purpose/manager and empty Team | Explicit missing-purpose/manager state; zero counts stay zero; no fabricated health/runtime | Backend/browser/mock rendering |
| TD-05 | Open Running/Pending/Review task links | Existing dashboard task API receives exact Team/state; correct task rows/selected controls, including Team outside dashboard page | Browser/mock rendering |
| TD-06 | Reload/back/clear/no-results and malformed URL | URL state restored, filter resets page, readable no-results, invalid query recovery without old rows | Browser |
| TD-07 | ADMIN creates/opens details; MEMBER reads; revoke account/membership | Current permissions enforced; MEMBER has no create link; workspace lookup404 after revocation, directory403, old rows cleared | Backend/browser |
| TD-08 |503, null200, wrong-scope or malformed-count response after success | Prior rows cleared; readable error and GET retry; no business writes | Browser/mock rendering |
| TD-09 | Inspect1440/768/390 and compact keyboard navigation | No horizontal overflow, primary text contrast>=4.5, dialog Escape closes and focus returns | Browser/mock rendering |
| TD-10 | Native projection vs exact persisted records before/after reads | API equals PostgreSQL projection and independent aggregate counts; Team/Agent/task rows and receipt/version counters unchanged | PostgreSQL probe |

The six browser cases run across three widths. Local mock HTTP rendering verifies interactions only. Backend integration uses H2; PostgreSQL browser/native execution must be confirmed from actual CI logs in TEAM_DIRECTORY_QC_REPORT.md. Broad accessibility, production load/failover, full permission administration and runtime execution remain outside this bounded gate.
