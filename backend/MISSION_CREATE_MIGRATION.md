# V008 Mission creation rollout

V008 adds nullable required_output, deadline, currency, approval_mode and context to existing missions, plus durable mission_create_receipts. Existing rows are preserved; no business examples, manager assignment, planning or task creation is seeded. Newly created Missions use CREATED. Configuration values do not imply runtime enforcement.

Back up the database and verify restore before deployment. Apply through normal Flyway startup after V007; do not edit installed migrations or delete volumes. Check flyway_schema_history version008 success, then create and reload a Mission through an authorized Team using the new scoped API. Confirm required output/context and created_by_user_id in PostgreSQL, retries return the original Mission, and no tasks/planning were produced. Disposable CI fixtures are separate from migrations.

Receipts contain the original Mission response and private user input. Protect database backups and DB access to the same standard as Mission data. The SHA256 receipt digest is for request matching, not confidentiality. This slice does not introduce field encryption/KMS; sensitive free text must not contain secrets, and production data-protection requirements remain open. Password hashing and request digests serve different purposes.

The old organization-level Mission POST is disabled to close its unvalidated creation path. Clients must use /api/v1/organizations/{org}/teams/{team}/missions with current ADMIN authorization, CSRF and Idempotency-Key. Planning and execution remain fail-closed.

Rollback the application only with a compatible API client; prefer a forward fix. Do not drop the receipt table or new fields after accepting writes, because that loses saved data and retry identity. Migration execution and native PostgreSQL proof are recorded in docs/qc/MISSION_CREATE_QC_REPORT.md once verified.
