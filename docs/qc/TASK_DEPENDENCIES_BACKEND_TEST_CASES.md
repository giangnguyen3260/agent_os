# Task dependencies backend QC test cases

Contract: `docs/engineering/TASK_DEPENDENCIES_CONTRACT.md`, frozen 2026-10-30. This is authored coverage and source-validation evidence, **not a backend runtime pass**.

## Ownership and execution

Only the five Java files in `backend/src/test/java/com/agentos/taskdependencies/` and this document were authored by backend QC. No production, fixture script, workflow, frontend or commit changes.

- `TaskDependenciesIntegrationTest` is public and discovered by normal `*Test` selection; isolated H2 PostgreSQL mode, public `clock_timestamp` alias, real Spring HTTP controllers/security/session/CSRF header and JDBC assertions.
- `TaskDependenciesPostgresIT` is public, native-only, and inherits **fixture helpers only**. It never inherits the H2 test methods or any unrelated mutator suite.
- `DependencyHttpFixture` owns UUID-scoped setup/cleanup, persisted ADMIN sessions, CSRF header requests and JSON/JDBC observations. No dependency service calls or production hash helpers.
- `DependencyProtocolOracle` independently encodes frozen graph/source/target/command tuples and the previous input/owner tuple domains. Legacy receipts are seeded with independent unchanged command digests.
- `DependencyConnectionProbe` observes actual server transaction settings on the same JDBC connections used for scoped reads/writes. Imported only into the PostgreSQL suite.

Native selector: add/retain `TaskDependenciesPostgresIT` in main's existing PostgreSQL selector. A minimal dependency-only-plus-normal-suite selection is `-Dtest='*Test,TaskDependenciesPostgresIT'`; do not replace or remove existing native IT selectors. Default Surefire `*Test` discovery alone does not run this native class.

Native environment: `TASK_DEPENDENCIES_TEST_JDBC_URL`, `TASK_DEPENDENCIES_TEST_DB_USER`, `TASK_DEPENDENCIES_TEST_DB_PASSWORD`; each independently falls back to corresponding `TASK_EDIT_TEST_*`. URL must start `jdbc:postgresql:`; missing/blank configuration fails instead of silently using H2. Use a disposable PostgreSQL database. Migration schemas, trigger/functions and every per-test organization/account are UUID-isolated and cleaned up. PostgreSQL pool size is eight for holder/writer/observer connections.

## Coverage and required observations

| Area | Assertions |
| --- | --- |
| HTTP/wire | Exact ordered context/candidate/page/receipt/lookup/Task3 fields; stable source token; first201/replay200; current ADMIN session, CSRF header, denial before malformed child/body/query, structural404, missing/foreign target distinctions. |
| Strict parsing | Decimal page grammar; unknown/duplicate query parameters including encoded duplicate; raw malformed UTF8/percent encoding; stripped literal `%_!` search; GET bodies/chunked bodies; duplicate/invalid media and idempotency headers; recursive JSON duplicates, extra fields, scalar coercion/exponent/fraction/null, source/duplicate IDs, 21-target limit, invalid UTF8, 16KiB limit. |
| Atomic semantics | Add, retain/replace, remove-one and clear; protected source/parent/target columns; dependency revision; edges/event/receipt/audit effects; zero-effect no-op, canonical-order replay, changed-body idempotency conflict; exact audit payload fields. |
| Historical recovery/privacy | Own actor only, explicit missing/other-actor null; identical receipt despite archived/paused parents and unsafe current target; malformed receipt and inconsistent event500; no dynamic historical target resolution. |
| Graph | 1200-edge path plus tail reaches source; disconnected cycle; multi-target prospective cycle; 21 legacy outgoing edges; unsafe off-path public title; all nine persisted target states, actual blank legacy title; wrong team/mission/org targets; foreign unsafe graph never read or disclosed. |
| Fingerprints/ABA | Independent exact frozen tuple/digest oracle; off-path title/status/time/input/owner/dependency revision, new node and off-path edge invalidate source and candidate; source conflict precedes blockers; equal-time dependency ABA invalidates other editors; real HTTP input/owner ABA invalidates dependency token; exact old tuple positions plus appended revision. |
| Old protocol compatibility | Seeded pre-upgrade input/owner receipts replay old fingerprint strings with unchanged command domains; fresh-key old fingerprints conflict. Old create receipt preserves Java UUID natural canonical order even when text lock order differs. |
| Multiline private compatibility | Source, selected target and disconnected task contain exact TAB/CR/LF description/acceptance/pending values: context/list/detail200, fresh actual TaskCreate201, private data absent from dependency DTOs/other-task creation response, original rows unchanged. Unsafe public titles remain fail-closed. |
| Eligibility/paging | 20 selected targets independent of 27-item search pagination; exact total, page1 has seven rows, page100000 empty, independent candidate lookup beyond page; deterministic combined blockers; unknown safe status blocks; exhausted unrelated counters do not block. |
| Native waits/races | `pg_blocking_pids` observed holder/writer wait graph, including chained waiters; org/team/task waits followed by fresh conflict and post-release commit clock. Organization wait revokes role/membership/account/credential; no effect. Reciprocal/two-edge and three-node races yield one commit; same-key201+200 identical receipt; different-key one winner. |
| Native writer protocol | Fresh TaskCreate and input edit visibly wait on held organization mutex, then commit; pre-writer dependency token conflicts. No assumed timing-only concurrency success. |
| Native rollback | Isolated per-org PostgreSQL fail triggers on task update, edge insert, event, receipt and audit. Each500 restores task/edge/event/receipt/audit state, lookup stays null and identical key/body subsequently commits once. Triggers/functions always removed. |
| Native migration/isolation | Separate schema migrated20→21: existing task rows/edges/input receipt bytes retained, dependency revision0, no synthetic provenance. Same-connection server settings assert every read REPEATABLE READ/read-only and command READ COMMITTED/writable. |

## Exact test method inventory

Methods below are authored scenarios, not passed executions or inflated parameter-invocation totals.

### TaskDependenciesIntegrationTest

- `exactContextCandidatesAndReceiptWireWithSessionAndCsrf`
- `addReplaceRemoveAndClearHaveFourEffectsAndPreserveOtherFields`
- `noOpWritesNothingAndCanonicalOrderReplaysButChangedBodyConflicts`
- `historicalRecoverySurvivesBlockedParentsAndUnsafeCurrentGraphAndIsActorPrivate`
- `allNineStatesAndBlankLegacyTitlesAreRealSelectableRows`
- `literalSearchExactCountAndPagingAreIndependentOfSelections`
- `twentyIsSelectionLimitNotGraphOrSearchLimit`
- `arbitraryDepthReachabilityAndDisconnectedCycleFailClosedWithoutLeaks`
- `multiTargetProspectiveCycleAndMissingTargetsHaveExactErrors`
- `unsafeOffPathMetadataAndExcessiveLegacyEdgesCannotBeCleared`
- `fullGraphChangesInvalidateSourceAndCandidateTokens`
- `sourceConflictPrecedesBlockersAndDeterministicBlockersDoNotWrite`
- `strictPageSyntax`
- `queryAndGetBodyRejectionsAreReadOnly`
- `invalidJsonNeverReservesAKey`
- `strictCommandTypesDuplicatesUtf8SizeHeadersAndCsrf`
- `authorityPrecedesMalformedWireAndChildScopeIsExact`
- `malformedStoredReceiptAndEventFailSafelyWithoutCurrentMetadataLeak`
- `existingEditorsPreserveOldTuplePositionsAndAppendOnlyDependencyRevision`
- `dependencyAbaWithIdenticalTimestampsInvalidatesBothOtherEditors`
- `reverseHttpAbaWithIdenticalTaskValuesInvalidatesDependencyTokens`
- `preUpgradeReceiptsStillReplayTheirOriginalCommandDigest`
- `fingerprintsAndPersistedCommandDigestMatchIndependentFrozenTupleOracle`
- `unknownSafeStatusBlocksWithoutPretendingToBeKnownOrRepairing`
- `exhaustedUnrelatedRevisionsDoNotBlockDependencyCommand`
- `legalWrongTeamAndMissionTargetsAreIndistinguishableFromMissing`
- `oldCreateReceiptUsesHistoricalUuidNaturalOrderAfterLockOrderChanges`
- `encodedDuplicatesAndMalformedRawQueryEncodingAreRejected`
- `foreignGraphIsNotReadOrDisclosedAndForeignTargetsBehaveAsMissing`
- `legitimatePrivateMultilineInputsOnSourceTargetAndDisconnectedNodeDoNotPoisonGraphOrCreate`

### TaskDependenciesPostgresIT

- `freshSourceReadAndCommitClockFollowObservedOrgTeamTaskWait`
- `authorizationIsFreshAfterObservedOrganizationWait`
- `sameKeyHasExactlyOneEffectAndIdenticalReceipt`
- `differentKeysOnSameSnapshotHaveOneWinner`
- `reciprocalAndThreeNodeCycleRacesCannotCommitCycle`
- `everyPersistenceFailureRollsBackAllEffectsAndKeyCanBeReused`
- `taskCreateAndEditParticipateInOrganizationSerialization`
- `allHttpReadsUseServerRepeatableReadReadOnlyAndWritesReadCommitted`
- `migrationRetainsLegacyRowsEdgesAndReceiptsWithRevisionZero`

## Validation performed and remaining gates

- Formatter manifest confirmed `Implementation-Version: 1.22.0`, matching `backend/pom.xml`. Owned Java files formatted successfully with `/tmp/google-java-format.jar` on Java17; subsequent dry-run is clean.
- All five owned Java sources compiled in a scratch API-shaped harness with the available Java17 compiler module. The harness substitutes minimal Spring/Jackson/JUnit/AssertJ/Flyway APIs; this verifies Java syntax, type/lambda structure and helper relationships **only**, not actual dependency compatibility or application behavior. Its varargs warning originates from the simplified assertion stub.
- Source inventory checks verify public entry classes, helper-only inheritance, no dependency service coupling, native environment fallback and no disabled storage constraints. Scoped diff whitespace check passed.
- No Maven is installed locally; no Spring/H2/PostgreSQL tests or application runtime were executed by this author. Actual Java21/Maven compilation, Spotless and H2/native tests remain mandatory in CI. Main owns workflow selection and native probe.
- No FK constraints were disabled or impossible stored foreign references fabricated. Legal scoped cyclic/overfull edges, unsafe public text, unsafe foreign graph, malformed receipt JSON and inconsistent event JSON provide isolated fail-closed fixtures. Constraint-bypassed dangling/orphan storage and actual process restart are not claimed as exercised here; main's native probe/restart gates remain separate.
- Full browser/frontend checks are main's evidence, not backend suite evidence. Runtime failures must be resolved against the frozen statuses and schemas; expectations were not loosened to accommodate current implementation.
