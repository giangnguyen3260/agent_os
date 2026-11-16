alter table execution_model_authorizations
    add column handed_off_at timestamptz;

alter table execution_model_authorizations add constraint ck_model_handoff_after_authorization
    check (handed_off_at is null or (control_revision is not null and handed_off_at >= authorized_at));
