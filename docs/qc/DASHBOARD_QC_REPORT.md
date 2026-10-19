# Dashboard independent BA/QC report

Review: 2026-10-19, branch `feature/dashboard-live`, baseline HEAD `42ae8120ee361ccc34409e55e1bbddaf3f34b3c4` plus shared uncommitted changes. QC edited only this report. This final source checkpoint supersedes earlier interim findings.

## Disposition

**No blocking source defect identified for the bounded dashboard development flow. Runtime gate remains pending actual CI results.** Contract DQC-01–04 are resolved; missing route/template/import checkpoint findings DQC-05–06 are closed. Earlier missing-font build failure is resolved by the dependency installation and independently successful build. No CI, backend test or browser pass is claimed.

Scope is organization overview plus inline task drilldown, with explicit refresh. Execution engine, task mutations, creation/admin flows, allocator and billing remain out of scope. Optional wider testing is not a blocker for this bounded development gate.

## Source findings

| Area | Review conclusion |
| --- | --- |
| Counts and deduplication | RUNNING, WAITING/BLOCKED and IN_REVIEW are counted from unique persisted task rows. Summary tasks have no multiplicative joins; team agent/task aggregates are calculated independently before joining teams. No approval summation or mission substitution. Archived teams/tasks remain included. |
| SQL and consistency | Summary and task reads use repeatable-read transactions. Five summary statements, two task statements or three with team filter are consistent with source and instrumentation assertions. All data queries filter organization; task team-name join also matches organization. Values are bound parameters; state predicates come from a fixed switch. Ordering and bounds match clarified contract. Separate offset page requests can shift as documented. |
| Schema and rollout | V004 composite organization/team/mission foreign keys enforce same organization and team, with required task mission/team and restricted status domain. WAITING/BLOCKED require nonblank reason/resolver and timestamp; other states require null pending metadata. Existing mission/team constraint fails closed on inconsistent legacy rows; rollout document provides read-only precheck and operator handling. Supporting indexes exist. PostgreSQL migration execution remains pending. |
| Security | Existing organization URL membership authorization applies to both endpoints on each request. Foreign/missing team filters share scoped not-found logic. Resolver is operational display text, not an identity lookup. No source tenant bypass found. Membership revocation is enforced on subsequent requests, not promised as push invalidation. |
| Component request state | Overview load cancels prior requests and clears prior data/context; version and destruction guards reject delayed context completion. Task requests are canceled and generation-guarded on filter/route/overview changes and destruction. 401/403 clear protected state; task 404 offers recovery. Failed task loads do not retain previous task rows. No blocking stale tenant/task response path identified in inspected source; deterministic dashboard race execution remains a follow-up, not a claimed test result. |
| Flow and UI | Protected workspace/:id/dashboard route and context entry are present; original context heading retained. Team Open uses same task panel. Team/task pages have separate state. Unsupported navigation/creation visibly unavailable; workspace switch/logout present. Capacity/performance explicitly unavailable. Responsive shell and pending metadata rendering present. No fake capacity, cost or performance found. |

DQC-01 now specifies maximum page 107374182, signed-int parsing and beyond-last empty results. DQC-02 specifies resolver display string and metadata nullability. DQC-03 specifies task relationships/status and legacy rollout behavior. DQC-04 specifies approximate transaction timestamp, ascending task order and independent live offset paging. These contract issues are closed in source.

## Independent execution

Frontend files were hashed at two source inspections and again after checks; dashboard TS/HTML/CSS/service, routes and context entry hashes were unchanged throughout validation. Commands below ran against that stable source checkpoint without application edits.

| Command | Actual result |
| --- | --- |
| `python3 scripts/check-java-imports.py` | PASS after backend completion; wildcard import finding closed. |
| `cd frontend && npm run lint` | PASS. |
| `cd frontend && npm run format:check` | PASS; all matched source files formatted. |
| `cd frontend && npm run test:tooling` | PASS; editor dependency create/read/cleanup and traversal rejection checks. |
| `cd frontend && node scripts/test-auth-generation.cjs` | PASS; both delayed session success and delayed unauthorized cases. Service harness, not dashboard browser race coverage. |
| `cd frontend && npm run build` | PASS, Angular production bundle including dashboard templates/styles; completed 2026-10-19T15:12:35.877Z. |
| `node --check frontend/tests/e2e/dashboard.spec.mjs` | PASS; syntax only. |

Backend tests were not executed by QC: local runtime lacks Maven/Docker and has Java 17 versus required 21. Main additionally reports Maven DNS failure; QC has not independently reproduced that failure. Java formatting is reported passed by main; QC independently executed only the import check. Sonar remains paused.

## Pending runtime evidence and concrete cases

Six dashboard backend integration methods are present: aggregate counts/health/live reads, input/access/empty behavior, fresh organization, stable pagination/team filters, query budget, and schema rejection. The query-budget harness instruments physical statements over a real application connection inside a repeatable-read transaction; assertions are present but not yet executed. `application-test.yml` uses H2 PostgreSQL mode, so this suite alone cannot establish native PostgreSQL behavior.

Seven browser cases are present, configured for 1440/768/390: real snapshot/pending explanation, DB transition refresh, foreign org/team isolation, empty data, membership revocation on reload, transport retry and compact navigation/team filter. Disposable SQL fixtures are wired into Docker CI against real PostgreSQL; mutation cases restore fixtures in finally and browser configuration is serial. Test presence is not a passing result.

Before development gate approval, collect actual quality and Docker results for the final published application revision, preserving existing login/recovery/workspace regressions. Confirm real PostgreSQL migration/fixture success and all seven dashboard cases across three configured viewports. Investigate actual failures; do not require optional breadth merely to close this bounded gate.

Useful follow-up cases if runtime reveals a concern: hold Alpha overview/task responses while switching organization/filter and release them late; verify no stale data/error can replace the new selection. Concurrent-write snapshot probing, broader failure injection and exhaustive accessibility are not presently source blockers. Record any final changes after this checkpoint and recheck affected areas before claiming acceptance.

## PR 5 source recommendation

Main reports published revision `dd8ecb1156b3498397126b572aad3358900ca5b9`, no subsequent application changes, and CI started. **Recommend proceeding through the bounded development CI gate; no source blocker identified.** This is not runtime approval or a merge/production recommendation.

QC could not independently bind the reviewed working tree to that SHA: the shared checkout remains at `42ae812` with implementation changes uncommitted, and the published commit object is unavailable locally (`git show`: bad object). The source recommendation therefore covers the inspected working tree; attribution to the published SHA relies on main's statement.

Main expects 39 backend tests and 54 browser instances. These are expected counts, not results. Require actual completed quality/Docker logs for the published revision before recording a passing development gate. Mocked-HTTP static visual checks can establish layout observations only; they do not replace real PostgreSQL/browser evidence.
