# Team Workspace QC — bounded development gate PASS

## Scope and tested revision

Forest S05 stored Team board, Pending reasons/resolvers, assigned Agent reads and safe recorded mission/task event metadata. Positive execution, Mission/Task writes, runtime allocation, complete audit/permission management and production acceptance remain incomplete. No migration or production seed. SonarQube remains paused, not passed.

Final candidate `4919e241f3f881b90b5e3129cc055e48d33e1b6c` was tested as merge `8fce609` into Team directory base `749592784f7bbc0dc4ee751d013f9958c3749c68`. [Draft PR12](https://github.com/giangnguyen3260/agent_os/pull/12) stacks on unmerged PR11. Documentation-only follow-up does not change this tested application tree.

## Executed evidence — 21 October 2026 UTC

| Gate | Actual result | Evidence |
|---|---|---|
| Java formatting/imports, backend tests/coverage, FE format/lint/tooling/build | PASS;88 backend tests, zero failures/errors/skips, including5 Workspace integration tests | [Quality run39594167624](https://github.com/giangnguyen3260/agent_os/actions/runs/39594167624), job119202242044, completed11:46:03Z |
| Docker build, healthy stack, schema/API/SPA checks | PASS; completed11:51:51Z | [Docker run39594167601](https://github.com/giangnguyen3260/agent_os/actions/runs/39594167601), job119202242473 |
| Real-backend browser suite | PASS;189 instances in3.1m,171 baseline plus18 Workspace instances across desktop/tablet/mobile | Same Docker job; summary11:50:57Z |
| Native PostgreSQL Workspace read boundary | PASS; exact snapshot/task projection, bounded lanes, Pending/Agent/event pages, safe activity scope/fields and unchanged business state | Same Docker job;11:51:01Z |
| Native regression probes | PASS; Studio concurrency/replay, blocked activation no mutation, Agent/Team directories, account-first login/reset barrier, exact session restart/logout replay denial | Same Docker job;11:50:58Z through11:51:30Z |

Backend integration uses H2. Real-backend browser and native PostgreSQL are separate runtime/persistence evidence. The native probe compares all31 stored tasks across8 lane mappings,5-row previews and20-row pages;24 assigned Agents and25safe events are fully paged. Foreign/mixed-reference events and raw payload/actor IDs are excluded.

## Source and interaction review

Independent source QC reviewed backend scope/metadata projection and client malformed/stale handling. QC independently inspected both completed workflow logs and accepted the final bounded runtime gate PASS with no open findings. Findings resolved before final runtime gate: Refresh action was added; Activity validation requires exactly four allowed keys; Pending records require reason, valid waiting date and resolver, while non-pending records require those fields null. Internal mission UUIDs stay in the API for identity but are not displayed as board copy.

Coordinator Chromium mock-HTTP checks passed45 states/interactions across1440/768/390. Covered bounded board, empty/null legacy settings,503/null/wrong-scope/mismatched counters, Pending20/4, Agents20/4, Activity20/5, rejected extra Activity keys, member Add Agent visibility, native dialog Escape/focus, no horizontal overflow and zero UI writes. Desktop/mobile screenshots were inspected. Mock fixtures prove rendering/interactions only. Local Maven reports also showed88 passing tests; final CI evidence above supersedes that local checkpoint.

Coverage limits: injected503 is not an actual network outage. Browser CI does not individually enumerate all delayed-response/account-switch interleavings or every malformed-field combination; source epoch/cancellation/account/scope checks were reviewed. No production load/failover, broad accessibility or full security certification is claimed. Activity is recorded mission/task metadata, not complete Agent configuration history or an execution trace.

## Persistence and fixture isolation

Dedicated org14141414-1414-4414-8414-141414141414 contains an ACTIVE Team with24Agents and31tasks: one each DRAFT/READY/RUNNING/IN_REVIEW/COMPLETED/FAILED/CANCELLED, plus24 WAITING/BLOCKED Pending rows. An empty legacy Team and foreign org15151515-1515-4515-8515-151515151515 exercise missing/scope cases. Fixture IDs were checked against earlier datasets before use; c200/c300 prefixes were avoided to preserve Team creation fixtures. All are disposable CI data, never product seeds.

Native probe compares exact Team/Agent/task/audit rows and version/receipt counters before/after reads. Authentication/session bookkeeping is outside this business snapshot. Every non-null Activity mission/task reference must resolve inside the same Team; mixed foreign records are excluded even if another reference points locally. No raw event payload or actor ID is selected by the API.

See [cases](TEAM_WORKSPACE_TEST_CASES.md) and [contract](../engineering/TEAM_WORKSPACE_CONTRACT.md). PRs remain draft/unmerged. No deployment on the user's Mac or production is claimed.
