# Team directory QC — bounded development gate PASS

## Scope and tested revision

Forest S03 persisted Team directory, search/lifecycle filters, pagination, detail links and actual filtered task reads. Health/runtime/cost/capacity, positive activation and production acceptance remain unavailable or incomplete. No migration or production seed is introduced. SonarQube remains paused, not passed.

Application `3ab36f4f65240821b0f63b416f8019749cb8b678` was tested as merge `b5731b2` into Agent directory base `e00617bcb6ed1a909ddccbe4acc9c6fedb66b44d`. [Draft PR11](https://github.com/giangnguyen3260/agent_os/pull/11) stacks on unmerged PR10. Documentation-only follow-up does not change that tested application tree.

## Executed evidence — 21 October 2026 UTC

| Gate | Actual result | Evidence |
|---|---|---|
| Java format/imports, backend tests/coverage, FE format/lint/tooling/build | PASS;83 backend tests, zero failures/errors/skips, including5 Team directory tests | [Quality run39527476954](https://github.com/giangnguyen3260/agent_os/actions/runs/39527476954), job118985419416, completed01:29:46Z |
| Docker build, healthy Compose stack, migration/API/SPA checks | PASS; completed01:36:19Z | [Docker run39527477009](https://github.com/giangnguyen3260/agent_os/actions/runs/39527477009), job118985419613 |
| Real-backend browser suite | PASS;171 instances in4.3m,153 baseline plus18 Team directory instances across desktop/tablet/mobile | Same Docker job; summary01:35:37Z |
| Native PostgreSQL Team directory projection | PASS; independent Agent/task counts, pagination/order, literal filters, tenant scope and unchanged business state | Same Docker job;01:35:41Z |
| Native regression probes | PASS; Studio concurrency/replay, blocked activation no mutation, Agent directory reads, account-first login/reset barrier, exact session restart/logout replay denial | Same Docker job;01:35:38Z through01:36:11Z |

Backend integration tests use H2. Native PostgreSQL and real-backend browser execution provide separate persistence/runtime evidence. Tests exercise25Teams, all lifecycle states, literal special-character names, manager/Agent counts, and a3Agent/6task case with2Running,2Pending,1Review and1Completed. Counts do not multiply to18.

## Source, interaction and findings

Independent source QC closed with no blocking findings. QC then independently inspected both completed workflow logs and accepted the final candidate gate PASS with no new findings. Review resolved nullable legacy purpose handling and ensured task query parameters drive an actual scoped read. Teams outside the dashboard page resolve through the authorized detail API and appear exactly once as the selected Team control option. Existing dashboard summary behavior stays compatible.

Initial CI found a wrong expectation in the new account-revocation test. Stale credentials and disabled accounts clear the principal and return401; authenticated membership denial returns403. The test was corrected to that established security contract without changing production authentication behavior. All83 tests passed in the final run above.

Coordinator Chromium mock-HTTP QA passed33 states/interactions across1440/768/390: populated/empty,503/null/wrong-scope/malformed-count clearing, null-purpose/manager legacy data, three task deep links per width with Team resolution outside an empty dashboard page, member create visibility, native dialog Escape/focus, no horizontal overflow and zero UI writes. Desktop/mobile screenshots were inspected. These fixtures prove rendering/interactions only, not PostgreSQL persistence.

Coverage limits: HTTP503 is simulated, not an actual network outage. Delayed responses during account/scope changes and every malformed field combination are not individually exercised in browser CI; source cancellation/identity guards were reviewed. Broad accessibility, production-security, load/failover and positive runtime acceptance are not certified.

## Read boundary and fixture isolation

Disposable org12121212-1212-4212-8212-121212121212 and foreign org13131313-1313-4313-8313-131313131313 were checked against existing fixture IDs before creation. Dedicated admin/member accounts use the random CI password; revocation restores membership in finally. Fixture SQL is never an application seed or migration.

Native probe compares the complete API projection and created_at DESC/id ordering with PostgreSQL, checks literal queries and status totals, rejects the foreign organization, then compares exact Team/Agent/task rows and receipt/version counters before/after reads. Auth/session bookkeeping is outside the stated business snapshot. Independent Agent/task aggregates prevent Cartesian multiplication and retain same-organization manager scope.

See [cases](TEAM_DIRECTORY_TEST_CASES.md) and [contract](../engineering/TEAM_DIRECTORY_CONTRACT.md). PRs remain draft/unmerged; no deployment on the user's Mac or production is claimed.
