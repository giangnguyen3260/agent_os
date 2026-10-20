# Implementation status — 20 October 2026

## Current branch outcome

On `feature/agent-directory`, Forest S07 is implemented as a real scoped directory with fixed pagination, literal name/role search, lifecycle/Team query filters and responsive navigation. ADMIN create/activation-review links and member read access use current workspace authorization. Runtime/workload remain unavailable. Draft PR10 stacks on unmerged PR9. Application3ded5dab passed78 backend tests,153 browser instances and native PostgreSQL projection/no-write checks. See [directory QC](docs/qc/AGENT_DIRECTORY_QC_REPORT.md) for exact gate evidence; no schema migration is added.

On `feature/agent-activation-review`, the ADMIN preflight reads saved configuration and same-organization Team limits. Current model authorization, tool authorization and execution runtime remain unavailable; UI activation is disabled and the server rejects direct requests without business mutations. This is a preflight delivery, not positive activation. Draft PR9 is stacked on unmerged Studio PR8. See [activation QC](docs/qc/AGENT_ACTIVATION_QC_REPORT.md) and [acceptance cases](docs/qc/AGENT_ACTIVATION_TEST_CASES.md) for final execution evidence. No migration or production seed data is introduced.

On `feature/agent-studio`, Draft configuration can be viewed, edited, compared before/after and saved as a new version. New structured nullable concurrency/token/budget settings are persisted alongside existing requested capability text. Immutable snapshots, current-revision checks and durable receipts prevent silent overwrites and duplicate saves. Application `218598e873b35f7fa669b34fd58bec1a87f3a960` with final test/fixture head `b8746e90` passed 66 backend tests and 117 browser instances, plus native PostgreSQL optimistic-concurrency/replay and auth/restart probes. Independent source review is closed; see final QC for bounded scope and evidence. See [Studio QC](docs/qc/AGENT_STUDIO_QC_REPORT.md), [acceptance cases](docs/qc/AGENT_STUDIO_TEST_CASES.md) and [V007 rollout](backend/AGENT_STUDIO_MIGRATION.md). Draft PR8 remains stacked on unmerged Agent creation PR7. Activation, provider grants and runtime enforcement are not included.


On `feature/agent-create`, the four-step Agent Draft flow persists identity, contract and requested capability configuration, with optional scoped team assignment and durable retry receipts. Application `f6185f6ff7e2dffff9c256d662882ce4b5150f8f` (test locator revision `2678d402f0fd53a703691aba29f66a6df064ff41`) passed the full 57-test backend suite and FE style/tooling/build in CI. The Docker/PostgreSQL gate and all 96 browser instances passed, including 24 Agent instances and a native concurrent HTTP creation/replay probe; see [Agent QC](docs/qc/AGENT_CREATE_QC_REPORT.md), [acceptance cases](docs/qc/AGENT_CREATE_TEST_CASES.md) and [V006 rollout](backend/AGENT_DRAFT_MIGRATION.md). Draft PR7 is stacked on unmerged Team PR6. Capabilities are requested configuration; no effective grants, provider execution or activation is included.

On `feature/team-create`, the three-step Create Team flow (Basics → Setup → Review) saves a real DRAFT with optional manager, eligible agents and configured limits. Application revision `5e1548061a048a8503d2317613feebf5503db348` passed 49 backend tests and 72 browser instances, including 18 new Team instances on desktop/tablet/mobile. The bounded development gate passed independent QC. Draft PR6 is stacked on dashboard PR5 and remains unmerged. Team activation and Agent creation were not delivered by that Team slice; Agent Draft creation is delivered separately above. See [Team QC](docs/qc/TEAM_CREATE_QC_REPORT.md) and [V005 rollout](backend/TEAM_DRAFT_MIGRATION.md).

On `feature/dashboard-live`, the organization overview and task drilldown are implemented as a bounded read flow from Forest S01 on top of unmerged workspace PR4. V004 adds real task persistence with tenant/team/mission integrity; Running/Pending/Review derive from unique task rows. Capacity and performance remain unavailable because their runtime sources do not exist. Application revision `3c5abe22` is verified by recovered CI execution logs:39 backend tests and54 browser instances passed, including corrected filter state/team assertions at all three widths. See [dashboard QC](docs/qc/DASHBOARD_QC_REPORT.md) for exact runs and scope.

Prior workspace application revision `4f3e795` passed 33 backend tests and 33 browser instances, with real PostgreSQL/Mailpit, reset race and restart/session/logout probes. Login/recovery/workspace changes remain stacked, draft and unmerged; no deployment to the user's Mac is claimed. Sonar remains temporarily paused, not passed.

| Scope | Actual state |
|---|---|
| Human login/session, recovery, workspace selection | Development gates verified; see the respective reports under docs/qc |
| Organization overview and pending-task read drilldown | Bounded development read gate verified; actual PostgreSQL rows, no sample business data |
| Capacity/performance/cost | Explicitly unavailable; no allocator, execution-run history or billing ledger implemented |
| Team Draft creation | Development gate verified; atomic assignments, scoped authorization and duplicate-safe retries |
| Agent directory | Scoped persisted reads, filters and responsive navigation; see directory QC |
| Agent activation review | Saved configuration/Team preflight and fail-closed activation boundary; actual activation unavailable |
| Agent Studio Draft edits | Development gate verified; 66 backend tests, 117 browser instances and native PostgreSQL version/replay probe |
| Agent Draft creation | Development gate verified; 57 backend tests and 96 browser instances, plus native PostgreSQL retry probe |
| Task mutation/execution, team/agent activation, effective grants, approvals and administration | Full designed flows incomplete; older static previews remain blocked |
| Production security/scale | Complete roles/grants, load/failover, full visual/accessibility and release hardening not accepted |

## Historical foundation snapshot — 15 October 2026

The text below records that earlier slice and its environment limitations. It is superseded for current login/recovery and CI status by the outcome above; historical blocked checks are not current failures or current completion evidence.

## Implemented in the first coding slice

- Google Java Style formatter (Spotless + pinned google-java-format1.22), explicit Java imports and style check in CI.
- Google TypeScript tooling via gts7/ESLint/Prettier; compiler options remain Angular-specific.
- JaCoCo and Sonar scanner/CI inputs; server gate setup is required, not claimed completed.
- Angular21 LTS / TypeScript5.9 / Node24 compatibility slice, lockfile and lightweight @angular/build builder; lazy feature routes, explicit404, Forest tokens,224px sidebar and390/768/1440 spacing/navigation foundations.
- Agent creation rejects a team from a different organization or a missing team through the Organization application-service contract. Null team remains permitted. Added two focused unit-test classes (eight test invocations planned).
- Independent QC agent;36 test cases, test plan, findings and fix/retest report under docs/qc.

## Verification and limits

Frontend format/lint/build and standalone Java formatter/import checks were run successfully by coordinator; independent QC outcomes are recorded in docs/qc/FOUNDATION_QC_REPORT.md. FE build produces six lazy feature chunks. These checks do not demonstrate functional runtime authorization or browser accessibility.

Backend Maven compile/unit/JaCoCo execution remains blocked by dependency/proxy setup and unsuitable downloaded Java21 runtime. The downloaded runtime was not accepted after checksum mismatch; it is not packaged. No BE tests were reported passing. Browser installation failed with a truncated archive, so no rendered mobile/tablet, dialog keyboard, or E2E checks passed. Sonar host/token are absent; analysis and quality gate remain Blocked. See the QC report for exact dispositions and evidence ownership.

Auth/session, actor authorization, encryption/KMS, workers, real API wiring, real stats, production scale tests and release hardening are not implemented in this slice. Cross-org resource consistency is not authenticated tenant authorization. Backend framework production-support review is still required; existing Spring Security configuration is a placeholder. Frontend screens remain previews with illustrative data.

This is work in progress, not a QC-verified production release. Required Blocked checks must pass before the corresponding item can be marked Verified. Dependency audit initially found a High tmp advisory through gts. A scoped patched dependency override and consumer compatibility smoke test were added; final independent retest of tmp0.2.7 reports zero npm audit vulnerabilities; lifecycle and string/array traversal rejection checks passed, as recorded in docs/qc. A clean dependency audit does not establish runtime security.

## Run with your configured environment

- Java21 + Maven: `mvn -f backend/pom.xml verify`.
- Formatting repair: `mvn -f backend/pom.xml spotless:apply`.
- Import rule: `python3 scripts/check-java-imports.py`.
- Node24: `cd frontend`, then `npm ci`, `npm run format:check`, `npm run lint`, `npm run build`.
- Review docs/engineering/CODING_STANDARDS.md before configuring SonarQube and CI secrets.

No tools, caches, credentials, node_modules, build outputs or downloaded runtimes are included in the source archive.
