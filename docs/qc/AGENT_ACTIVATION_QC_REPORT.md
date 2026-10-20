# Agent activation review QC — bounded development gate

## Scope and revision

Real saved-configuration/Team preflight and a fail-closed server activation boundary. This does not deliver positive activation, provider access, effective model/tool grants, capacity allocation or execution workers. UI Activate remains disabled. SonarQube is paused by user instruction; no Sonar gate passed. No schema migration or production seed data is introduced.

Application head `3f9fbf9f3d7ce0892de0030bbfd91a70d64b9335` was tested as merge `e420532` into Studio base `fa24cc66b183a75ff3a0dd040cc1f8f542cf5656`. Draft [PR9](https://github.com/giangnguyen3260/agent_os/pull/9) stacks on unmerged Studio PR8. Documentation-only follow-up does not change that tested application tree.

## Executed evidence — 20 October 2026 UTC

| Gate | Actual result | Evidence |
|---|---|---|
| Java style/imports, backend tests/coverage, frontend format/lint/tooling/build | PASS; 74 tests, zero failures/errors/skips, including 8 activation tests | [Quality run39424496390](https://github.com/giangnguyen3260/agent_os/actions/runs/39424496390), job118633482984, completed12:45:19Z |
| Docker build, healthy Compose stack, schema/API/SPA checks | PASS | [Docker run39424496393](https://github.com/giangnguyen3260/agent_os/actions/runs/39424496393), job118633482840, completed12:49:29Z |
| Real-backend browser suite | PASS; 135 instances in2.0m, 117 baseline plus18 activation instances across desktop/tablet/mobile | Same Docker job; summary12:48:45Z |
| Native PostgreSQL activation boundary | PASS; configured checks, unavailable integrations, concurrent blocked/stale denial and zero mutation | Same Docker job; probe success12:48:46Z |
| Native Studio concurrency/replay and auth barrier | PASS | Same Docker job; Studio12:48:45Z, account-first login/reset barrier12:48:48Z |
| Session restart/logout replay | PASS; exact session survives restart, logout rejects replay | Same Docker job;12:49:16Z |

Backend integration tests use the test datasource; they are not substitutes for the native PostgreSQL probe. The latter saves real requested configuration through Studio, verifies the first six checks PASS and the three missing integrations UNAVAILABLE, then compares exact Agent/configuration rows and history/receipt/task counters before and after concurrent current-revision POSTs, a stale request and CSRF denial. Blocked requests return409 ACTIVATION_BLOCKED with the current review; stale requests return409 CONFIGURATION_CONFLICT. No business mutation occurs. This concurrent HTTP probe is not a forced database lock barrier or a load test. Session bookkeeping outside the stated business snapshot is not an activation mutation.

## Source and UI review

QC found a null200-envelope callback failure before publication. Safe envelope access and the browser regression resolve it. Independent QC inspected both completed workflow logs and closed the bounded preflight gate PASS, with no remaining source findings. Fresh ADMIN authorization, same-organization scope, strict revision input and CSRF checks protect the API. Review errors/role denial clear previous checklists; requested model text never becomes effective permission. Team checks compare configured maxima and do not claim allocation/enforcement.

Local mock-HTTP review additionally passed21 rendered states across1440/768/390: loading, incomplete/current configuration, configured-but-unavailable, transport error, null envelope, wrong scope and member denial. Refresh, stale-check clearing, dialog Escape/focus, no horizontal overflow and zero UI writes were checked. These local fixtures prove rendering/interactions only. The real-backend CI suite is the persistence/runtime evidence.

See [acceptance cases](AGENT_ACTIVATION_TEST_CASES.md) and [delivery contract](../engineering/AGENT_ACTIVATION_REVIEW_CONTRACT.md). Development preflight evidence is green. Positive activation remains blocked until authoritative provider/model access, tool grants and a worker integration exist. Full designed flows, production security/scale, broad accessibility and release hardening are not accepted by this report. No deployment on the user’s Mac or production is claimed; PRs remain draft and unmerged.
