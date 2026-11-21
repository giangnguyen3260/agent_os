# Configured Mission plan acceptance validation

21 November 2026, Asia/Ho_Chi_Minh. Stacked on PR121 head `081f492231a1668f816f3eaa5ba8371fffd54e21`.

Implemented: opt-in API/UI acceptance, V053 immutable snapshot/receipt and current revision selection, strict JSON command, scoped fresh ADMIN/CSRF checks, org serialization lock, source/READY fingerprints, legacy-plan rejection, exact historical replay and stale configuration display. Mission becomes/remains READY; Tasks stay DRAFT. No Runs, controls, dispatch or provider calls are created.

Executed locally:

- 7 acceptance integration cases on H2 PostgreSQL mode: complete DAG+immutable prior content+reaccept+historical replay, stale/cycle/legacy denial, current authority/integrity, HTTP CSRF/strict-body/no-store, database-failure rollback, existing-control denial, concurrent same-command single insertion.
- 5 existing review integration cases passed with default acceptance off.
- 30 mocked browser cases (21 prior review regressions +9 acceptance cases), across desktop/tablet/mobile: explicit confirmation, stored content/escaping, unchanged suppression, stale display, ambiguous outcome exact retry, current authority erasure.
- Angular production build, frontend lint, Java compilation/import/format checks and diff whitespace checks passed.

`MissionPlanAcceptancePostgresIT` inherits the seven acceptance cases, requires real PostgreSQL environment variables, creates/drops only an isolated random schema and has no H2 fallback. It is included in Quality workflow from this feature's first publication. It was compiled locally; native execution awaits matching-head CI. Browser checks use mocked API responses; they do not prove full-stack deployed acceptance. No paid provider acceptance was performed.

Release status: Draft feature, unmerged and undeployed; both review/acceptance flags default off. Record exact published head and CI separately.
