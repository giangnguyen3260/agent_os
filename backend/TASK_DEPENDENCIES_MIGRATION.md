# V021 task dependency configuration

Migration: `src/main/resources/db/migration/V021__task_dependency_configuration.sql`. Application and required test/CI results remain pending; no database deployment is claimed.

Adds tasks.dependency_revision integer>=0 default0, task_dependency_events and task_dependency_receipts with scoped Task/actor/event foreign keys and revision/key uniqueness. Existing tasks, owner/input revisions, outgoing edges, statuses and old receipt bytes remain intact. No sample data, initial history, edge rewrite or backfilled author. Revision0 means no dependency command recorded under this protocol, not proof of an originally empty set.

Deploy the matching application and V021 together: existing input/owner source token domains now include dependency_revision, so old fresh-form tokens should conflict and require a new review; historical identical commands still replay unchanged digest/receipt. TaskCreate joins the existing org-mutex graph-integrity protocol without changing its historical canonical command digest. Fresh creates against an unsafe existing graph fail closed; valid private multiline inputs remain supported. No broad rewrite of old title parsers or implicit corruption repair.

Preserve V009 composite Task/edge constraints. Dependency events are append-only through application APIs; current ADMIN receipt recovery is actor/scoped and never enriched from current target metadata. No new public history endpoint. Application/database production privilege and operational hardening remain separate from development acceptance.

Before an actual rollout, use the normal database backup/versioned migration process and verify existing edge/task counts and old receipts, then run the matched application's real PostgreSQL migration/concurrency/rollback suite. Roll forward with the matching application; do not drop provenance tables or reset revisions to bypass stale forms. Restoring a whole backup discards later business state and is not an automatic rollback action. No merge or deployment is authorized by this document.
