# Live organization overview contract

Scope: Forest S01 43:1494, mobile 60:3756, tablet 60:10372. This is dashboard persistence/read delivery, not an execution engine, task mutation UI, team creation wizard or full approval workflow. Prior workspace/login/recovery branches remain unmerged. Stack this branch on feature/auth-workspace.

## Metric semantics

Running = unique task rows with RUNNING. Pending = unique WAITING or BLOCKED task rows, never approval-request counts or summed blockers. Review = IN_REVIEW, distinct from Running. Mission counts never stand in for tasks. Pending records require reason, waitingSince and resolver. Fresh installations have zero actual task rows, not sample data. Task tables are a durable foundation for subsequent lifecycle delivery; this slice exposes no public task mutation API.

Team health precedence: ARCHIVED for archived teams; SETUP_REQUIRED for teams with no ACTIVE MANAGER agent; ATTENTION for teams with pending tasks or DEGRADED agents; NORMAL otherwise. It is a stated operational rule, not proof of runtime health. Agent totals count all persisted agents; activeAgents counts ACTIVE. Team totals include archived teams, whose status is shown. Tasks in all team statuses are included in organization totals.

Capacity and performance/cost remain explicitly unavailable: resource allocator, execution attempts and billing ledger do not exist. Do not fabricate limits, utilization, success percentages, cycle times or currency. Keep these design panels with clear no-data explanations. No production example seeding.

## API agreement

All responses use ApiResponse.data and existing organization membership security. GET /api/v1/organizations/{organizationId}/dashboard?teamPage=0 returns:

- generatedAt: ISO instant
- snapshot: {running,pending,review,totalTeams,totalAgents,activeAgents} integer counts
- capacity: {available:false,reason:string}; performance: {available:false,reason:string}
- teams: {items:[{id,name,status,agentCount,running,pending,review,health,healthReason}],page,size,total}; fixed size20, stable name/id ordering.

GET /api/v1/organizations/{organizationId}/dashboard/tasks?state=RUNNING|PENDING|REVIEW&teamId=<optional UUID>&page=0 returns {items:[{id,title,status,teamId,teamName,missionId,pendingReason,waitingSince,resolver}],page,size,total}. Fixed size20 stable created_at/id ordering. Page range is 0..107374182 inclusive, parsed as signed Java int; negative, above-max, malformed or overflow values ->400. Valid pages beyond the result return200 with emptyitems and accurate total; no silentclamping. Missing/unknown state ->400. Task ordering is created_at ascending, id ascending. Resolver is a required nonblank display string (max255) on pending tasks, not an authenticated identity or email lookup. It is stored operational responsibility text visible to organization members; future writers must not resolve or expose foreign-account personal data. Pending reason is nonblank and waitingSince is nonnull; all three are null for nonpending statuses. Foreign/missing team filter ->404 without metadata. Organization nonmembers ->403 (existing policy); anonymous ->401. All queries, joins and pages scoped by organization. Use bounded constant query count and suitable indexes. Summary uses five constant SQL statements; tasklist uses two, or three with a team filter, independent of page item count. Snapshot/team aggregate reads use one repeatable-read transaction; generatedAt is the database transaction timestamp taken at the start, an approximate freshness indicator rather than an exact event watermark. Offset pages are independent live reads and may shift on concurrent inserts/deletes. Refresh is explicit, no phantom realtime claim. Task pages are separately refreshed live reads.

## Frontend agreement

Protected /workspace/:id/dashboard, entry from existing context screen with actual context heading preserved for prior tests. New dashboard component replaces old static dashboard source. It owns a Forest shell for this slice: desktop224sidebar+32padding, <=1023 compact44pxMenu/dialog, tablet24padding/mobile16, 4metriccolumns desktop/2compact. All business names/roles/counts from API. Running/Pending/Review buttons open an inline live list with state/team filters, paging, pending reason, wait start and resolver. Team Open action opens same team-filtered task panel, not a fake team workspace. View teams scrolls/focuses actual list. Other modules/creation/membership navigation remain visibly unavailable with explanation, never link to static samples. Workspace switch and logout work. Handle loading/empty/error/retry/access loss and stale requests on route/filter changes; never retain previous tenant data on failure. Team list pagination and task pagination independent. No localStorage authority.

## Verification

Backend integration tests: mixed-task counts/deduplication, empty state, team health, foreign org/team isolation, revocation, input validation, pagination, DB constraints. Real PostgreSQL CI fixtures only: Alpha with tasks/teams and hidden Beta; all prior login/recovery/workspace tests retained. Browser tests at1440/768/390 verify metrics, pending reasons, drilldown, refresh after DB update, missing permission, empty org, menu and no overflow. Sonar remains paused. Exact CI evidence required before development gate.

## Persistence and rollout

Tasks have required organization, team and mission; composite foreign keys require all three references to agree, including the task team matching its mission team. Status domain: DRAFT, READY, RUNNING, WAITING, BLOCKED, IN_REVIEW, COMPLETED, FAILED, CANCELLED. V004 also constrains existing mission organization/team consistency. Legacy inconsistent data causes migration to fail closed for operator review; never silently repair tenant ownership or delete data. Existing valid rows and unassigned missions remain supported; tasks require a team-assigned mission.
