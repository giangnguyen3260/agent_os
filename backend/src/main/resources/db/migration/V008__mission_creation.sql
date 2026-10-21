alter table missions add column required_output text;
alter table missions add column deadline date;
alter table missions add column currency varchar(3) check (currency = 'USD');
alter table missions add column approval_mode varchar(30) check (approval_mode = 'HUMAN_REQUIRED');
alter table missions add column context text;
create table mission_create_receipts (
 organization_id uuid not null references organizations(id),
 account_id uuid not null references human_accounts(id),
 idempotency_key uuid not null,
 body_sha256 varchar(64) not null,
 original_response text not null,
 primary key (organization_id, account_id, idempotency_key)
);
alter table missions add constraint ck_mission_required_output check (required_output is null or (char_length(required_output) between 1 and 4000));
alter table missions add constraint ck_mission_context check (context is null or char_length(context) <= 8000);
alter table missions add constraint ck_mission_new_budget check (required_output is null or budget_limit is null or (budget_limit between 0 and 1000000 and budget_limit = round(budget_limit, 2)));
