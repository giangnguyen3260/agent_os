-- Internal preparation evidence only. No credential or reversible envelope columns.
-- No connection FK: CREATE preparation necessarily precedes the connection row.
CREATE TABLE provider_secret_preparations (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL REFERENCES organizations(id),
    connection_id UUID NOT NULL,
    provider VARCHAR(32) NOT NULL CHECK (provider IN ('OPENAI','ANTHROPIC','GOOGLE_GEMINI')),
    credential_revision BIGINT NOT NULL CHECK (credential_revision > 0),
    external_locator VARCHAR(36) NOT NULL UNIQUE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('PENDING','WRITTEN','UNKNOWN')),
    external_version VARCHAR(16),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CHECK ((state = 'WRITTEN' AND external_version = '1')
        OR (state IN ('PENDING','UNKNOWN') AND external_version IS NULL)),
    CHECK (updated_at >= created_at)
);
CREATE INDEX provider_secret_preparations_scope_idx
    ON provider_secret_preparations (organization_id, connection_id, credential_revision);
