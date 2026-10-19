# Implementation status — 19 October 2026

## Current branch outcome

On `feature/dashboard-live`, the organization overview and task drilldown are implemented as a bounded read flow from Forest S01 on top of unmerged workspace PR4. V004 adds real task persistence with tenant/team/mission integrity; Running/Pending/Review derive from unique task rows. Capacity and performance remain unavailable because their runtime sources do not exist. Application revision `3c5abe22` is verified by recovered CI execution logs:39 backend tests and54 browser instances passed, including corrected filter state/team assertions at all three widths. See [dashboard QC](docs/qc/DASHBOARD_QC_REPORT.md) for exact runs and scope.

Prior workspace application revision `4f3e795` passed 33 backend tests and 33 browser instances, with real PostgreSQL/Mailpit, reset race and restart/session/logout probes. Login/recovery/workspace changes remain stacked, draft and unmerged; no deployment to the user's Mac is claimed. Sonar remains temporarily paused, not passed.

| Scope | Actual state |
|---|---|
| Human login/session, recovery, workspace selection | Development gates verified; see the respective reports under docs/qc |
| Organization overview and pending-task read drilldown | Bounded development read gate verified; actual PostgreSQL rows, no sample business data |
| Capacity/performance/cost | Explicitly unavailable; no allocator, execution-run history or billing ledger implemented |
| Task mutation/execution, team/agent creation screens, approvals and administration | Full designed flows incomplete; older static previews remain blocked |
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
