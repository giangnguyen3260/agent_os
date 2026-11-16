alter table execution_model_authorizations add column delivery_unknown_at timestamptz;
alter table execution_model_authorizations add column reconciled_at timestamptz;
alter table execution_model_authorizations add column delivery_resolution varchar(24);

alter table execution_model_authorizations add constraint ck_model_delivery_unknown
    check (delivery_unknown_at is null or
        (handed_off_at is not null and delivery_unknown_at >= handed_off_at));

alter table execution_model_authorizations add constraint ck_model_delivery_resolution
    check (
        (delivery_resolution is null and reconciled_at is null)
        or (
            delivery_resolution is not null and reconciled_at is not null
            and reconciled_at >= authorized_at
            and (delivery_unknown_at is null or reconciled_at >= delivery_unknown_at)
            and (
                (delivery_resolution = 'NOT_HANDED_OFF' and handed_off_at is null)
                or (delivery_resolution in ('VERIFIED_USAGE','VERIFIED_NO_CHARGE')
                    and handed_off_at is not null and reconciled_at >= handed_off_at)
            )
        )
    );
