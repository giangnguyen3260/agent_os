# V007 Agent Studio rollout

Apply after V006 through Flyway. Back up the database and schedule a schema-change window: adding/backfilling revisions and validating constraints can lock existing tables. Development CI on a fresh PostgreSQL database is not proof of a production rollout window or throughput target.

`agents.configuration_revision` starts at0 for legacy agents without configuration and is backfilled to1 only where `agent_draft_config` already exists. New Agent Drafts explicitly start at1. Migrated configuration remains unchanged; no creator or historical revision snapshot is fabricated for legacy data. New creation and edits record immutable snapshots with the authenticated account and timestamp. Snapshots are append-only through application code, not tamper-proof against a privileged database operator.

Nullable structured concurrency, max tokens per run and monthly USD budget preserve earlier Drafts. Existing requested free-text limits remain intact and are not silently parsed into structured settings. API validation rejects fractional integers, coerced numeric strings and excess budget precision before database storage can round values. The new numeric settings are configuration, not runtime enforcement.

Version snapshots and idempotency receipts are written in the same transaction as current configuration and revision increment. Keep original-response receipts durable; pruning or incompatible response format changes require an explicit retry-compatibility policy. A retry can intentionally return an older saved response after a later edit; reading Studio again fetches the current revision.

The existing create/detail contracts remain compatible. The Studio GET/PUT endpoint is additive. Only current organization ADMIN can edit DRAFTs; active/paused/archived Agents remain read-only. Identity, team, status, level and autonomy are not editable in this flow. No activation, grants, provider credentials, execution or schema-seeded business data is introduced.
