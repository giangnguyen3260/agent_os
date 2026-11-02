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

## C1 focused execution

Source54d4d0a909d15f4944a1fd4e961dfb9006154f3b,
treef2dca61e7d018e424a640870a86c0295d1f68978. Dedicated run41382948285,
job125077492953 completed SUCCESS on Java21. Actual completed logs record3 H2
full-Flyway-chain/constraint tests and6 native PostgreSQL tests:9 total,0
failures/errors/skips; both Maven invocations BUILD SUCCESS. Native tests use
the actual operator script, isolated random database/non-superuser owner,
SET ROLE and explicit permission-denied SQLSTATE checks. These are capability
fixtures, not a secure current Docker deployment or production publisher.

BE also executed a standalone H2 complete-chain smoke locally. That is structural
compatibility, not an extra JUnit count. Full Quality/Docker remain pending.
A test-only follow-up adds denied UPDATE/DELETE/TRUNCATE/ALTER assertions on the
publisher registry; it changes no migration, grant or runtime behavior and needs
its own completed test result. No future publication, protected rollout or public
list claim is inferred from9 passing storage tests.

## C1 full Quality; C2 role assertion hardening

Completed Quality run41382948324/job125077493040 records1451 backend tests,
0 failures/errors/skips, BUILD SUCCESS, configured dependency audit0 and successful
frontend checks/build on C1. These overlap the dedicated tests, not extra unique
coverage. Docker formatting/build/start and early schema/provider probes passed;
full Docker regression remains pending at this checkpoint.

C2e7651d6b65eb4e7c61e9c7663c8491324e0b7f97,
treea00df1a1d48d1a7c4eae4a785982f8d926765111, changes only four additional denied
publisher-registry mutation assertions and this report. Dedicated run41383374160,
job125078794188 completed SUCCESS:3 H2 tests and6 native PostgreSQL tests, all
0 failures/errors/skips. Both Maven invocations BUILD SUCCESS. C2 full Quality
and Docker results are not inferred from C1. Production DDL/grants are unchanged.

## C2 full-suite resource failure and bounded-cache correction

Completed Quality job125078794324 did NOT pass:1446 reported cases,0 failures,
1 initialization error. ProviderEvidencePostgresIT failed its first administrative
connection with PostgreSQL SQLSTATE/diagnostic `too many clients already`, before
six native methods could execute. This result is separate from C2's dedicated9/0.

The full suite retains many Spring contexts and their Hikari pools. A cache-pressure
explanation is consistent with the full-only failure and source inventory, but no
complete pg_stat_activity snapshot was captured at the failing instant. The new
fixture's DriverManager connections use try-with-resources; cleanup does not kill
unrelated databases or connections.

Quality now sets the JVM property `spring.test.context.cache.maxSize=4`: evicted
contexts close rather than retaining all their pools. All test selections, assertions,
per-context pool capacities, retries and production configuration remain unchanged.
This bounds fixture resource lifetime instead of increasing PostgreSQL capacity or
accepting unavailable results. Official Spring context-cache documentation describes
LRU close/eviction and this property:
https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html

The correction still requires a fresh full-suite result; previous C1 success is not
a substitute. Publication of the change alone does not close this failure.


## C3 full regression closure — 2 November 2026

This checkpoint supersedes the pending C3 statements above. Tested source is
f1f05030791a9e494d26326d73084f08308c19a1, tree
eeb355e684b1c6cf6bb3a40ef80058c65862bcb5, tested merge
86283c54e41d6d2ab1d8f940dd6ba7be560eb5a2.

Quality run41384029263/job125080790013 completed SUCCESS:1451 backend tests,
0 failures/errors/skips, BUILD SUCCESS at03:39:58 UTC; audit0 and frontend
checks/build passed. This is a fresh completed full-workload result after bounding
the test-context cache, not a reassigned C1 pass. C2's failed1446/0/1 remains
recorded above. No test selector, assertion, pool size or retry was weakened.

Dedicated C3 run41384029230/job125080790024 independently recorded3 H2 and6 native
PostgreSQL cases, all0 failures/errors/skips. Full Quality overlaps these cases;
counts are not added as unique tests.

Docker run41384029113/job125080789880 and format125080789757 completed SUCCESS.
Actual logs:929 regular backend tests with0 failures/errors/skips; disjoint browser
33+75+42+81+90+780=1101 passed, plus3 separate focused repeats. Native provider
CLI/emitted-SQL protocol/cookie probe passed03:41:09 UTC. Retained PostgreSQL and
SMTP probes passed. Exact existing session survived backend restart and logout
rejected replay at04:21:27 UTC. No new UI or screenshot acceptance is inferred.

This closes runtime acceptance of the empty storage/restricted-role foundation.
It does not close Q3/Q4: current Compose still uses its bootstrap administrator;
no protected deployment, trusted provenance/epoch/CAS/sticky-failure publication,
working observer, PRESENT projection or public list route is delivered. Publisher
remains deliberately ungranted. Operator SQL was executed only in disposable CI.
No user DB operation, provider/key call, merge/deploy or Sonar gate is claimed.
Final evidence publication changes Markdown only; all execution attribution stays
with the exact source/merge above, not an inferred pass for a later docs commit.
