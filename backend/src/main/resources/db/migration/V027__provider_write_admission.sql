CREATE TABLE provider_write_admission (
    organization_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    actor_id UUID NOT NULL REFERENCES human_accounts(id) ON DELETE CASCADE,
    recent_requests VARCHAR(4096) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, actor_id)
);
