# Implementation gap review

Review date: 2026-10-18. Inspected local source: `467acd1` (working tree clean at review start). User-confirmed GitHub main baseline: `207fced`; application source is equivalent for this audit, while local workflow/README content is older. Remote equivalence was supplied by the user, not independently fetched.
Perspective: solution architecture, backend, and frontend technical review.
Scope: repository source inspection only; no application code changes, live endpoint probes, dependency research, or execution of application tests. Findings describe code paths, not a newly verified deployment. Paths below are repository-relative.

## 1. Assessment

AgentOS is a persistence foundation plus an Angular preview, not a functionally complete autonomous organization product. Four controllers expose 14 application HTTP operations backed by five JPA tables. Create/read behavior is real database behavior when the application is running, but authentication, authenticated tenancy, RBAC, execution, approval workflows, accounting, and frontend API consumption are missing. A successful HTTP response or rendered screen must not be counted as completion of the business capability it resembles.

Do not wire these endpoints into a shared or externally reachable product before implementing the security foundation and fixing trace isolation. The browser UI is not the security boundary. `frontend/docker/default.conf` already proxies `/api/` without authentication, so endpoints can be called directly even though the preview does not consume them. Root `compose.yaml` binds the frontend to loopback and keeps backend/database ports private; this reduces exposure but supplies no application identity or tenant authorization.

The selected first delivery is **login/authentication and the security foundation** on the coordinator-created `feature/auth-login` branch, governed by `docs/planning/AUTH_LOGIN_DELIVERY_CONTRACT.md`. `docs/planning/DELIVERY_COORDINATION.md` is authoritative for delivery order and the subsequent BA one-screen queue; canonical UI is v0.3. The coordinator-created audit branch is `review/screen-delivery-audit`. Mission intake below is an unselected technical alternative for later delivery: select an authorized organization, create a mission, reload its persisted details/list, and inspect its creation event. Keep planning/execution explicitly unavailable until implemented. This delivers a verifiable business outcome using existing persistence without pretending to run agents.

## 2. Actual API inventory

All paths below are under `/api/v1`. `O` denotes `/organizations/{organizationId}`. All application controllers currently have no authentication or role enforcement. Success responses use `{data, meta: {requestId}}` via `common/api/ApiResponse.java`; POST methods currently return the default HTTP 200, not a creation/async-specific status.

| Method and path | Actual implementation | Important limit / functional status |
| --- | --- | --- |
| POST `/organizations` | `OrganizationService.create`: normalize slug, check existence, persist organization | Real create; no creator membership, ownership, provisioning authorization, or audit |
| GET `/organizations` | `OrganizationService.list`: repository `findAll` | Real global list; exposes all organizations rather than caller memberships |
| GET `O` | `OrganizationService.get`: lookup by ID | Real lookup; organization existence is not permission |
| POST `O/teams` | Verify organization exists, persist team | Real create; no role check, reporting structure, or audit |
| GET `O/teams` | Filter teams by organization ID | Real list; no membership check; nonexistent organization can produce empty list |
| POST `O/agents` | `AgentService.create`: verify organization and optional team's organization, persist | Real create with default MID/SUGGEST and DRAFT status; no runnable agent setup |
| GET `O/agents` | Filter agents by organization ID | Real list; no caller authorization or pagination |
| GET `O/agents/{agentId}` | Lookup then compare stored organization ID | Resource/path consistency exists; no authenticated tenant authorization |
| POST `O/missions` | Verify organization exists, persist mission and creation audit in transaction | Real intake; optional team/manager ownership unchecked; status CREATED |
| GET `O/missions` | Filter missions by organization ID | Real list; no pagination, authorization, filtering contract, or execution metrics |
| GET `O/missions/{missionId}` | Lookup then compare stored organization ID | Real details; no state progression or task graph |
| POST `O/missions/{missionId}/plan` | Check mission/path organization consistency; append planning-requested audit; return existing mission | Audit-only placeholder: no planning call, job, state change, tasks, or result |
| GET `O/audit-events` | `TraceService.listAuditEvents`: organization-filtered, newest first | Real audit rows; unauthenticated, unbounded, no actor details in response |
| GET `O/missions/{missionId}/trace` | Controller discards organization ID; service queries only mission ID, oldest first | **Tenant isolation defect**; no mission ownership/existence check; not an execution trace |

Evidence: `backend/src/main/java/com/agentos/{organization,agent,mission,observability}/api/*Controller.java`, corresponding application services and repositories. `OrganizationService.getTeam` exists internally but has no dedicated team-detail controller mapping. There are no update/delete APIs in these controllers. Endpoints described in `API_BOUNDARY.md` beyond this inventory are designs, not implemented routes.

Operational health is separate: Actuator is a dependency; the Docker profile exposes health and readiness internally, Nginx blocks `/actuator`, and `/healthz` is a static Nginx response. None verifies authorization or business workflow correctness.

## 3. Persisted entities versus designed entities

The actual schema is `backend/src/main/resources/db/migration/V001__create_core_tables.sql`; only five JPA entity classes exist. SQL columns, DTO fields, business invariants, and functioning workflows are distinct layers.

| Entity/table | Persisted behavior and values | Missing functionality |
| --- | --- | --- |
| Organization / `organizations` | UUID, name, normalized unique slug, description, ACTIVE default, timestamps | Users/memberships, role ownership, constitution/policy, update/archive, audit |
| Team / `teams` | Organization FK, name, purpose, ACTIVE default, timestamps | Hierarchy/reporting lines, membership and lifecycle; no unique team-name rule |
| Agent / `agents` | Organization/team IDs, name, role, level, autonomy, DRAFT default, timestamps | Model configuration, skills, tools, permissions, contracts, budgets, activation, performance/health. `reports_to_agent_id` exists but create DTO/response do not manage it |
| Mission / `missions` | Organization/team/manager IDs, title, goal, priority, optional decimal budget, CREATED default, timestamps | Tasks, execution state transitions, progress, results, spend, start/pause/resume/cancel. `created_by_user_id` exists but constructor never populates it and response omits it |
| Audit event / `audit_events` | Organization/mission IDs, event type, text payload, timestamp; actor defaults SYSTEM | Real human attribution; actor/task fields not populated by current service; response excludes actor. Only mission creation/planning request call `AuditService.record` |

Absent persistence/application capabilities include users and organization memberships/RBAC, agent contracts, skills and agent skills, tools and grants, model providers/configuration, tasks/dependencies/assignments, execution runs, model/tool call usage, approval requests and policy rules, memory entries, artifacts, and budget reservations/ledger. Names in `DOMAIN_MODEL.md`, `DATABASE_ERD.md`, `MISSION_STATE_MACHINE.md`, enums, or request records are not evidence those workflows exist.

Database FKs establish referenced-row existence, but separate organization and resource FKs do not ensure both rows belong to the same tenant. In particular mission team/manager links can reference another organization's rows. There are no composite tenant FKs or database row-level security policies in V001. Audit organization/mission consistency also lacks a database-level tenant invariant. Existing agent team validation is useful application-level consistency, not authentication.

## 4. Stub and runtime boundary inventory

| Source under `backend/src/main/java/com/agentos/` | Actual behavior | Completion implication |
| --- | --- | --- |
| `orchestration/application/OrchestratorService.java` | Empty `requestMissionPlanning()` method, no mission parameter | Not called by mission plan endpoint; no orchestration/worker/queue |
| `governance/infrastructure/DefaultGovernanceEngine.java` | Always returns ALLOW with “Default MVP policy” | No policy evaluation, denial, approval creation, or enforcement; not invoked by current mission flow |
| `model/infrastructure/StubModelGateway.java` | Fixed response text and zero token counters | No provider invocation, routing, usage measurement, retry, or cost |
| `tool/infrastructure/StubToolGateway.java` | Returns STUBBED with empty output | No external side effect, permission check, sandbox, approval, or result handling |
| `resource/application/ResourceManager.java` | Empty service | No cost/token/rate/concurrency enforcement |
| `memory/application/MemoryService.java` | Empty service | No retrieval, scoping, persistence, or promotion |
| `common/application/CurrentActor.java` | Record plus SYSTEM factory | No principal resolver, authenticated identity, tenant, or permission context |
| `common/infrastructure/security/SecurityConfig.java` | Comment-only configuration | No filter chain or security behavior; POM has no Spring Security starter |

`application.yml` retry/delegation/cost/approval-timeout settings are configuration declarations, with no enforcing implementation in these services. Redis in Compose is infrastructure availability, not proof of an event bus, job queue, or distributed locks. `DomainEvent` and audit rows do not establish reliable event delivery.

## 5. Frontend screens: fixtures and actual function

Evidence: `frontend/src/app/app.routes.ts`, `frontend/src/main.ts`, `frontend/src/app/core/layout/shell-layout.component.ts`, and `frontend/src/app/features/*/*component.ts`.

`provideHttpClient()` is registered, but no feature injects/uses HttpClient or calls fetch. Feature classes are empty; business create buttons have no handlers. Router navigation, lazy loading, wildcard 404, and shell mobile dialog handlers are implemented presentation behavior. They do not demonstrate business workflow completion. The shell explicitly says “Preview workspace” and that runtime data is not connected; retain clear disclosure while fixtures remain.

| Screen | Fixtures / unsupported claims | Actually functional now | Truthful next delivery |
| --- | --- | --- | --- |
| Shell | Agent_AI workspace, engineering organization identity | Navigation, active links, mobile navigation open/close | Authenticated identity, membership-derived organization picker, tenant-scoped request/cache context |
| Dashboard `/dashboard` | 12 agents, +3 weekly, 4 running missions, 3 approvals, $83.42 October spend/$116.58 remaining; mission percentages, health/provider labels, approval items and timeline are literals | Static layout | Scoped counts of persisted records only; unavailable metrics remain unavailable |
| Organization `/organization` | “You → AI Manager → Product/Engineering/QA” hierarchy | Static diagram | Read authorized organization and persisted teams; do not infer reporting lines from team existence |
| Agents `/agents` | Maya/Emma/Alex cards, model labels, success rates, dollar costs, status and permissions matrix | Static cards/table; Create Agent inert | Persisted agent create/list/details using actual DTO fields; omit unsupported configuration/telemetry |
| Missions `/missions` | Running/review states, task phases, $12.40/$20 and $8.10/$15, model/tool call counts | Static board; Create mission inert | Mission intake/list/details/creation audit, showing actual CREATED status and stored budget |
| Approvals `/approvals` | “Nothing is waiting for approval” is hardcoded, not a verified empty queue; contradicts dashboard's three items | Static placeholder | Explicit unavailable state until backend queue and decision workflow exist |
| Unknown route | No business fixtures | 404 component and dashboard navigation | Keep existing routing behavior; test authenticated navigation/return paths |

### Business value provenance rules

- Stored `budgetLimit` is a user-supplied ceiling, not spend, remaining budget, reserved capacity, or an enforced limit. No currency field/accounting semantics exist; specify those before financial presentation.
- CREATED/DRAFT/ACTIVE constructor defaults are genuine stored initial values, not proof of execution, agent availability, or health. Agent ACTIVE counts must not be sold as live process health.
- Titles, goals, names and creation timestamps from APIs are persisted business inputs/metadata. A creation event is real history; a planning-requested event proves only that request was recorded.
- Zero tokens from the model stub are synthetic. Missing telemetry must render “Unavailable” or “Not measured,” never `$0`, `0 calls`, or fabricated success rates.
- A successful, authorized list returning `[]` supports an empty state. Loading, forbidden, network failure and unimplemented queue are different states. Never substitute fixtures after a failed production request.
- Dashboard totals require explicit tenant scope, status predicate, time window/time zone, currency and data freshness. Current unpaginated lists can support small-scope record counts, but not execution progress, weekly growth, spend or task completion.

## 6. Prioritized risks and required controls

Severity refers to connecting real/shared data or future execution, not an assertion of an observed exploit in a deployed environment.

| Priority | Finding and concrete consequence | Required implementation / validation |
| --- | --- | --- |
| P0 | No authentication or membership/RBAC checks: callers can list all organizations and create/read resources under known organization IDs | Add verified identity, membership and default-deny authorization to all application routes; membership-filter organization list; restrict org provisioning; test anonymous and nonmember requests |
| P0 | Trace endpoint ignores path tenant: request organization A with mission B returns B's audit records | Pass both IDs through service/repository; authorize organization, require mission ownership, query both keys; test mismatch and direct access as A-only member |
| P0 | Mission creation accepts foreign team/manager IDs; DB FKs accept existing cross-tenant references | Resolve team/agent through scoped application contracts, validate tenant and manager eligibility; strengthen relational constraints where appropriate; reject atomically with no mission/audit write |
| P0 before real execution | Governance always ALLOW; no resource enforcement or approval persistence | Replace default allow with deny-by-default policy and explicit grants; enforce authorization/governance/budget in server execution path before side effects; never enable real tools/models by swapping adapters alone |
| P1 | `/plan` returns success without planning and permits repeated audit writes | Keep action unavailable in first slice; later implement validated state transition, durable job/outbox, idempotency and retry-safe processing; use truthful command/status contract |
| P1 | Audit title constructed by JSON string concatenation; quotes/newlines produce malformed payload; every actor SYSTEM | Serialize structured payloads, capture authenticated actor and correlation ID; limit/redact sensitive content; test escaping and transaction rollback |
| P1 | No full input bounds: title/name lengths can exceed schema; budget has no positivity/precision validation; slug normalization may become empty and uniqueness precheck races | Add DTO/domain constraints and normalized-slug validation; translate unique/FK violations into stable errors; define budget unit/range/scale; test concurrent duplicate slugs |
| P1 | Unbounded list/audit queries and unrestricted repeated writes | Add pagination, stable sort/filter contracts, payload limits and appropriate per-actor/tenant rate limits; exercise large datasets |
| P1 | No optimistic version/state guard, durable asynchronous dispatch or command idempotency | Add when lifecycle/decision commands arrive; prevent duplicate plans, approvals, charges and side effects under concurrent retries |
| P1 | UI fixtures imply working governance, costs and progress | Replace with sourced fields or explicit unavailable states; enforce production fixture exclusion; test error/empty/denied states independently |
| P2 | Error contract incomplete; validation discards field details; request IDs are generated per response with no demonstrated log propagation | Specify 400/401/403/404/409 behavior and field errors; correlate request, audit and logs without leaking secrets |

Follow the selected same-origin server-session and CSRF contract in `docs/planning/AUTH_LOGIN_DELIVERY_CONTRACT.md`; configure allowed origins deliberately. Same-origin proxying, CORS, UUID obscurity, hidden buttons, container hardening and loopback binding do not replace authorization. Preserve private management endpoints and controlled ingress while adding the application controls.

### Proposed tenant/RBAC contract

This is a proposal, not an existing role system. Use the selected persisted human-account/server-session contract and an organization membership relation with explicit permissions; model agents are not human accounts. Resolve actor server-side; never trust an actor ID/role from a request body. A path organization selects scope only after checking membership. Repeat authorization in application service entry points so future workers/internal callers cannot bypass it.

| Proposed permission group | Allowed business operations |
| --- | --- |
| Viewer | Read authorized organization, teams, agents and missions; audit only with explicit audit-read grant |
| Operator | Viewer plus create missions and, in a later slice, request allowed lifecycle actions |
| Organization admin | Manage teams/agent configuration and membership within own tenant; policy changes separately authorized |
| Approver | Decide eligible tenant approval requests only; scoped grant, exact immutable action payload, expiry and separation-of-duty rules |

Organization provisioning should be a controlled onboarding/platform operation that atomically grants creator ownership, not unrestricted POST access. Define stable external behavior: 401 without valid identity; 403 for known tenant membership lacking permission; 404 for foreign/nonexistent resource IDs to avoid resource enumeration. Scope all child references, trace queries, caches, background jobs and future event streams. Membership in tenant A alone must never authorize a request to tenant B, even when both IDs are valid.

## 7. Later technical alternative: secure mission intake (not selected first screen)

This bounded alternative is retained for technical reference only. Login/auth plus the security foundation on `feature/auth-login` is the selected first delivery. Scheduling any mission work requires the BA one-screen queue under `docs/planning/DELIVERY_COORDINATION.md`. The sequence and acceptance evidence below apply only if that later scope is selected.

### User outcome and bounded scope

An authenticated operator selects an organization they belong to, submits a title/goal with optional authorized team and manager plus a validated budget ceiling, sees the newly created mission, reloads the page, and reads its creation event. A viewer can read but cannot create. No model/tool execution, planning, fabricated task graph, cost accounting or approval decisions is included in this slice.

### Implementation sequence

1. **Security and tenant foundation:** introduce identity verification, user/membership persistence and controlled test/onboarding provisioning. Gate all existing application routes, including `/plan` and observability, before exposing any real-data screen. Add scoped permission checks and fix the trace query; prevent mission foreign-tenant references. Keep `/plan` disabled/unavailable until its semantics are implemented.
2. **Mission contract and persistence:** retain create/list/get routes and response envelope; validate lengths, budget semantics, team/manager ownership and eligibility. Populate creator and actor from the principal. Serialize audit payload safely; preserve atomic mission+audit commit. Add pagination and deterministic ordering for mission/audit lists, with an agreed frontend contract. Define duplicate-submit handling explicitly.
3. **Frontend shell and intake:** add authenticated route/session behavior and membership organization picker; typed API client unwraps `data`, handles structured errors and cancellation. Tenant change clears cached data, resets forms and cancels/discards old in-flight results. Implement reactive mission form, pending-submit lock, list and detail/readback with actual statuses and budget ceiling labels.
4. **Audit readback:** expose only authorized mission events using both tenant/mission keys. Render creation actor/time and event content safely. Label as mission history, not execution telemetry. Disable planning with a clear explanation of availability.
5. **Independent verification:** execute the security/integration/browser scenarios below; attach actual results to QC evidence. Only then mark this bounded feature complete.

### Required acceptance evidence

- Authorized operator create → database commit → reload list/details → scoped creation audit succeeds with real PostgreSQL migrations, not only H2 schema generation.
- Anonymous GET/POST is rejected; viewer POST denied; nonmember org list/details/create/trace denied; no rejected request creates a row/event.
- Two tenants with distinct teams, agents and missions: reject cross-tenant mission references; mismatched trace path leaks no records; changing the URL cannot bypass permission checks.
- Quotes, Unicode and newlines produce valid serialized audit payloads; invalid lengths/budget precision or negative amounts return field errors; forced audit failure rolls back mission creation.
- Form displays loading, validation, empty, forbidden and server/network errors correctly; retry/double click follows agreed duplicate policy; refresh persists values; tenant switch cannot briefly show another tenant's stale response.
- Rendered browser test covers keyboard form submission, validation announcements, navigation and narrow viewport. No fixture metrics appear as real values. Stored CREATED is not relabeled Running or Planned.
- Backend verify, FE lint/format/build, meaningful integration/E2E tests actually pass. Apply the user-approved temporary Sonar pause explicitly; do not claim a Sonar pass. Other required blocked checks keep the slice unverified, and restore the Sonar gate according to the agreed exception condition.

## 8. Branch and PR strategy by screen

Authoritative delivery order: follow `docs/planning/DELIVERY_COORDINATION.md`. The coordinator has created `review/screen-delivery-audit` for audit/planning and `feature/auth-login` for the selected first delivery, login/auth plus the security foundation, as specified in `docs/planning/AUTH_LOGIN_DELIVERY_CONTRACT.md`. Then follow the BA one-screen queue. Canonical UI is v0.3. The later screen rows below describe technical dependencies, not a competing delivery order or completed implementation. The user-approved temporary Sonar pause must not be reported as a pass.

Use short-lived branches and reviewed PRs into `main`, one coherent contract/behavior change per PR. The two coordinator-created branches are identified below; other names are suggestions for later BA-selected work. This review creates no branches. Avoid a long-lived “connect all screens” branch. Shared foundation must merge before dependent screens are exposed; parallel screen work can use explicit test doubles in tests/development only. Keep schema migrations additive and uniquely numbered after V001; coordinate allocation across concurrent branches. Merge backend contracts before their consumers, or together in a small full-stack screen PR.

| Screen / shared scope | Proposed branch | Dependency and concrete scope | Merge / completion gate |
| --- | --- | --- | --- |
| Login/auth + shared security foundation (selected first) | `feature/auth-login` (created) | Identity/membership/RBAC, scoped service contracts, trace fix, mission child validation, actor attribution and negative security tests | All existing routes protected; two-tenant regression tests pass; update anonymous API smoke expectation |
| Shell / session | `feature/auth-login` for contracted login/session behavior; later shell scope per BA queue | Follow selected auth contract: login/session handling, membership picker, typed client/error mapping and cache isolation | No unauthorized tenant selection; logout/401 clears state; switching tenants cancels stale results |
| Missions, later alternative | `feat/missions-secure-intake` | After both foundations: bounded create/list/detail/history flow from section 7, validation and truthful fields | Full persisted readback and denial E2E; no planning-completion claim |
| Organization | `feat/organization-teams-read-create` | After foundations: scoped organization details and team list; admin-only team create | Viewer/admin matrix, empty/error states and tenant-safe references; hierarchy/constitution stay unavailable |
| Agents | `feat/agents-registry-intake` | After foundations and team selector contract: create/list/detail with name, role, level, autonomy, optional team | DRAFT displayed accurately; foreign team denied; no model/skills/tool grants implied by static matrix |
| Dashboard | `feat/dashboard-persisted-overview` | After mission/agent read contracts: real scoped record counts and limited creation history; consider dedicated aggregation endpoint for scale | Counts reconcile to scoped records; unsupported spend/health/progress/approval panels unavailable; no N+1 full-history fetch pattern |
| Approvals | `feat/approvals-queue-decisions` | Later, after policy and action execution contract: approval entity/migration, queue/detail, immutable scoped decisions, expiry, concurrency/idempotency and audited resume | Approver separation rules, duplicate/expired/foreign decisions denied; execution resumes once and only for approved payload |
| 404 / navigation | `test/navigation-session-states` | After shell: verify unknown routes, return paths and session expiry across lazy pages | Browser navigation/keyboard checks; no unrelated business code |

Follow-on mission planning belongs in `feat/missions-durable-planning`, after task persistence/state machine, outbox/worker, enforcing governance/resource controls and explicit retry/idempotency contracts. Integrate real model/tool adapters in separate bounded PRs only after those controls. The dashboard should consume measured execution data only after those producers exist.

PR descriptions should state the bounded business outcome, API/schema changes, authorization matrix, fixture removal, evidence and remaining unavailable actions. Do not bundle broad formatting changes with functional branches. Rebase dependent work after shared contracts merge rather than duplicating authorization implementations per screen. Approval runtime work may require several small prerequisite PRs; do not merge a decision UI that claims working protection before the server can enforce it.

## 9. Verification limits and release decisions

Inspected tests comprise `AgentServiceTest` and `OrganizationServiceTest`: focused mocked service checks for team lookup/agent creation consistency. They do not authenticate requests, exercise a real database migration, test mission transactions/trace isolation, or validate a rendered UI. `application-test.yml` disables Flyway and uses H2 create-drop, so a future test using that profile alone cannot verify PostgreSQL migration correctness.

The inspected older local CI configuration supplies Maven verify/JaCoCo, frontend lint/format/tooling/build, Docker smoke and a Sonar step. GitHub main has a user-approved temporary Sonar pause; the local workflow is not authoritative for its current gate behavior. `frontend/scripts/test-editor-dependency.cjs` tests dependency behavior, not application workflows; current FE CI does not run business browser tests. Docker smoke explicitly accepts anonymous organization listing today and must change with authentication. Builds, health checks, HTML fallback and an API array response prove deployability surfaces, not secure functional completion.

`IMPLEMENTATION_STATUS.md` records earlier blocked backend/browser/Sonar checks; that is historical evidence, not a fresh test result. No new pass/fail runtime claim is made here. Re-run required checks in the implementing branches and preserve actual independent QC evidence under the existing standards in `docs/engineering/CODING_STANDARDS.md`.

Release decision: continue local preview/foundation development. Block real/shared-data wiring until the P0 security/tenant defects are resolved. First delivery is login/auth plus the security foundation on `feature/auth-login`; acceptance follows its delivery contract and the authoritative coordination document. Mission intake remains a later alternative, with the evidence above applicable only if selected. Track organization/agent registry, dashboard aggregates, planning/execution and approvals as separate capabilities; neither existing fixtures nor a future API hookup makes those broader screens functionally complete.

## 10. Files changed by this review

- `docs/engineering/IMPLEMENTATION_GAP_REVIEW.md` — this document only.
