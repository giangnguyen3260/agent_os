# Task Draft Edit — V017

Implements the frozen `docs/engineering/TASK_DRAFT_EDIT_CONTRACT.md` in the isolated
`com.agentos.taskediting` package. No shared authorization helper, Task Detail DTO,
routes, CI, fixture, runtime flags, or provider integration is changed by this worker.

## Migration and retained data

Apply normal Flyway V001–V017 on PostgreSQL. V017 adds a nonnegative integer
`tasks.task_edit_revision NOT NULL DEFAULT 0`; existing task values, statuses,
constraints, owners, dependencies, timestamps and Mission intake counters are retained.
It adds only `task_edit_events` and `task_edit_receipts`. Both have composite task
scope foreign keys; receipts additionally reference the same scoped event and actor.
Events have unique task/revision and receipts have actor/org/Team/Mission/Task/key
identity. There are no production seeds, runtime tables, triggers or status changes.

Application access to the new history is insert/read only. Each successful command
updates exactly title, description, acceptance criteria, priority, updated_at and the
edit revision, then inserts an event, durable original receipt and safe
`TASK_DRAFT_UPDATED` audit in the same transaction. The audit contains only eventId
and revision, with structured Mission/Task references. Failure of any provenance
insert rolls back the task update and all other inserts.

## Protocol and concurrency

The new root is
`/api/v1/organizations/{org}/teams/{team}/missions/{mission}/tasks/{task}/draft-settings`.
GET returns exactly the 13 Context fields; POST returns exactly the 9 Receipt fields;
GET `/receipts/{key}` returns `{receipt: Receipt|null}` inside the normal API envelope.
All new endpoints require fresh enabled/current-credential same-org ADMIN authority.
POST retains existing CSRF enforcement and requires exactly one canonical UUID key.
The isolated strict parser rejects duplicates, unknown/missing fields, coercion,
non-UTF8, unsafe Unicode/control characters and oversized bodies (65536 UTF8 bytes).
Required text is Java-strip canonicalized and bounded in Unicode codepoints.

Snapshot version `TASK_DRAFT_EDIT_SNAPSHOT_V1` is a SHA256 of an ordered JSON tuple:
scoped IDs; actual four Values; task status, revision, updated_at, raw owner ID,
created_at, pending reason, waiting timestamp and resolver; sorted complete dependency
IDs; Team name/status; Mission title/status; intake paused/version. No owner or
dependency metadata is projected into Context. Request digest version
`TASK_DRAFT_EDIT_COMMAND_V1` includes scope, actor and the complete canonical command,
including expected revision/fingerprint. Fingerprints are opaque preflight markers.

Writes lock organization, account, current membership, Team, Mission, then Task;
authority is checked again after all waits. A stored matching receipt is recovered
before mutable eligibility/revision checks, without any writes. Changed same-key
commands conflict. Different keys on one expected snapshot have one winner. No-op,
stale, blocked, invalid and unauthorized operations create no history or receipt.
A missing/unavailable receipt does not prove a write failed; callers must preserve
ambiguous-outcome handling and never invent a new command/key automatically.

## Test execution and evidence

Feature regular suite: 39 actual passing cases, zero failures/errors/skips:
22 `TaskDraftEditWireTest` cases and 17 `TaskDraftEditIntegrationTest` cases.
This includes HTTP/CSRF and strict headers/media/schema, legacy nulls, persisted
mutation boundaries, owner/dependency fingerprint/preservation, historical replay,
actor isolation, current credentials/roles, no-op/stale/max revision/state blockers,
separate event/receipt/audit failure rollback, and explicit legacy/missing-receipt null
serialization even under global NON_NULL inclusion. All DTO records explicitly use
JsonInclude(ALWAYS). Flyway V001–V017 executes on H2
PostgreSQL compatibility mode in the regular integration suite.

`TaskDraftEditPostgresIT` contains 22 cases: those same 17 integration cases plus five
native cases covering same-key concurrency, different-key stale competition,
authority loss during a real organization lock wait, fingerprint/server-clock changes
after a lock wait, and V016-to-V017 retained-row/constraint migration in a disposable
schema. This suite is NOT_RUN locally; H2 results are not PostgreSQL evidence.
The explicit suite has no skip annotation and fails if required environment is absent:

- `TASK_EDIT_TEST_JDBC_URL=jdbc:postgresql://127.0.0.1:15432/agentos_test`
- `TASK_EDIT_TEST_DB_USER=agentos`
- `TASK_EDIT_TEST_DB_PASSWORD` supplied by the disposable CI database configuration; no password value is published here

Quality selector owned by main:
`-Dtest=*Test,TeamSettingsPostgresIT,TaskDraftEditPostgresIT`.
Default Surefire selection excludes `*IT`. An explicit selection cannot silently
fall back to H2. Native wait tests observe `pg_stat_activity` before releasing locks;
they do not rely on a sleep to assume the writer is waiting.

Formatting uses pinned google-java-format 1.22.0; the repository Google import check
passes. Local Java21/Maven were provisioned outside the repository. Stale generated
classes were cleared before the successful focused run; the restricted local VM
needed Byte Buddy's existing agent explicitly supplied through JAVA_TOOL_OPTIONS.
These tooling adjustments change no project files. Sonar remains paused. Native PG,
CI gates, browser/native app evidence and independent QC are not claimed by this
worker.

The broader pre-null-hardening regular run executed 377 cases: zero assertion
failures, 12 errors, zero skips. All 12 errors were AuthenticationIntegrationTest
setup cleanup: delete from human_accounts violated the pre-existing
approval_requests.requester_human_id FK after governance tests left referenced rows.
The feature uses its own H2 database; no cleanup or unrelated application/test source
was changed to mask this suite-order failure. Full verify also encountered an
uncached maven-jar-plugin 3.4.2 in offline mode; no full-verify pass is claimed.

## Source freeze

Owned deliverable is 11 files: six production Java files in
`backend/src/main/java/com/agentos/taskediting/`, three test Java files in
`backend/src/test/java/com/agentos/taskediting/`,
`backend/src/main/resources/db/migration/V017__task_draft_edit.sql`, and this note.
No commits. Main owns route/detail integration, CI selectors, native probes and fixtures.


## Coordinator completed C3 acceptance — 27 October 2026

The worker-authored local/freeze ledger above is historical. C3 candidate bf328720a139c8fbbbf8863fbf3eb5962b83c8f5 passed complete Quality verify:423tests/0failures/0errors/0skips, including all22TaskDraftEditPostgresIT and retained23TeamSettingsPostgresIT on the actual PostgreSQL service. Docker regular378tests and native PostgreSQL HTTP migration/persistence/race/rollback/lock-wait authority gates passed. New45+baseline633 browser instances and session restart/logout passed. Source app/migration bytes are unchanged from the initial candidate; migration-retention testing now uses dedicated nonpooled sessions, preserving all retained-row/constraint assertions. Main also isolated the existing AuthenticationIntegrationTest H2 fixture, without altering application security/constraints/assertions. See docs/qc/TASK_DRAFT_EDIT_QC_REPORT.md for exact run/tree/merge/visual evidence. Local offline package limitations are not a failed completed CI verify.
