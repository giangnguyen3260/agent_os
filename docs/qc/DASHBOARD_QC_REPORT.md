# Dashboard independent BA/QC review

Review date: 2026-10-19. Branch: `feature/dashboard-live`. Baseline HEAD: `42ae812` (shared working tree, not an immutable implementation revision). Reviewed `docs/engineering/DASHBOARD_DELIVERY_CONTRACT.md`, routes, dashboard component, initial schema, organization security, quality workflow and browser configuration. QC owns only this report; no application changes made.

## Disposition

**Blocked for dashboard acceptance: delivery is in progress and executed dashboard evidence is absent.** Initially only the untracked contract was present. Agent application changes appeared during review; the follow-up below supersedes the initial absence finding. Reinspect the final diff before acceptance. No CI pass is claimed.

The scope is appropriately bounded to one organization overview and task drilldown. Preserve unique RUNNING tasks as Running, unique WAITING/BLOCKED tasks as Pending, and IN_REVIEW tasks as Review. Never substitute missions, approval totals or blocker totals. Resource allocation, pipeline execution and billing are not prerequisites for this read flow; their absence requires explicit unavailable states, not fabricated utilization, performance or currency. Keep mutation, creation and other module workflows unavailable.

## Contract decisions needed before acceptance

| ID | Gap and concrete resolution | Acceptance evidence |
| --- | --- | --- |
| DQC-01 | “Excessive page” has no bound. Specify a numeric maximum for both page parameters, integer parsing rules and behavior for a valid page beyond the last result. Prefer 200 with empty items and accurate total for valid out-of-range pages; do not silently clamp. | Boundary tests for -1, 0, max, max+1, integer overflow and malformed input on both endpoints. |
| DQC-02 | `resolver` has no wire type or disclosure rule. Specify whether it is a display string or structured identity, required fields, and permitted identity sources. Define nonblank reason/resolver and nonnull waitingSince for both WAITING and BLOCKED, and nullability for other states. Do not expose email or foreign identities accidentally. | Schema and response assertions for each pending state; reject null/blank pending metadata. Verify nullable fields on nonpending tasks and render the agreed representation. |
| DQC-03 | The new durable task model needs explicit tenant and relationship constraints. Define task status domain, team requirement, mission optionality and whether task team must match mission team. Require referenced team/mission to belong to the task organization. Existing independent organization/team foreign keys in the initial schema alone do not prove this. | PostgreSQL rejects cross-organization task/team and task/mission inserts; reads scope every join to the organization even if legacy relationships are inconsistent. No unrelated schema redesign required. |
| DQC-04 | Stable ordering is not a snapshot across requests. Define created_at ascending/descending and acknowledge that independently refreshed offset pages can shift during inserts/deletes. One repeatable-read transaction covers snapshot, team counts and team items in a single overview response, not subsequent pages or the task endpoint. Define generatedAt as the database snapshot acquisition time or an explicitly approximate response timestamp. | Tied names/timestamps page deterministically by ID on unchanged data. Concurrent writer cannot create mixed aggregates within one overview response. UI does not promise task-list totals equal an older overview after a live update. |

Health precedence is clear. Interpret MANAGER as persisted `AgentLevel.MANAGER`, not a free-text role. Agent totals include persisted archived agents; activeAgents includes only ACTIVE. Archived teams and their tasks remain included. Define a bounded query budget in implementation evidence so “constant query count” can be verified rather than inferred from a small fixture.

## Actionable acceptance cases

All dashboard cases below are **not executed** at this checkpoint.

| Case | Setup and required result |
| --- | --- |
| DQC-T01 Real counts and deduplication | Alpha: 2 RUNNING, 1 WAITING, 1 BLOCKED, 1 IN_REVIEW, plus terminal tasks. Add multiple agents to each team and unrelated missions/audit records. Require Running=2, Pending=2, Review=1, each drilldown ID exactly once, matching team counts and uninflated agent counts. Add archived-team tasks and verify inclusion. Approval-like records never increase Pending. |
| DQC-T02 Empty and unavailable | Fresh organization has no task rows and all task metrics are zero. Capacity/performance have available=false and meaningful reasons. No sample names, percentages, prices or limits appear. No production fixture seeding. |
| DQC-T03 Tenant authorization and disclosure | Anonymous gets 401; authenticated nonmember gets 403 for both organization dashboard endpoints. Alpha member filtering a Beta team or nonexistent team gets equivalent 404 without names/counts/IDs. Remove membership after a successful read and retry both endpoints: 403. All task/team/mission joins remain tenant scoped. |
| DQC-T04 Health precedence | Archived team with no manager and pending tasks is ARCHIVED. Nonarchived team without ACTIVE MANAGER is SETUP_REQUIRED even with pending/degraded records. With active manager, pending task or DEGRADED agent yields ATTENTION; otherwise NORMAL. Health reason explains the winning rule without claiming runtime telemetry. |
| DQC-T05 Pagination and bounds | At least 41 teams and 41 tasks, duplicate names and creation times. Fixed size 20, correct totals, deterministic ID tie-break, no duplicate/missing records across unchanged pages. Check both state aliases WAITING/BLOCKED map to PENDING; unknown state and invalid UUID/page inputs use agreed errors. Team and task pagination do not alter one another. |
| DQC-T06 Database integrity and queries | Apply migrations on real PostgreSQL, validate pending metadata and tenant constraints, and inspect indexes for organization/status/team/order access paths. Compare SQL statement counts for 1 versus 20 returned teams: no per-team queries. Coordinate a writer between overview reads and assert one consistent transaction snapshot. |
| DQC-T07 Request races and access loss | Delay Alpha success, switch to Beta, then release Alpha response: no Alpha data returns. Repeat with stale error/401, state/team/page changes and component exit. Current 403/401/network/5xx clears protected stale content and offers the appropriate recovery. Existing global auth-epoch tests alone do not verify dashboard state. |
| DQC-T08 Single real flow | Existing context heading remains; open protected /workspace/:id/dashboard. Running, Pending, Review and Team Open all use the actual inline list; Pending shows reason, wait start and resolver. View teams focuses actual list; switch workspace and logout work. Unsupported modules and creation show explanations and cannot navigate to static sample pages. |
| DQC-T09 Explicit refresh and viewports | Update tasks through test-only PostgreSQL fixtures, explicitly refresh and observe new counts/list. At 1440/768/390 verify desktop sidebar, compact menu/dialog, metric columns, long text, loading/empty/error/retry, no horizontal overflow, and reset/clamp behavior when totals shrink. Do not call this realtime. |
| DQC-T10 Regression and gate | Retain login/recovery/workspace suites, use real PostgreSQL Alpha/Beta dashboard fixtures, and collect actual final-revision integration/browser results and CI job links. Keep Sonar paused. Test presence, fixture-only rendering and historical workspace CI are not dashboard runtime acceptance. |

## Independently executed local checks

| Command | Actual result and limit |
| --- | --- |
| `python3 scripts/check-java-imports.py` | PASS, exit 0. Import style only. |
| `cd frontend && node scripts/test-auth-generation.cjs` | PASS, exit 0: delayed session success and delayed unauthorized response cannot overwrite a newer account. Existing service harness, not dashboard/browser evidence. |
| `cd frontend && npm run build` | FAIL, exit 1: cannot resolve `@fontsource/inter/400.css`, `600.css`, `700.css` imported by src/styles.css. Package is declared in package.json; local dependency installation is incomplete. Reinstall the lockfile dependencies in the build environment and rerun before attributing a source regression. |
| Runtime prerequisites | `command -v mvn` and `command -v docker` found neither; `java -version` reports 17.0.20, while pom.xml requires 21. Backend/PostgreSQL/browser runtime suites were not attempted. Node is v24.19.0. |

## Follow-up ownership

Implementation owners resolve DQC-01 through DQC-04 in the contract/API/schema and provide dashboard source/tests. Record final tested commit plus any dirty diff, exact commands, counts, failures and CI URLs before changing disposition. Final implementation/runtime review remains pending.

## Follow-up: agent changes observed during review

Inspected the newly written DashboardService, DashboardController, V004 migration, dashboard frontend service/component and Docker fixture wiring. Working tree remains in progress on the same HEAD.

- V004 source addresses the main DQC-03 integrity concern: composite organization/team/mission foreign keys, required mission/team, bounded status domain and pending metadata checks. Nonpending metadata must be null. PostgreSQL execution remains unverified. The new constraint on existing missions may reject legacy cross-tenant mission/team rows; migration tests should include representative existing data.
- DQC-01 implementation selects MAX_PAGE=107374182, fixed size 20, and empty results beyond the final page. Task ordering is created_at/id ascending. Document these in the contract and test boundaries. A signed-in request with missing required state should also return 400.
- Resolver is a nullable string in the DTO and varchar(255) in storage. WAITING/BLOCKED require nonblank reason/resolver and a timestamp. Document the display/disclosure rule and required mission in the contract.
- Summary uses repeatable-read, five database statements, task aggregation without agent joins, and separate agent/task aggregates before joining teams. Task reads use two statements, or three with a team filter, in repeatable-read. Organization scope and parameterized values are present; state SQL is chosen from a fixed switch. No approval-count summation or fabricated capacity/performance was observed.
- Frontend source clears data at overview load, cancels task requests, guards responses by generation and clears protected state on 401/403. This is a favorable source finding, not proof of race behavior. Independently refreshed task pages may legitimately disagree with earlier overview totals.

Concrete in-progress blockers observed at this checkpoint:

| ID | Finding | Required resolution |
| --- | --- | --- |
| DQC-05 | Independent Java import check now FAILS: DashboardController.java line 5 uses a wildcard import. | Replace with explicit imports and rerun the repository import check and required formatting. Initial baseline import PASS does not cover the new files. |
| DQC-06 | Component references dashboard-page.component.html/css, but neither file exists at the inspected checkpoint. Required workspace/:id/dashboard route is also absent. | Finish template/styles and protected route/context entry, then run Angular build/template compilation. TypeScript-only checking cannot establish these resources or route behavior. |
| DQC-07 | No executed dashboard integration/browser evidence; fixture wiring alone is insufficient. | Execute DQC-T01 through T10 with final revision and retain results before gate approval. |

Independent follow-up command: `cd frontend && ./node_modules/.bin/tsc --noEmit -p tsconfig.app.json` PASS, exit 0. This checks TypeScript only, not Angular external templates, migrations, HTTP behavior or browser rendering. No further build pass is claimed. The source is changing concurrently; checkpoint findings may be resolved by later agent edits and must be rechecked.
