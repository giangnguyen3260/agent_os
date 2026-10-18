# AgentOS screen delivery plan

Date: 2026-10-18. Owner: BA lead / delivery coordinator. Scope: audit and planning only; change this file only, no source implementation.

## Authority and current evidence

The user-supplied full content of **Library version 1, UI_FLOW_SPEC v0.3** is the canonical current UI spec for this plan. It is a proposal/review baseline, not blanket business approval. Its §10 supplements earlier sections, including approval information/reply and invalidation behavior. The sibling `../agentos-v0.1/UI_FLOW_SPEC.md` v0.4 is an **unconfirmed local draft**. Its S00/S18/S19 and D01–D08 claims must not become approved scope or override v0.3. Local BA review, domain/state/API/schema documents and `../ENGINEERING_DELIVERY_PLAN.md` are supporting inputs requiring reconciliation, not authority to silently change that baseline. Main supplies the decision record for any separately approved rule.

Read [FIGMA_SCREEN_INVENTORY.md](FIGMA_SCREEN_INVENTORY.md) and [DELIVERY_COORDINATION.md](DELIVERY_COORDINATION.md): main's live metadata inventory is now available. Initial inventory was Unverified pending that handoff; status now is **metadata verified: 467 target/reference nodes, 156 named entries; visual, behavioral and implementation acceptance Not verified**. No live Figma API inspection or screenshot review was performed by this BA task. The historical Forest review's 124-frame count is superseded for inventory purposes, not a current completion denominator.

| Inventory evidence | Meaning / limitation |
|---|---|
| Main reports 604 top-level frames | Includes historical revisions; not 604 independent screens. |
| Forest 157, Mobile 156, Tablet 156 in live metadata | Per `DELIVERY_COORDINATION.md`, two desktop design-direction/component references are excluded from the markdown enumeration: Forest 155 + Mobile 156 + Tablet 156 = 467 current target/reference nodes, grouped into 156 named entries. Metadata verified; these are not independent routes. No unresolved count discrepancy. |
| AUTH 31 states per breakpoint | Confirmed in supplied rows: 93 responsive auth states, grouped into functional screens below. |
| S01–S17 plus shared states, Confirm and Delivery variants | All are in scope for mapping. Resolve which revision is the implementation target; retain historical aliases explicitly. |
| No S00/S18/S19 rows found in supplied inventory | Draft additions only, not designed-screen delivery commitments. Necessary setup/acceptance/recovery behavior belongs in agreed contracts of existing screens unless separately approved. |

Forest is the desktop target with Mobile/Tablet equivalents. Actual inventory sizes are 1440×1000, 768×1024 and 390×844; canonical prose mentions desktop 1440×900, so Designer must resolve viewport/scroll expectations, not stretch screenshots blindly. Every unique node gets a ticket mapping and disposition: target state, responsive equivalent, shared component/guidance, or superseded historical revision. No frame may disappear through deduplication. Functional tickets include their states and breakpoints; frame totals are never delivery percentages.

Other inspected inputs: `PRODUCT_SPEC.md`, `API_BOUNDARY.md`, `IMPLEMENTATION_STATUS.md`; available domain/state/schema/architecture companions in both repositories; sibling BA/Forest reviews; `docs/qc/QC_TEST_PLAN.md`, `TEST_CASES_FOUNDATION.md`, `FOUNDATION_QC_REPORT.md`; actual routes, feature components, controllers, services, gateways and migration V001. Existing reports are historical scoped evidence, not current screen passes. This task executed no application, browser, migration or persistence/security tests.

## Current route and backend capability baseline

| Current FE route | Observed implementation | Delivery status |
|---|---|---|
| `/` | Redirects to `/dashboard` | Navigation exists; authenticated landing behavior unverified. |
| `/dashboard` | S01-like static KPIs, example missions, costs, progress and activity | Preview only; no business-data acceptance. |
| `/organization` | S02-like static organization tree | Preview only; no persisted relationships displayed. |
| `/agents` | S07-like static agents, success/cost values and permission matrix | Preview only; no API integration evidenced. |
| `/missions` | S10-like static mission/task-flow/cost cards | Preview only; no execution evidence. |
| `/approvals` | S14-like fixed empty message | Preview only; not evidence the actual queue is empty. |
| `**` | Not-found component | Source present; browser/deep-link behavior unverified. |

No dedicated auth, bootstrap, teams, settings, creation, detail, acceptance, recovery or trace route is declared in the inspected route file. S17 overlays are not proven by routing. Future paths must be agreed in the screen contract; screen IDs below are not invented existing routes.

Actual API base is `/api/v1`. In the following table `O` means `/organizations/{organizationId}`.

| Capability observed in code | Exposed surface / storage | Gap to designed experience |
|---|---|---|
| Organization create/list/get | POST/GET `/organizations`; GET `O`; JPA organization rows | No authenticated membership filter; list uses `findAll()`. No exposed update. |
| Team create/list | POST/GET `O/teams`; JPA team rows | `getTeam` is a service helper, not a controller detail route. No exposed settings, membership/manager, archive, routing or quota commands. |
| Agent create/list/get | POST/GET `O/agents`; GET `O/agents/{agentId}`; JPA identity/team/role/level/autonomy | Team/org consistency checked for agent creation, but this is not actor authorization. No contract/config versions, capability catalogs, activation or effective grants. |
| Mission create/list/get; request planning | POST/GET `O/missions`; GET `O/missions/{missionId}`; POST `.../{missionId}/plan`; JPA mission rows | Planning only records an audit request and returns the existing mission; no plan/task generation or worker dispatch. Mission service does not demonstrate same-org team/manager validation. No execution commands. |
| Audit and mission trace | GET `O/audit-events`; GET `O/missions/{missionId}/trace`; JPA audit rows | Trace is audit events, not full run/model/tool/cost traces. Mission trace service queries missionId without organization scope: fix authorization and tenant lookup before acceptance. |
| Auth and policy | `SecurityConfig` placeholder; `CurrentActor.system()` helper; governance engine returns ALLOW | No implemented login/session or trusted human identity, scoped grants or deny-by-default enforcement. |
| Runtime integrations | `OrchestratorService` TODO; `StubModelGateway`, `StubToolGateway`; empty ResourceManager/MemoryService | No real execution, budget enforcement, memory behavior or tool side-effect assurance. Stub outputs are not delivery evidence. |
| Database foundation | V001 creates organizations, teams, agents, missions, audit_events | No task/run/approval/authorization/config-version/output/recovery/reservation tables in inspected migration. JPA writes show implementation intent, not restart-durability proof. |

`API_BOUNDARY.md` describes many more intended endpoints than the controllers expose. Do not tell FE those APIs exist. BE must provide versioned request/response/error, allowed-action and persistence contracts for missing capabilities before integration.

## Delivery control and PR sequence

1. **Audit/planning PR first**: branch `review/screen-delivery-audit`. Main packages the inventory, this plan, capability gaps and decision/dependency register for review. Coordinator records that `review/screen-delivery-audit` and `feature/auth-login` already exist on GitHub from main `207fced92adb328bf1039970bae6b216f8458fbd`. This task only edits this plan; main/coordinator owns PR packaging and the inventory. No implementation mixed into the planning PR.
2. **First implementation branch: `feature/auth-login`**. Deliver working login across all three breakpoints with its full security dependencies below. A styled login form backed by mocks is not a feature completion.
3. Then **one functional screen per branch/PR**, including its responsive/state variants and owned dialogs. Only one screen In delivery/In QC at a time; next implementation begins after previous PR passes applicable gates and merges. Backend/security preparations needed by that screen are included or explicitly completed dependencies. Do not batch the remaining auth screens or all S17 dialogs into one feature branch.
4. Each PR supplies branch/revision, canonical rule references, exact Figma nodes, route/API/schema changes, test evidence, Designer/QC findings, unresolved blockers and required integration retests. Later destinations may remain tracked dependencies; affected screens stay Awaiting integration, not Accepted, until links work end-to-end. Do not mask dead CTAs as finished functionality.

BA can record a dependency-driven resequencing decision with reason/impact; no silent parallel screen work. Proposed branch names after login are planning identifiers, not branches created by this task.

## First feature: auth login and security dependencies

Target nodes: desktop **70:6350**, tablet **71:7109**, mobile **71:7913**. Include login-error/loading states: desktop 70:6387/70:6421; tablet 71:7142/71:7176; mobile 71:7946/71:7980. Match the real designs and check field validation, submit lock, keyboard/focus, errors, narrow layout and safe redirect behavior.

Follow [AUTH_LOGIN_DELIVERY_CONTRACT.md](AUTH_LOGIN_DELIVERY_CONTRACT.md), the coordinator-owned first-login contract. Proposed implementation defaults are persisted human accounts, operator-provisioned first admin without default password/public signup, adaptive password hashing, same-origin Spring Security server sessions, cookie/CSRF protection and membership enforcement. BA/SA document session TTL, rate limits, concurrent sessions, recovery/MFA and topology before release; proceed with clearly labelled proposed defaults where safe. Open policies are dependencies, not blanket approval stops: block only where a missing decision prevents secure/correct implementation or a required acceptance test. Do not silently invent approved values. Dependent UI screens remain separate branches, but login cannot bypass mandatory security dependencies to meet that separation.

| Owner | Required login dependency / acceptance evidence |
|---|---|
| SA | Identity/session threat model, trust boundaries, scoped policy and supported stack contracts; cookie/CSRF/CORS topology, shared session-store failure/revocation behavior, secret management and audit/redaction. |
| BE | Real account/credential storage or verified IdP adapter; adaptive password hashing if passwords are local; secure first-admin provisioning; trusted principal; rotated Secure/HttpOnly/SameSite session cookie; logout/revoke/idle and absolute expiry; rate limits and non-enumerating errors. Shared revocable session storage; no in-memory-only business authority. |
| BE | Deny-by-default protected APIs and service authorization, current membership/resource scope checks, cross-org list/detail/trace protection, CSRF and credentialed CORS policy, durable security audit, safe error handling. URL organizationId and FE button visibility confer no authority. |
| FE | Real login/session/logout integration, safe return URL, guarded routes and API error handling; no bearer credentials in localStorage. Clear tenant-scoped state on logout/scope changes; no protected data flash or fake authenticated user. |
| Designer | Review all login variants at inventory sizes plus intermediate widths; error/disabled contrast, labels, focus, loading and responsive behavior. |
| QC | Independent valid/invalid/disabled-user login, enumeration/rate-limit, fixation, CSRF/origin, logout/replay/expiry, store failure and cross-replica revocation tests; direct unauthenticated/cross-org API attempts and secret leakage checks. Use relevant TC-FND IDs with actual revision/environment and redacted request/session evidence. |
| BA | Confirm post-login destination and no-membership behavior; dependencies on workspace/recovery/expiry screens tracked. Accept only evidenced behavior; mandatory unfinished recovery/step-up policy blocks acceptance. |

Actual credentials/sessions must work against the deployed BE and real storage. Restart/replica checks must prove the agreed session semantics. Any protected endpoint still publicly accessible, or tenant trace leak, blocks this feature's security acceptance. Real model/tool execution additionally requires enforced grants/resource controls; default ALLOW/stub gateways cannot prove it.

## Auth functional-screen queue

All rows start **Planned / Not verified**. Each listed state name applies to Forest, Tablet and Mobile nodes in the supplied inventory; obtain exact node lists from that inventory in each ticket. These groupings cover all 31 AUTH names per breakpoint without treating state variants as separate product features.

| Serial order / branch | Functional screen and included AUTH names | Dependencies and screen acceptance |
|---|---|---|
| A1 `feature/auth-login` | login, login-error, login-loading | First feature; full security gate above. |
| A2 `feature/auth-session-expired` | session-expired | A1 expiry/revocation; actual 401/expiry clears sensitive state, safe reauthentication, no lost unsaved work silently submitted. |
| A3 `feature/auth-forbidden` | forbidden | A1 scoped policy; real 403, no data leak or retry escalation; safe permitted navigation. |
| A4 `feature/auth-workspace` | workspace, no-workspace | A1 memberships; only authorized workspaces, persisted/current selection validated, empty membership truthful, old-tenant responses/cache cannot populate new scope. |
| A5 `feature/auth-forgot` | forgot, sent | A1 recovery contract and real delivery adapter; uniform response, throttling, durable expiring token digest, no secret in logs. Verify through isolated test delivery, not an invented sent banner. |
| A6 `feature/auth-reset` | reset, reset-expired, reset-success | A5; validate token scope/expiry, consume once atomically, persist password change and agreed session invalidation; replay/concurrency tests. |
| A7 `feature/auth-members` | members | A4 scoped directory/roles; real member list, authorized scope, no cross-org exposure. |
| A8 `feature/auth-role-edit` | edit-role, review-role, role-saved | A7 authority policy; persist reviewed role diff with actor/audit/version, no self-escalation or stale overwrite; effect visible on new protected requests. |
| A9 `feature/auth-invitations` | invitations | A7 invitation store/list/filter; real statuses/expiry and permitted entry points. |
| A10 `feature/auth-invite-create` | invite-form, invite-review, invite-sent | A9; reviewed email/scope/role, durable unique invitation and token lifecycle, actual isolated delivery evidence, idempotent send. |
| A11 `feature/auth-invite-accept` | invite-new, invite-existing, invite-mismatch, invite-expired | A10/A1; verified intended identity, expiry/revocation, atomic one-time membership creation, duplicate/replay protection; mismatch cannot switch invitation ownership. |
| A12 `feature/auth-agent-permissions` | agent-permissions, review-agent, revoke-confirm, agent-revoked | A8 plus BE agent/grant model; reviewed effective scope, durable revoke and live enforcement on next protected action; no self-grant. Agent Studio link retested later. |
| A13 `feature/auth-grant-edit` | grant-edit, grant-review, grant-saved | A12 authority/envelope; persist reviewed scoped grant/version/expiry, enforce current permission and concurrency; review grant policy rather than assuming management role suffices. |

Backend agent/grant prerequisites can be implemented for A12/A13 before the Agent Studio UI. Missing resources produce truthful empty states, never seeded pretend business activity. All designed features remain delivery commitments; missing producers or services keep the affected feature incomplete.

## Product functional-screen queue

Canonical families S01–S17 are all covered below. Each row is one functional-screen branch, all its tabs/wizard steps/state variants and responsive equivalents included. All are **Planned / Not verified**, including existing previews. Backend prerequisites must exist before FE integration; generated business activity must be persisted before metrics can be demonstrated. Static content is not acceptance evidence.

| Order / proposed branch | Screen / inventory mapping | Dependencies and additional acceptance |
|---|---|---|
| P1 `feature/team-create` | S04 steps 1–3, validation, contextual Confirm | Auth; organization/bootstrap, eligible agents, scoped limits. Persist team/name/purpose/setup/intake/limits; optional manager per resolved policy. Reject conflicts; reload proves creation; return-to-workspace remains integration dependency. |
| P2 `feature/team-list` | S03, NoResults | P1; scoped searchable/paginated summaries. Created team present; truthful empty/no-results and actual health/counts/cost; links tracked to later screens. |
| P3 `feature/agent-create` | S08 steps 1–4, Validation/Test/TestResult/TestError, Delivery and Confirm aliases | P1/P2; real allowed catalog/provider/tool/skill/grant configuration, limits, contract and validation services. Persist Draft, not execution eligible; actual sandbox result; wizard preserves errors/back/unsaved state. Resolve Delivery vs original nodes explicitly. |
| P4 `feature/agent-studio` | S09 Draft/Activate/Active/Contract/Permissions/Limits/Edit/Diff/Saved/Paused, Delivery/Confirm aliases | P3; versioned config/activation/pause services. Validate before activation, no partial config save; run snapshot fixed, live revoked grants enforced. Conflict diff; genuine workload/usage or unavailable state. |
| P5 `feature/agent-list` | S07, NoResults | P4; actual identity/config/status/team/current task/capacity/spend projections; no sample agents/permission matrix. Unassigned explicit; detail/create links work. |
| P6 `feature/team-settings` | S06 Members/Limits/Saved/Diff/Blocked; General/Intake routing tabs from spec | P2/P4; persist membership/manager/routing/limits, conflict protection and impact checks. No owner lost on removal; no silent team transfer; intake pause does not stop runs. Missing designed tabs flagged to Designer. |
| P7 `feature/mission-create` | S11 steps 1–3 and Confirm | P6/P4; team-manager scope, inputs/attachments/output metadata, budget and policy. Persist draft/create separately from planning; planning failure retains mission and safe retry; Start separate. |
| P8 `feature/mission-list` | S10 | P7; scoped summaries/status/filter/priority/date. Planning/Needs plan honest; no invented progress; actual counts/cost and preserved filters. |
| P9 `feature/mission-control` | S12 Planning/PlanningError/Plan/EditPlan/Outputs/Paused/Cancelling/Cancelled and Confirm | P7; real planner, task graph/schema, dependency validation, scheduler/resources, run/output and allowed-action services; safe approval/recovery services before executing risky work. Persist plan/version, actual dispatch/pause/cancel; run success not mission completion. |
| P10 `feature/task-detail` | S13 all Intake/Ready/Running/Review/Input/Dependency/Done/Failed/Unknown/Capacity/Budget/Rate/Rework/Output/AfterInput/AfterApproval/Cancelling/Cancelled and intake-flow states | P9; real task/run/attempt/blocker/output contracts. Assign/input/triage validated, all blockers reevaluated, Assigned not Running. Rework retains old output and blocks dependent; UNKNOWN requires reconciliation before retry; no silent owner change mid-run. |
| P11 `feature/approval-center` | S14, Info, Decided plus spec Expired filter | P9/P10; scoped durable queue, actual count independent of pending task count, requester/team/age filters and information status. |
| P12 `feature/approval-detail` | S15 Approved/Reject/Rejected/Ask/Reply/Invalidated/Expired/SelfApproval/Input/ConfirmScope/Acknowledged | P11; action snapshot/payload/scope/version/window, evidence, decisions and audit. Full ask→reply→Pending loop; reject reason; self-approval blocked; concurrency/expiry read-only. Reevaluate readiness after decision. Canonical v0.3 allows Approved→Expired/Invalidated; any separate immutable-decision/authorization design must be explicitly reconciled, not imported as approved v0.4 behavior. |
| P13 `feature/trace-explorer` | S16 | P9/P10/P12; durable redacted run/model/tool/approval/retry/output events, config snapshots and usage ledger. Correct org scope on trace, real costs/tokens or unavailable, correlation/navigation and pagination. |
| P14 `feature/team-workspace` | S05 Setup/Pending/Agents/Activity, Tasks tab | P2/P5/P6/P8–P13; consistent live aggregates/board/intake/workload. Unique Pending with primary/full blocker reasons/ages/next action; no free drag to Done/Running; actual slots vs effective capacity. Date affects Performance, not Current snapshot. Stale/reconnect states honest. |
| P15 `feature/org-dashboard` | S01 | P14/P11/P13; scoped real aggregates and attention/activity. Every KPI matches drill-down scope/time; missing costs unavailable, zero denominator shown —; empty org provides actionable setup/team entry. |
| P16 `feature/org-map` | S02 | P2/P14/P5/P4; persisted memberships/reporting tree and inspector, empty/unassigned handling, working workspace/agent/Add Agent links. No static invented hierarchy or unsupported drag transfer. |

S17 is a designed overlay family, not one giant parallel branch. Deliver each functional dialog with its owning screen: agent pause with P4; team archive/pause intake with P6; mission pause/cancel with P9; input/rework/retry/stop-run with P10; create-task entry from P9/P14 and its own functional branch if not already delivered. Keep `S17-CreateTask`, Input, Rework, Retry, PauseAgent, PauseMission, Cancel, StopRun, Unsaved plus spec archive/permission-denied requirements mapped. The first parent implements the shared component; subsequent parents integrate/retest it. Confirm variants attach to every affected wizard/edit; default/busy/failure/focus behavior is required, not inferred from component reuse.

Shared State-Loading/Error/Conflict, responsive Navigation, foundations/guidance and intermediate-width behavior are acceptance tasks for each consuming screen, not additional business screens. The inventory's Delivery S08/S09 revisions are unresolved aliases until main/Designer chooses canonical targets. Historical original/v0.3 nodes remain classified as superseded/reference with evidence; no duplicate implementation required.


## Common acceptance contract: persistence and complete functionality

The latest user scope is **full implementation of all designed screens**. No automatic deferral of designed modules under old MVP assumptions. If a design needs catalogs, configuration, membership, provider integration, storage or recovery capabilities absent in current BE, deliver those dependencies. S00/S18/S19 local-draft labels remain unapproved additions; this does not defer required behavior visible in current designs. Main/Designer reconcile any unmapped current node into the serial queue before declaring complete coverage.

- Every action uses a real authorized API and actual persisted records. A successful mutation is proven by server readback, page reload, second session where relevant, and application restart against PostgreSQL with migrations. Browser memory/localStorage and a success toast are not business persistence. Database rollback/constraint and negative cases prove failed commands leave no prohibited writes.
- Real organization IDs, credentials, agents, missions, approvals, statuses, progress and spend must originate in persisted records or measured execution. Static labels, tokens, enums and isolated test fixtures are valid; hardcoded demo business data in normal runtime is not. Unavailable metrics stay explicitly unavailable and their designed feature stays incomplete until its producer exists. No synthetic zero, fake email delivery, stub execution or simulated acceptance.
- Field validation, permission denial, loading, empty, no-results, error, conflict, duplicate submission, failure recovery and unsaved-change behavior must be exercised. Wizards can be implemented step by step but require a working final submit before acceptance. Back/return/deep-link behavior preserves approved context.
- Canonical v0.3 §11 cases are mandatory traceability: unique Pending counts; approval with missing input still waiting; Draft ineligible; Current snapshot independent of performance date; UNKNOWN prevents blind retry; self-approval denial; information reply loop; actual pause/cancel state; live grant revocation; rework retains history/dependencies; all create entry points; every pending reason has owner/age/next action. Proposed rules become explicit implementation contracts, not claims of previously approved policy.
- FE uses actual APIs; BE checks actor/resource scope on every entry point. Audit, search, trace, artifacts and events need equivalent protection. Security tests include absence of prohibited reads/writes/tool effects. Race/idempotency/retry and grant changes need meaningful integration evidence.
- Designer reviews rendered Forest/Tablet/Mobile and intermediate widths with exact node references, keyboard/focus, labels, contrast, touch targets and scroll behavior. Metadata verification is not visual acceptance. Coordinator has inspected desktop/mobile login contexts; tablet and remaining screens need per-screen context/render review.
- QC independently verifies browser/API/DB behavior on the exact commit, appropriate BE/FE tests, migrations and Docker integration; preserve coding/style/build checks. **Sonar is temporarily paused by user instruction**; report Paused, never Pass. Builds, Docker smoke, functional acceptance and production readiness are separate claims.

## Responsibilities and handoff

| Role | Owned output / acceptance responsibility |
|---|---|
| BA lead | User outcome, fields/rules, actor/action/scope, transitions, entry/exit, canonical spec/Figma node mapping, full scope, ordered queue and proposed-policy/decision register. Accept requirement coverage only with evidence. |
| SA | API/error/security contracts, domain boundaries, tenancy, transaction/concurrency/idempotency, migrations and dependency/scale implications; review BE/FE contract fit. |
| BE | Real persistence, migrations, validation, trusted identity/policy, orchestration/integrations/read models, meaningful unit/integration tests and redacted API/DB evidence. Fix server defects. |
| FE | One functional screen per branch using real APIs; complete forms/actions/navigation/state/error/session/context and responsive implementation. Attach browser evidence; fix UI defects. |
| Designer | Canonical node selection across breakpoints, retained/superseded revision mapping, state/overlay/accessibility review and rendered comparison. No blanket visual sign-off from metadata. |
| QC | Independent positive/negative/security/persistence/browser tests on exact revision; record defects, impact, reproducible evidence and affected regression; independently retest fixes. Authors do not self-close QC findings. |
| Coordinator | Review PR/evidence completeness, sequence branches/merge, maintain accurate status, resolve cross-role blockers; no acceptance solely from build success. |

Handoff: BA scope/criteria → SA contract → BE dependency and persistence implementation → FE screen → Designer review + independent QC → fix/retest → BA requirement acceptance and coordinator PR review. Contract/design discovery may proceed ahead; serial screen implementation remains enforced. Do not mark a family complete while one required state, breakpoint, final-submit or linked dependency is incomplete.

## Evidence ledger and completion statuses

Use coordination vocabulary: **Planned → Ready → Implementing → In QC → Fix/retest → Accepted**. Blocked and Awaiting integration retain the underlying incomplete status; record owner, cause and concrete unblock/retest condition. Ready requires mapped nodes, testable criteria and usable dependency contracts; Accepted requires independent applicable checks and BA/Designer coverage. No fake percentages or inferred completion from changed file counts.

Each screen ticket records: screen/functional flow, branch/PR, exact commit, canonical spec section and Figma nodes per breakpoint, dependencies, proposed/approved policy references, expected/actual results, test IDs/commands/environment/date/executor, redacted API/DB artifacts, browser screenshots, defects/retests, status and acceptance owners. Test outcomes are PASS/FAIL/BLOCKED/NOT_RUN with scope; missing evidence is not PASS. Reopen on regression or changed underlying contracts.

| Work item | Current evidence | Current status / next owner |
|---|---|---|
| Metadata inventory | Main live metadata, 467 enumerated nodes / 156 names; AUTH31 per breakpoint; inventory read | Metadata verified; main/BA map each node to ticket/disposition. Visual/function Not verified. |
| Audit/planning | This plan, coordinator inventory/coordination/login contract | Draft review artifact; coordinator packages audit PR. No runtime feature accepted. |
| Login A1 | Design nodes identified; coordinator contract exists; placeholder security observed | Planned / Not implemented or accepted. SA/BE security contract/dependencies then FE; independent QC required. |
| Remaining AUTH / S01–S17 | Complete family/state grouping and serial queue above; preview routes/basic persistence only | Planned / Not verified. Designer/BA resolve canonical variants, owners implement dependencies per screen. |
| Current review verification | Document readback, AUTH-name coverage and scope checks only | No new functional tests executed. Historical CI/QC results do not establish current acceptance. |

Changed by this BA task: `docs/planning/SCREEN_DELIVERY_PLAN.md` only. Existing coordinator documents and source files are not edited by this task.
