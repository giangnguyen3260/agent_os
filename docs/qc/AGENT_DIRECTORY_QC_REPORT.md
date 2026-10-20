# Agent directory QC — bounded development gate PASS

## Scope and revision

Forest S07 directory reads, filters/pagination, scoped navigation and account access. Positive activation, live workload/capacity, effective grants and production acceptance remain incomplete. No migration or production seed is added. SonarQube remains paused, not passed.

Application `3ded5dab7aca60fcdf42704a2befec0408bffe2e` was tested as PR merge `df11852` into Activation Review base `01a2a06e90fbfc231720be34281e50dc8edb458f`. [Draft PR10](https://github.com/giangnguyen3260/agent_os/pull/10) stacks on unmerged PR9. Documentation-only follow-up does not change that tested application tree.

## Executed evidence — 20 October 2026 UTC

| Gate | Actual result | Evidence |
|---|---|---|
| Java format/imports, backend tests/coverage, FE format/lint/tooling/build | PASS; 78 backend tests, zero failures/errors/skips, including 4 directory tests | [Quality run39469068967](https://github.com/giangnguyen3260/agent_os/actions/runs/39469068967), job118788031098, completed17:48:30Z |
| Docker build, healthy stack, schema/API/SPA checks | PASS; completed17:53:40Z | [Docker run39469068283](https://github.com/giangnguyen3260/agent_os/actions/runs/39469068283), job118788028728 |
| Real-backend browser suite | PASS; 153 instances in2.3m, 135 baseline plus18 directory instances across desktop/tablet/mobile | Same Docker job; summary17:52:47Z |
| Native PostgreSQL directory projection and read boundary | PASS; exact projection/order, pagination, literal search, lifecycle filters, tenant scope and unchanged business state | Same Docker job;17:52:50Z |
| Native Studio/activation/auth/restart regressions | PASS; Studio concurrency/replay, blocked activation no mutation, account-first login/reset barrier, exact session restart and logout replay denial | Same Docker job;17:52:48Z through17:53:19Z |

Backend integration tests use H2. Native PostgreSQL and browser execution supply separate persistence/runtime evidence. Local Chromium mock-HTTP checks additionally passed18 rendered states across1440/768/390: populated, empty, HTTP503, null envelope, wrong scope and member controls. Checked no horizontal overflow, readable primary action, Status exact-label selection, dialog Escape/focus and zero UI writes. Coordinator inspected desktop/mobile screenshots. Mock fixtures prove rendering/interactions only.

## Findings and source review

Rendered review found a broad link-color rule overriding the primary Create Agent text. Scoped styling and a computed-color regression resolved it. Earlier browser execution found six Status-label locator timeouts and three retry synchronization timeouts across the widths. An explicit accessible Status name and response/settled-loading waits resolved them. No timeout extension or assertion removal was used. The final full runtime gate above passed after these fixes.

Independent QC reviewed service/controller/DTO, Angular page/service/template and backend/browser cases. No actionable source findings remained. It reviewed the final two-file Status/retry fix at local equivalent tree5c1df8b. Fresh account/credential/membership, bound escaped literal search, combined filters, stable pagination, nullable legacy configuration, malformed envelopes and stale-data cancellation were checked. Independent QC then inspected the completed quality/Docker logs and accepted the bounded runtime gate PASS, with no new findings.

Coverage limits: the HTTP error case injects503, not a transport failure. Delayed responses during account/workspace switches and every malformed individual field/pagination shape are not individually exercised by browser cases; source guards were reviewed. This is not complete accessibility, production-security or load/failover certification.

## Persistence boundary

Dedicated CI organization88888888-8888-4888-8888-888888888888 has25 Agents across all lifecycle states, assigned/unassigned Teams, role matches and literal special characters. Foreign scope and dedicated admin/member accounts avoid baseline collisions. Revocation tests restore membership in finally. These are disposable CI fixtures, never application seeds.

Native probe compares the complete API projection/order to PostgreSQL, follows fixed20-row pages, checks literal queries and lifecycle counts, rejects foreign Team/workspace, and compares exact Agent/configuration rows plus task/version/receipt counts before/after reads. Auth/session bookkeeping is outside that business snapshot. Requested concurrency is not allocation; no runtime data is fabricated.

See [cases](AGENT_DIRECTORY_TEST_CASES.md) and [contract](../engineering/AGENT_DIRECTORY_CONTRACT.md). PRs remain draft/unmerged; no deployment on the user's Mac or production is claimed.
