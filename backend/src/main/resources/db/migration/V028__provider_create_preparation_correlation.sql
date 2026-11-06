-- Bind a journaled Create attempt to its receipt namespace before writing to Vault.
-- Replays can race, so several preparations may share the same namespace.
ALTER TABLE provider_secret_preparations ADD COLUMN create_actor_id UUID REFERENCES human_accounts(id);
ALTER TABLE provider_secret_preparations ADD COLUMN create_idempotency_key UUID;
ALTER TABLE provider_secret_preparations ADD CONSTRAINT provider_preparation_create_scope CHECK (
    (create_actor_id IS NULL AND create_idempotency_key IS NULL)
    OR (create_actor_id IS NOT NULL AND create_idempotency_key IS NOT NULL AND credential_revision = 1)
);
CREATE INDEX provider_preparation_create_namespace_idx
    ON provider_secret_preparations (organization_id, create_actor_id, create_idempotency_key);
