CREATE INDEX idx_mission_intake_history
    ON mission_intake_receipts
    (organization_id, team_id, mission_id, created_at DESC, event_id DESC);
