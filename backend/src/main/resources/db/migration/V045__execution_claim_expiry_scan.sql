create index idx_execution_claims_expiry_scan
    on execution_claims (invalidated_at, expires_at, run_id, fence);
