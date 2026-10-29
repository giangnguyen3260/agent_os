# V020 Task owner configuration

V020 adds `tasks.task_owner_revision` as a nonnegative integer, not null with default zero. Existing owner pointers, task input, dependencies, timestamps, state and Mission intake counters are retained. It creates no Agent, task, synthetic history or baseline owner event.

`task_owner_events` records each accepted owner revision, current human actor/time, restricted raw before/after owner IDs and separate safe public before/after JSON. A scoped task FK and unique scoped revision prevent cross-task association and duplicate revisions. Raw provenance must never be returned as an unavailable public owner.

`task_owner_receipts` binds organization, Team, Mission, task, human actor and command key to a digest and exact original safe response. The full scoped actor/event FK binds the receipt to its event. Task mutation, event, receipt and safe `TASK_OWNER_UPDATED` audit are one transaction; failed inserts roll back all effects.

Deploy through normal Flyway startup after backing up persisted data and reviewing the migration in staging. Do not edit an applied migration or delete database volumes to apply it. Rollback requires a reviewed forward migration and data retention policy; dropping owner tables would erase provenance. No rollback script is supplied.

The pre-existing input-edit source fingerprint moves to `TASK_DRAFT_EDIT_SNAPSHOT_V2` and includes owner revision to reject owner ABA, even with a restored task timestamp. Input command digests and existing receipt replay remain unchanged. Input revision and Mission intake version are not consumed by owner changes.

C6 candidate `4fd55cc` passed Quality40892374253 (762 tests, zero failures/errors/skips), including the real-PostgreSQL V019→V020 retention and constraint suite. Docker40892374203 confirmed the actual mutation/rollback/race/authority probe and backend restart/session/logout checks. This is CI evidence, not a production or Mac rollout. See [contract](../docs/engineering/TASK_OWNER_CONTRACT.md) and [QC](../docs/qc/TASK_OWNER_QC_REPORT.md).
