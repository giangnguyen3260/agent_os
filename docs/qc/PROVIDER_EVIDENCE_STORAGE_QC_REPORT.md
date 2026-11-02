# Provider evidence storage verification

Date: 2 November 2026. Baseline fae8aa2e55cdc9e9dd1d2e56836765df6cdbb86f;
branch feature/provider-evidence-storage. Foundation only: V025 empty health,
integrity and publisher-fence tables, exact credential binding and bounded time
constraints, plus an explicit operator-only capability-role script.

Reader can SELECT private version/evidence metadata. Publisher intentionally has
no grants. There is no configured trusted producer, public list route, successful
health default, network provider/key call or secured current Compose deployment.
Protected deployment, immutable runtime versions and guarded publication remain
prerequisites documented in backend/PROVIDER_EVIDENCE_OPERATIONS.md.

Google Java import and formatter dry-run checks passed locally. Local Maven
execution was blocked by dependency-repository DNS resolution; it is not a test
pass. Dedicated H2/native PostgreSQL suites and full Quality must execute on
Java21 CI. Missing explicit disposable-role opt-in fails native tests rather
than silently skipping. Tests execute the actual operator script on an isolated
random database and inspect real capability-role privileges.

Independent review is in PROVIDER_EVIDENCE_STORAGE_REVIEW.md. Authored tests,
workflow selectors and source review are not runtime evidence. Record completed
logs, actual test/failure/error/skip counts and exact tested commit before
acceptance. No merge/deployment/user database operation or Sonar gate is claimed.
