# Process and completeness review — 2026-10-18

## Scope and disposition

Reviewer: QA lead, under explicit user authorization for a team audit. Baseline: commit `467acd14a3dd16796974c15bc1ec8d184f0673c5`; working tree was clean at the initial inspection. The user subsequently identified current GitHub main as `207fced92adb328bf1039970bae6b216f8458fbd`, with the same application source baseline and two stale local files (workflow/README). Local workflow text must therefore not be treated as the current remote policy. This is a read-only source/document/workflow inspection; the only authorized write is this report. No build, dependency installation, service startup, migration, browser interaction, API request, security exploit, or test suite was executed in this audit. Proposed cases below have no execution passes.

**Disposition: foundation in progress; functional product acceptance and release readiness are not established.** The repository contains a frontend preview, partial persistence/API services, build definitions and useful QC planning. It does not implement the complete operating loop in `PRODUCT_SPEC.md` §2. No defensible percentage-complete figure follows from file counts, route counts, or a successful compiler invocation.

Sources inspected: README and IMPLEMENTATION_STATUS; product and QC documents; ADR-0003; both GitHub workflows; frontend routes, shell, feature components, styles, package scripts and Angular configuration; backend controllers/services, DTOs, security and runtime stubs, tests, POM, profiles and V001 migration; Sonar configuration. Referenced sibling delivery/BA documents were not re-audited; this report does not independently reconcile their full requirements or external Figma baseline.

## Actual completeness and material findings

Paths in this report are repository-relative. Findings are source observations, not executed vulnerabilities or HTTP results.

| ID / severity | Evidence | Assessment and required disposition |
|---|---|---|
| PR-01 / High functional gap | `frontend/src/app/features/*/*.ts`; `frontend/src/main.ts` | All five feature pages are fixed templates with empty component classes. HttpClient is provided at bootstrap but no feature API consumption was found. Dashboard Create mission/Review approvals, Missions Create mission and Agents Create Agent buttons have no handler or link. Navigation and drawer controls do have bindings. FE owner must wire and test each promised action or explicitly mark it unavailable for preview acceptance. Provision of HttpClient is not API integration. |
| PR-02 / High data integrity of presentation | Dashboard shows 12 agents, 4 running missions and 3 pending approvals; Agents shows three sample cards, Missions two sample cards, Approvals always says nothing is waiting | These are illustrative values, not verified statistics. Shell explicitly discloses preview/sample data, which appropriately limits claims. The empty approval template is not proof of an empty backend queue. FE/BA must agree fixture consistency for preview and replace fixed values with scoped runtime data before functional acceptance. |
| PR-03 / Critical security prerequisite | `common/infrastructure/security/SecurityConfig.java` is an empty configuration with a future-security comment; POM has no Spring Security dependency; `OrganizationService.list()` calls `findAll()` | Trusted identity, membership and deny-by-default authorization are absent. URL organization filtering is resource consistency, not permission. `CurrentActor` is only a record; its existence supplies no authentication boundary. BE/SA own implementation and direct API negative tests using authenticated A/B actors. Loopback deployment exposure limits do not supply application authorization. |
| PR-04 / High tenant scoping defect | `ObservabilityController.missionTrace(organizationId, missionId)` calls `TraceService.missionTrace(missionId)`; repository lookup scopes by mission only | Organization path parameter is discarded. Require mission ownership and actor authorization before reading trace, then test foreign mission IDs with zero disclosure. This remains a separate defect even after authentication is added. No exploit was executed. |
| PR-05 / High cross-organization consistency gap | `MissionService.create()` checks only organization existence before saving supplied teamId/managerAgentId; V001 uses independent foreign keys | Existing foreign team/manager IDs are not checked against the requested organization. Independent FKs establish existence only. BE/DB owner must reject mismatched references and test no persisted mission/audit side effects. Agent creation already calls scoped `getTeam`, but this does not protect mission creation or authenticate its caller. |
| PR-06 / Critical governance prerequisite | `DefaultGovernanceEngine.evaluate()` always returns ALLOW; `StubModelGateway`, `StubToolGateway`, empty `MemoryService`/`ResourceManager`, no-op `OrchestratorService.requestMissionPlanning()` | Policy, budget enforcement, actual execution and memory are placeholders. Do not certify approval enforcement or external side effects. Before real gateways are enabled, require denied/pending actions to produce zero provider/tool calls and no unauthorized writes. |
| PR-07 / High mission workflow gap | `MissionService.requestPlanning()` only records MISSION_PLANNING_REQUESTED and returns the mission | A successful plan HTTP response would establish neither planning, a task graph, state transition nor worker execution. No call to the orchestrator is made here. BE/SA must implement and test the approved state machine, durable dispatch, retry/recovery and observable outcomes. No approval decision API/controller exists in the inspected controller inventory. |
| PR-08 / Medium audit correctness gap | Mission creation builds JSON payload by string concatenation; `AuditEventJpaEntity` always sets actorType SYSTEM with no actorId | Quotes/backslashes/newlines in titles can produce invalid JSON text; database payload is text and will not validate it. Human attribution is absent. BE should serialize structured payloads and propagate trusted actor identity; verify escaping, correct actor and transactional rollback on audit failure. This is not a demonstrated injection exploit. |

Organization/team, agent and mission create/list/read service code exists. Agent team ownership checks and null-team handling are concrete implemented logic. Audit persistence and trace retrieval exist in limited form. They have not been independently exercised here. Model/skill/tool configuration, usable forms, approvals, task graphs, run tracking, cost accounting and the complete product operating loop cannot be marked delivered by these skeletons.

## Process review and evidence hierarchy

The existing process has useful safeguards: stable TC-FND-001–036 IDs, explicit expected evidence, independent fix/retest ownership, clear NOT_RUN/BLOCKED semantics, separation of author reports, and warnings against production-readiness claims. Preserve these. The weakness is execution coverage and gate enforcement evidence, not absence of planning documents.

| Layer | What existing implementation/evidence establishes | What it does not establish |
|---|---|---|
| Static inspection | Routes, handlers, configuration intent and concrete source omissions | Rendered behavior, working API, exploitable runtime outcome or a test pass |
| Format/lint/compiler/build | Style/type/compilation and deployable output when actually executed | Click behavior, business outcomes, tenant isolation, browser accessibility |
| Tooling test | `test-editor-dependency.cjs` checks consuming tmp dependency lifecycle/traversal behavior | Angular component tests, screen interaction or business E2E |
| Backend unit tests | Two Mockito classes declare eight invocations (five agent, three organization) | Spring HTTP validation, real service composition, transactions, PostgreSQL, migrations or authentication. The two parameterized agent rejection labels share one mocked exception path. |
| Docker smoke definition | `.github/workflows/docker.yml` starts healthy stack; checks healthz, app-root HTML at /missions, organizations list-shaped data and external actuator 404 | JavaScript execution, mission rendering, create/reload persistence, authorization, approval enforcement or full schema validation. Historical runtime smoke success was subsequently supplied by the user as run `39132678489`; no browser business flow follows from that outcome. |
| Functional browser E2E | No suite or execution evidence found in inspected repository | Cannot be inferred from SPA HTML or lazy chunk production. Require actual UI → API → PostgreSQL → UI outcomes, including negative side effects. |

`npm test` points to `ng test`, but `frontend/angular.json` defines build/serve only, with no test target; no frontend spec files/browser test configuration were found. CI runs tooling checks and build, not application browser tests. Add a working application test runner and independent functional browser job before claiming screen coverage.

`quality.yml` invokes Maven verify, FE lint/format/tooling/build, uploads artifacts and requires Sonar connection. `sonar.qualitygate.wait=true` expresses server-gate waiting. This describes the stale local workflow: the user confirms current main explicitly and temporarily removed mandatory Sonar steps. That authorized deferral is not a process violation and absent Sonar is not a current mandatory merge blocker. Sonar analysis/metrics remain unverified rather than passed; do not reinstate a mandatory gate without the applicable decision. Separately, no FE coverage-producing command is configured despite the LCOV path; JaCoCo generates a report but no local numeric threshold check is configured. The proposed coverage ≥80%, duplication ≤3%, zero new blocker/critical issues and hotspot review need explicit adopted server policy, new-code baseline and an actual analysis result. Missing reports must not be treated as clean coverage. Workflow presence does not establish branch protection, required-job enforcement or review independence. Historical successful check outcomes are recorded below without implying a wholly green run.

Recommended team sequence: BA/SA freeze screen scope and contracts → FE/BE deliver one vertical slice → developer supplies revision-specific checks → QC independently executes positive/negative/API/browser cases → owner fixes defects → QC reruns affected cases and regression → BA/human acceptance. Keep preview acceptance, functional acceptance and production release as separate decisions. No developer self-closure of QC defects; waivers retain original result and explicit scope/owner.

## Historical evidence reconciliation

`QC_TEST_PLAN.md` and the foundation case catalogue are planning snapshots with 36 initially NOT_RUN cases. They are not a current execution total. `FOUNDATION_QC_REPORT.md` is append-only history: its early token/44px/breakpoint defects and tmp 0.2.6 audit failure must be read with later entries.

The latest 2026-10-15 scoped retest records independent successful FE tooling, npm audit, format, lint and build commands against tmp 0.2.7. This audit independently calculated current SHA-256 values matching that entry:

- `frontend/package.json`: `4b298a1051d932552bac7fbaac6e9f59aefd203918471b6129328afe7aeeb9c3`
- `frontend/package-lock.json`: `8fc4d7cdeef31e8b236b150832cdd218e9d876bd738d96656b290b225b676a1c`
- `frontend/scripts/test-editor-dependency.cjs`: `e58543c42f4a86f4118c94465c7a25f2029edbf3aae4ba5fce141cd2bd6112e5`

These matches support input continuity for those three files only. They neither rerun the commands nor recertify today's vulnerability database or all frontend source. Historical Java formatter/import success is not Java 21 compilation/unit execution. Later static fixes are present: approved global token values, 44px button minimums and drawer breakpoint at 1023px. Browser visual/hitbox/focus retests remain unestablished; do not repeat early source mismatches as current defects or close runtime checks from CSS alone.

The foundation report retains backend/browser/Sonar blockers. `DOCKER_QC_REPORT.md` dated 2026-10-18 records static definition checks, with Docker build blocked and container health/migration/API smoke NOT_RUN. Those are historical recorded environment limits, not fresh environment probes by this review, and must be read alongside the subsequently supplied CI results below. The user subsequently supplied historical GitHub Actions evidence: Docker runtime smoke passed in run `39132678489`; run `39132678490` passed eight backend tests, lint and build, with only Sonar configuration failing. These are user-supplied historical CI outcomes, not independently fetched logs or newly reproduced results in this audit. They supersede the older environment-blocked narrative for those historical CI checks; they do not establish functional product E2E, migration assertions, browser accessibility or current-revision reruns. There are zero new runtime passes here.

## Migration verification gate

V001 creates five core tables plus indexes and independent FKs. Default profile enables Flyway and Hibernate validate; the H2 test profile disables Flyway and uses create-drop. Mockito tests use neither. Therefore H2 or unit success cannot verify PostgreSQL SQL, migration history, constraints or deployment upgrades. Successful healthy Docker startup would be useful indirect startup evidence, but must be supplemented by explicit migration assertions.

DB/BE owner supplies a disposable PostgreSQL environment and QC executes:

1. Fresh database: run the exact production migrations; capture Flyway version/checksum/success history, PostgreSQL version and Hibernate validation. Assert all expected tables, indexes, nullability, unique slug and FK behavior.
2. Restart on the same volume: assert V001 is not reapplied, history stays successful and seeded records survive. A health check alone is insufficient.
3. Exercise organization/team/agent/mission/audit persistence with real services: null team permitted where contracted, missing FK rejected, duplicate slug behavior, cross-org rejection and zero unintended inserts. Independent FKs do not enforce same-org relationships; explicitly test that application boundary.
4. Test transaction rollback (including audit failure), concurrent duplicate slug creation, and titles requiring JSON escaping. Agree stable API error handling before acceptance; the current exception handler does not explicitly map all database constraint errors.
5. For future migrations, test upgrade from the previous supported schema with representative data and verify preservation. Only V001 exists now; no multi-version upgrade pass is possible. Record recovery/restore rehearsal separately; never infer rollback safety or delete a meaningful volume to repair migration failure.

All five groups are proposed and NOT_RUN in this review. Migration acceptance remains unverified.

## Per-screen QC gates

Every screen must independently satisfy: approved contract/fixtures; clean applicable build/static checks; live scoped data with loading/empty/error/retry states; wired keyboard-usable controls; actual backend authorization; persisted read-after-write where applicable; responsive/accessibility review; independent E2E and regression evidence. Run 390/768/1440 CSS-pixel views plus 767/769 and 1023/1024 shell boundaries, intermediate widths, zoom and long content. Capture actual hitboxes/focus behavior; CSS minimums alone are insufficient.

| Screen / gate owner | Required functional acceptance | Current source disposition |
|---|---|---|
| Login/security — SA/BE/FE + QC | Approved auth contract; real bootstrap/account/session/CSRF/membership boundary; TC-AUTH-001–016; visual comparison and recovery dependency accepted | Specified only; empty SecurityConfig; functional/security gate BLOCKED |
| Shell/routes/404 — FE + QC | All routes via links and direct refresh; active navigation; root redirect; unknown route recovery; drawer open/Close/Escape/focus trap and return; resize while open; no overflow | Source bindings exist; browser behavior NOT_RUN |
| Dashboard — FE/BE + QC | Scoped real aggregates, approval/mission consistency, timeline provenance, loading/empty/error states; both hero actions reach usable flows; permissions and fresh data after mutation | Static preview; functional gate BLOCKED by missing integration |
| Organization — FE/BE + QC | Create/select/read organization, create/list teams; validation, duplicate slug, persistence after refresh, foreign-tenant denial; approved map derived from data | Static map; APIs partial; functional gate BLOCKED |
| Agents — FE/BE + QC | Create and reload agent; valid/null/foreign/missing team variants; defaults and invalid inputs; real permission enforcement; contracted configuration fields persist | Static cards/matrix, inert create; service checks partial; functional gate BLOCKED |
| Missions — FE/BE/SA + QC | Create and reload; team/manager ownership; plan produces durable approved states/task graph; denied transitions, retries and budgets; trace matches actual events | Static board; create/read APIs partial; planning only logs; functional gate BLOCKED |
| Approvals — FE/BE/SA + QC | Live queue/details; authorized approve/reject; stale/repeated decisions; timeout policy; rejected/pending action yields zero tool calls; exact allowed resume behavior and audit | Fixed empty message; workflow absent; functional gate BLOCKED |

BLOCKED above describes readiness for the proposed functional gate, not an attempted test result. Features outside current routes still require coverage against the product spec; a route list is not the whole backlog.

## First delivery recommendation: login and security boundary

Deliver authentication/login first under [AUTH_LOGIN_DELIVERY_CONTRACT.md](../planning/AUTH_LOGIN_DELIVERY_CONTRACT.md), on the specified `feature/auth-login` branch. Its status is specified, not implemented or accepted. The empty SecurityConfig and absent trusted identity/membership enforcement make this the prerequisite for meaningful protected-screen acceptance. A dashboard preview or successful build cannot establish that boundary.

[FIGMA_SCREEN_INVENTORY.md](../planning/FIGMA_SCREEN_INVENTORY.md) records verified live metadata inventory, not visual or implementation acceptance. This review counted **31 AUTH states each for Forest desktop, Tablet and Mobile (93 responsive frame entries)** in that document. These include recovery, invitations, workspace/session, membership and permission states; they are not 93 implemented features or all part of the initial login ticket. Map every state to a delivery ticket and resolve duplicate revisions. Visual acceptance remains pending. The auth contract records prior desktop/mobile screenshot/context inspection; tablet context/render inspection remains required, and this audit did not inspect live Figma or render the implementation.

Login visual references from the contract:

| Variant | Default | Error | Loading |
|---|---|---|---|
| Desktop 1440 | 70:6350 | 70:6387 | 70:6421 |
| Tablet 768 | 71:7109 | 71:7142 | 71:7176 |
| Mobile 390 | 71:7913 | 71:7946 | 71:7980 |

Acceptance requires real persisted human accounts, operator-only admin bootstrap, credential verification, server-side sessions, CSRF and server-side organization membership enforcement. Model agents are not human accounts. Before coding, SA/BE must approve the CSRF bootstrap/sign-in/current-session/sign-out API and error contract. Session lifetime, abuse limits and concurrent-session policy must be recorded before release; this review invents no thresholds or endpoint paths. Recovery must work with tested transport/token lifecycle, or login remains incomplete until the adjacent recovery ticket is accepted. Workspace choice must use actual memberships. BA signs requirement coverage, designer visual acceptance and independent QC functional/security evidence; no build-only Done.

### Concrete auth QC cases

Trace: each case below references the functional scope and/or required QC evidence items (Q1–Q8) of [AUTH_LOGIN_DELIVERY_CONTRACT.md](../planning/AUTH_LOGIN_DELIVERY_CONTRACT.md). All cases are **NOT_RUN**. Gate readiness is BLOCKED by missing implementation/approved API details where applicable, not an executed case result. Use disposable PostgreSQL, a bootstrapped active human, disabled human, unknown identity, A/B organizations with distinct memberships, controllable session clock/network and redacted evidence.

| ID / contract trace | Concrete steps | Expected result and evidence |
|---|---|---|
| TC-AUTH-001 / Q1, bootstrap scope | Start empty PostgreSQL with real Flyway; run explicit admin bootstrap; restart application/database; attempt public registration and default credentials | Account persists, migrations/history are valid, no public/default-password bootstrap route grants access. Record migration output, redacted account row and API results. |
| TC-AUTH-002 / Q2, Q6 | Submit valid credentials through semantic form using Enter; query actual session; refresh protected page | Real authenticated server session and membership-based navigation survive refresh; no mock success. Capture browser/API/session-store correlation without token values. |
| TC-AUTH-003 / Q2 | Submit wrong password, unknown identity and disabled account through UI and direct API | All denied with contracted generic public credential error, no account disclosure or authenticated session; disabled user cannot enter. Capture status/body comparison and session assertions; timing policy requires separate agreement. |
| TC-AUTH-004 / Q3 | Inspect persisted credential representation and redacted logs/responses after success/failure; change stored hash through controlled fixture then retest credentials | Adaptive one-way hash, no raw password/token disclosure; credential behavior follows documented change policy. Do not infer session revocation policy. Capture safe assertions rather than secrets. |
| TC-AUTH-005 / Q4 | Compare pre-login/post-login session identity; inspect localhost HTTP and deployed HTTPS cookies | Session rotates on authentication; HttpOnly and adopted SameSite policy; Secure on HTTPS with only documented localhost exception. No bearer credential in localStorage. Record redacted cookie attributes and identity-change assertion. |
| TC-AUTH-006 / Q4, Q6 | Logout, replay old session against domain API, refresh and use Back; expire session using controlled clock and repeat | Server invalidates logout session; expired session rejected; UI returns to login/session-expired flow without protected data access. Capture API rejection and browser state; expiry values must come from agreed policy. |
| TC-AUTH-007 / Q5 | Send each contracted state-changing request with absent, invalid and valid CSRF token, including sign-in/sign-out where required by approved policy | Missing/invalid token rejected with zero mutation; legitimate flow works. Record request/result matrix and DB/session side-effect counts. |
| TC-AUTH-008 / Q5 | Call organization/agent/mission/audit/trace APIs without identity; as A substitute B org/resource IDs and request workspace B | Server rejects unauthorized reads/writes independently of route guards; no B trace/data or side effects. Exercise PR-04/05 directly; capture negative API/DB assertions. |
| TC-AUTH-009 / Q6 | Submit empty/malformed fields, activate labels, autocomplete and keyboard controls; delay response and rapidly click/press Enter repeatedly | Accessible validation and focus, empty initial inputs, no Figma sample credentials; pending state prevents duplicate submit; no fake success. Capture meaningful FE tests plus browser requests and state. |
| TC-AUTH-010 / Q6 | Interrupt sign-in network, return server error, restore network and retry | Recoverable distinct network error, no authenticated claim before server confirmation, safe retry; no credential logging. Capture request sequence and actual session lookup. |
| TC-AUTH-011 / Q6 | Open protected deep link logged out; login with valid internal return URL; repeat with external, protocol-relative and encoded unsafe redirect targets | Protected route redirects to login; success uses validated internal redirect/fallback and never external attacker destination. API remains protected throughout. Capture final URLs and direct API denials. |
| TC-AUTH-012 / scope, Q5 | Login with zero/one/multiple memberships; select workspace; alter submitted org ID; refresh | Actual memberships determine selection and no-workspace/forbidden state; no fixed UUID or stale foreign workspace. Record membership fixtures and API/UI outcomes. |
| TC-AUTH-013 / recovery dependency | Follow Forgot password; deliver through isolated mail transport; test valid, expired, reused and tampered reset tokens | Genuine delivery and reset, random token stored as digest, expiry/single-use rejection and safe errors per approved recovery contract. Inert CTA/fake sent page cannot pass; unresolved recovery keeps login incomplete. |
| TC-AUTH-014 / Q7 | Render default/error/loading against all nine referenced login frames at 1440/768/390, narrow viewport, zoom and mobile keyboard; keyboard/screen-reader review | Forest visuals compared with approved reference, no clipping, visible focus, meaningful labels/status, contrast and measured 44px targets. Capture screenshots and manual outcomes; tablet context review and designer signoff remain pending. |
| TC-AUTH-015 / scope, Q3 | Exercise bootstrap/login failure/success/logout and inspect audit events plus logs | Correct trusted human/system attribution and event outcome; no passwords/session/reset tokens. Capture redacted persisted events and transaction evidence. |
| TC-AUTH-016 / policy dependencies, Q8 | Execute agreed rate/lockout/concurrency boundaries and applicable BE/FE/browser/Docker tests on exact feature commit | Adopted boundaries and negative cases enforced; full logs, exits and revision-linked artifacts. Missing policy blocks those variants. Multi-instance session behavior needs separate distributed-store verification; single-instance pass cannot prove it. Sonar remains explicitly paused, never passed. |

The remaining AUTH inventory states (invitations, role/grant review, revocation and related outcomes) require adjacent contracts/cases before their own acceptance. Metadata verification does not close them. Existing TC-FND security cases remain applicable; this concrete first-screen set supplements them.

## Later-screen test cases: Dashboard with shared shell

Retain these cases for delivery after login/security acceptance. `/dashboard` is the current root landing route, not the recommended first delivery screen. Preserve TC-FND IDs; use the following new proposed screen IDs. Every case is **NOT_RUN**. For runtime-dependent cases, missing auth/data/action endpoints are known readiness blockers; no expected result below is asserted as actual behavior.

Fixtures for functional execution: authenticated user A in organization A, user B in organization B, known zero/nonzero dashboard aggregates, pending and decided approvals, missions with known status/cost/events, deterministic time/currency policy and controllable API failures. Use disposable data and provider/tool counters.

| ID | Steps | Expected result / required evidence |
|---|---|---|
| TC-DASH-001 | Open `/`, `/dashboard`, refresh and navigate Back/Forward in deployed browser | Root resolves to dashboard; real heading/assets render without console errors. Capture URL, screenshot and console/network trace; HTML app-root alone cannot pass. |
| TC-DASH-002 | Activate each sidebar/drawer link and topbar Approvals; return to dashboard | Correct route and active state; direct refresh works; unknown URL offers working dashboard recovery. Capture route assertions. |
| TC-DASH-003 | Compare visible preview labels/data before runtime integration | Preview is explicit and illustrative figures are not represented as live. Record inert controls as gaps; this preview-only check cannot pass functional action cases. |
| TC-DASH-004 | Load known nonzero A data; compare every metric/list/timeline with API and persisted fixtures | Counts, costs, statuses and timestamps follow adopted contract; approvals agree with queue; no B content. Capture requests and independent fixture queries. Missing aggregate contract blocks execution. |
| TC-DASH-005 | Load zero data; delay requests; return 500/offline; recover | Distinct loading/empty/error states, accessible status, safe retry and no invented successful values; no mutation on read/retry. Capture each state and request counts. |
| TC-DASH-006 | Click and keyboard-activate Review approvals | Opens live queue and correct scoped request detail; dashboard count agrees. Current dashboard button has no binding: source gap, not an executed failure. |
| TC-DASH-007 | Activate Create mission; submit valid data; reload; repeat with invalid data and rapid double submit | Usable form; contracted single-write behavior; persistent mission and updated metrics; invalid input has accessible errors and no insert. Current create button/form integration is absent. Capture UI/API/DB correlation. |
| TC-DASH-008 | As A, tamper organization/mission/trace IDs to B; repeat requests without identity | Approved unauthorized/not-found response with no B fields, trace or writes. Denied requests cause zero provider/tool calls. Requires implemented security; UI hiding cannot pass. |
| TC-DASH-009 | At 390/768/1440 and boundary widths, open drawer, Tab/Shift-Tab, Escape, Close, choose link and resize | No clipping/critical overflow; correct sidebar/drawer behavior, logical visible focus, modal focus containment/return and measured ≥44px critical targets. Capture viewport measurements and keyboard outcomes. |
| TC-DASH-010 | Run automated accessibility review, then keyboard/screen-reader and zoom checks on populated/error states | Agreed WCAG 2.2 AA scope; meaningful names/structure/status, readable contrast and non-color cues. Automated output alone cannot close manual checks. |
| TC-DASH-011 | Change fixture status/approval outcome via authorized flow, reload and switch organization | Dashboard refresh follows agreed freshness policy; no stale cross-org values or duplicate events. Capture before/after UI, API IDs and persisted state. |
| TC-DASH-012 | Attempt pending/rejected high-risk action and inspect displayed trace/outcome | No execution claimed before authorization; denied action produces zero tool effects; allowed outcome and actor audit match actual execution. Stub ALLOW/zero-cost responses cannot certify enforcement. |

Execution record must include revision, UTC time, environment/tool versions, executor, fixtures, exact steps/commands, expected/actual, exit codes where applicable, redacted screenshots/network/API/DB evidence, defect links and retest revision. Separate observed static defect, NOT_RUN case and readiness BLOCKED gate. Never convert historical success into a current whole-screen pass.

## Immediate ownership and exit decision

1. BA/SA prioritize the login/security contract and its unresolved policies, then the later dashboard contract and preview scope; reconcile unresolved policies before numeric/timeout acceptance criteria are enforced.
2. BE/SA prioritize PR-03–07, PostgreSQL migration evidence and trusted audit attribution before real external execution is enabled.
3. FE replaces inert actions/static runtime claims and supplies a working browser/application test setup; deliver the login/security slice with real server sessions before dashboard functional acceptance or expanding screen count.
4. DevOps supplies the logs/artifacts and commit associations for the historical runs, plus current required-check enforcement evidence. Track Sonar configuration as explicitly deferred under the user’s temporary policy, not a violation or mandatory blocker. QC independently validates the gates and affected negative cases.
5. QA keeps functional acceptance on hold until applicable cases pass with evidence and Critical/High findings are fixed/retested or explicitly dispositioned by an authorized decision. A disposition is not a pass. Human acceptance remains separate.

## Changed files

Only `docs/qc/PROCESS_REVIEW_2026-10-18.md` was created by this audit. Final working-tree inspection also showed an unrelated untracked `docs/planning/` directory appearing after the initial clean snapshot; it was not created or modified by this audit. At the user’s follow-up direction, AUTH_LOGIN_DELIVERY_CONTRACT.md and FIGMA_SCREEN_INVENTORY.md within it were read for this report update. No source, tests, dependencies, configuration or historical evidence documents were edited.
