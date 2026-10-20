# Agent Draft — final development QC

Decision: **PASS for the bounded development scope** on 20 October 2026. PR7 remains draft, stacked on unmerged Team PR6. Production acceptance and deployment on the user's Mac are not claimed. SonarQube is temporarily paused by the user's instruction, not passed.

## Tested revision

Application `f6185f6ff7e2dffff9c256d662882ce4b5150f8f`; test-locator-only head `2678d402f0fd53a703691aba29f66a6df064ff41`. Both final workflows checked out merge `bf94991` of that head onto Team base `e5f57e0a0e248ef32a111525f49ecaceec332fd0`. Later documentation-only changes do not alter the tested application or tests.

## Executed results

| Gate | Result | Evidence |
|---|---|---|
| Backend formatting, imports and tests | 57 passed; zero failures, errors or skips; includes eight new Agent integration tests | [Quality run 39353293613](https://github.com/giangnguyen3260/agent_os/actions/runs/39353293613), job 118402250502; aggregate 2026-10-20 01:26:01 UTC |
| FE lint, Prettier, tooling/auth-epoch regressions and production build | Passed | Same Quality run; build 01:26:23 UTC |
| Docker build, PostgreSQL schema and fixtures | Passed; migrations through V006 | [Docker run 39353293715](https://github.com/giangnguyen3260/agent_os/actions/runs/39353293715), job 118402251199 |
| Real-backend browser suite | **96 passed in 2.3m**: 72 baseline plus 24 Agent instances at 1440/768/390 | Same Docker run; completion01:30:35.201 UTC |
| Native PostgreSQL concurrent Agent HTTP creation/replay/conflict | Passed: one 201 plus one 200, same ID; changed body 409; persisted detail read | Same Docker run; 01:30:35.808 UTC |
| PostgreSQL account-first login/reset barrier and stale-session denial | Passed | Same Docker run; 01:30:37.483 UTC |
| Exact session survives backend restart; logout denies replay | Passed | Same Docker run; 01:31:05.596 UTC |
| Local rendered inspection | 18 states across desktop/tablet/mobile; no horizontal overflow; malformed-success retries and delayed-prefill checks passed at all three widths | Mock HTTP fixtures; visual/interaction evidence only |

Docker job completed successfully at 01:31:13 UTC. Native Agent probe uses simultaneous HTTP requests, not a forced database lock barrier or load test. Transaction rollback and additional negative/concurrency cases remain H2 integration evidence. The separate account-first barrier above tests authentication, not Agent creation.

## Accepted behavior

Identity → Contract → Capabilities → Review & test writes only after final confirmation. Agent identity, exact optional description, required contract and requested capability text persist; level MID, autonomy SUGGEST and status DRAFT are server-owned. Optional team assignment requires a same-organization DRAFT/ACTIVE team and leaves its manager/status unchanged. Legacy detail reads expose null optional configuration.

Every creation/replay revalidates enabled account, credential version and ADMIN membership. Reads require current membership; candidates require ADMIN. Missing CSRF and MEMBER writes are denied. Scoped detail reads do not expose foreign agents. Parameterized queries, escaped literal search, fixed page 20 and stable name/id ordering bound collection reads.

Agent, configuration and permanent response receipt commit atomically. Organization/account/membership/team lock order matches the Team creation convention. Identical canonical requests replay the original response before team eligibility is reconsidered; changed bodies conflict. Reads do not acquire organization write locks.

FE retains values through Back, focuses invalid fields and confirms unsaved navigation. Identity/workspace changes, access loss and destruction clear private state. Ambiguous outcomes freeze the same body/key for retries; malformed success does not navigate. Confirmed creation then failed detail GET retries only GET. A late URL-team candidate response cannot change the team after Identity or during a frozen request.

Eight browser scenarios cover delayed prefill, validation/Back/Cancel, unassigned persistence, selected-team persistence, lost-response replay, MEMBER/CSRF denial, admin revocation and detail-read recovery. Each runs at all three viewports. See [acceptance cases](AGENT_CREATE_TEST_CASES.md).

## Defects resolved and review

V006 initially repeated a team index already introduced by V004; it now reuses that index and migrates successfully. Async candidate prefill is restricted to editable Identity state and has a delayed-response regression. An exact optional-test button locator was corrected to match the rendered label; the final suite has no skipped or failed instances. Independent QC verified both final workflow logs and confirmed the bounded development gate PASS, with no outstanding confirmed source blockers.

## Limits and rollout

Models, skills, tools and limits are requested configuration, not provider approval, effective grants or runtime enforcement. Optional sandbox execution is disabled and no test result is fabricated. Activation, Agent Studio editing/permissions, execution, full administration, exhaustive accessibility/visual acceptance, production load/failover and release hardening remain open. Review [V006 rollout](../../backend/AGENT_DRAFT_MIGRATION.md), including the paginated collection response compatibility change and receipt retention, before applying to existing data.
