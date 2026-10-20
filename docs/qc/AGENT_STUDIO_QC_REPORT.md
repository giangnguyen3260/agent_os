# Agent Studio — final development QC

Decision: **PASS for the bounded development scope**, 20 October 2026. Draft PR8 remains stacked on unmerged Agent creation PR7. This is not production acceptance or deployment on the user's Mac. SonarQube remains temporarily paused by user instruction, not passed.

## Revision and evidence

Application `218598e873b35f7fa669b34fd58bec1a87f3a960`; final test/fixture head `b8746e90cf02f72c348a8c6617008cd37b37791e`. Final workflows checked out merge `609b480` of that head onto Agent creation base `4eaf4ef9c3e3ef219c54464b65a432552341ccfe`. Subsequent documentation-only changes do not change the tested application, fixtures or tests.

| Check | Result | Exact evidence |
|---|---|---|
| Backend formatting/import rules and tests | **66 passed**, zero failures/errors/skips; nine Studio integration tests plus 57 baseline | [Quality run 39421323785](https://github.com/giangnguyen3260/agent_os/actions/runs/39421323785), job 118622700217; aggregate 2026-10-20 12:20:10.122 UTC |
| FE lint, Prettier, tooling/auth regressions and production build | Passed | Same Quality run; build 12:20:33 UTC |
| Docker build and PostgreSQL schema/fixtures | Passed, through V007 | [Docker run 39421323772](https://github.com/giangnguyen3260/agent_os/actions/runs/39421323772), job 118622700649; Docker backend also passed 66 tests |
| Browser against actual backend/PostgreSQL | **117 passed in 1.6m**; 96 baseline plus 21 Studio instances | Same Docker run; 12:23:33.546 UTC; 1440/768/390 viewports |
| Native Studio concurrent HTTP update/replay | Passed: identical key/body returns same saved version; two keys at one expected revision produce one 200/one 409; earlier request still replays original response after a later edit | Same Docker run; 12:23:34.262 UTC |
| Existing native Agent Draft creation/replay/conflict | Passed | Same Docker run; 12:23:33.929 UTC |
| Native account-first login/reset barrier and stale-session denial | Passed | Same Docker run; 12:23:36.279 UTC |
| Exact session survives backend restart; logout rejects replay | Passed | Same Docker run; 12:23:59.447 UTC |
| Local rendered interaction review | 18 states, no horizontal overflow; malformed/503/dropped save-response retries, explicit stale-conflict reload, strict budget precision, compact-menu Escape and unsaved route discard checked at all widths | Mock HTTP fixtures only; not backend persistence evidence |

Native Studio probe uses simultaneous HTTP requests, not a forced database lock barrier or load test. Forced write-failure rollback, migration backfill and tenant-FK assertions are H2 integration evidence; fresh PostgreSQL migration and runtime persistence are separate CI evidence. Authentication's account-first barrier is not a Studio race test.

## Accepted scope

Studio → Edit configuration → Review configuration changes → Configuration saved reads and writes real data. Only current ADMIN can edit DRAFTs; members read ACTIVE and other statuses without an edit action. Identity, team, status, level and autonomy stay unchanged. Contract and requested model/skill/tool/free-text limits persist, as do nullable concurrency, token and monthly USD configuration values. Before/after diff comes from the current loaded revision and local validated edits; there is no illustrative version number or active-run ID.

Fresh enabled-account, credential-version and membership checks apply even on replay. Organization/account/membership/agent locking serializes writes; expected revision rejects stale edits. Scoped foreign resources return 404 within an authorized workspace; workspaces without membership return 403. Strict allowlisted DTOs reject coercion, invalid bounds, fractional integers and excess budget precision.

Current configuration, revision increment, authenticated actor/timestamp snapshot and permanent response receipt commit atomically. Snapshots are append-only through application code with tenant composite foreign keys. Replay is checked before current status/revision validation, while authorization remains first. Legacy drafts without configuration start at revision 0 and require a complete valid contract; existing configuration is backfilled to revision 1 without fabricated actor history. New creation records revision 1.

FE preserves edits on stale 409 and requires confirmed Reload latest. Unknown save outcomes freeze the immutable body, expected revision and key. Confirmed save then failed GET retries GET only. Account/workspace changes, revocation and destruction clear private state and ignore stale requests. No-op review and invalid input cannot issue PUT. Required-field focus, multiline text, Back/Cancel and responsive layouts were checked.

Seven Studio browser cases run across three widths: actual diff/persistence/reload, validation/no-op/Back/Cancel, stale conflict/reload, committed-response loss/replay, GET-only recovery, member/CSRF/foreign/ACTIVE controls and admin revocation. See [acceptance cases](AGENT_STUDIO_TEST_CASES.md).

## Review and resolved findings

Independent source review found a budget-precision tolerance mismatch; strict frontend decimal validation and a real-browser regression now reject 12.340000001 before review. Studio CI fixtures now use their own organization ID, preserving the dashboard's empty fixture. Scope tests distinguish workspace403 from authorized scoped404. Final logs above supersede preliminary runs that stopped before or during these corrections.

Independent QC also verified both final run logs, exact merge/head/base and native probe outcomes, and confirmed the bounded development gate PASS. No outstanding confirmed source blockers remain.

## Remaining scope and rollout

Activation, effective grants, provider execution, enforced runtime limits, full version-history browsing, production security/scale/failover and exhaustive accessibility acceptance remain open. The disabled activation action does not fabricate a checklist or sandbox result. Review [V007 rollout](../../backend/AGENT_STUDIO_MIGRATION.md) for backfill behavior, schema locks, receipt compatibility and retained requested text. Privileged database tamper resistance is not established by append-only service methods.
