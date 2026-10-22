# V009 Task intake rollout

This additive migration follows V008. Existing tasks retain status and get null description/acceptance/priority/owner; existing Missions get intake version0 and unpaused intake. No production sample task, owner, dependency or membership is inserted.

Adds bounded task configuration, scoped immutable dependency edges, durable task-create receipts and Mission intake revision/pause flag. Task status remains the existing nine-state contract; DRAFT is the existing INTAKE lane. This migration does not add execution, plan versions or task editing. Owner identity/name are projected only when still in the exact organization/Team. Dependency foreign keys require matching organization/Team/Mission at both endpoints.

Deploy backend and Angular together because the exact Task Detail DTO gains nullable fields and dependency IDs. Run Flyway once through the normal application startup; never manually mark the migration applied. Before deployment, take a database backup and validate migrations against a staging copy with existing V008 data. Verify legacy detail reads/null behavior, all receipt/dependency keys, fixed candidate paging and current schema history success.

Rollback is application-forward: older strict FE rejects the extended DTO, so reverting only Angular or only backend is unsupported. Preserve new business data and receipts. Do not drop columns/tables to roll back; use a reviewed forward migration after an accepted backup/restore procedure. Production rollout/volume testing remain outside the disposable CI gate.
