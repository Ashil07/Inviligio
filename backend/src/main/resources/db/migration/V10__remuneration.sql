-- M17: remuneration. A rate per role applies from its effective date until the next rate of the same
-- role takes over; pay is computed from completed duties of published charts (no stored amounts).

create table remuneration_rate (
    id              uuid          primary key default gen_random_uuid(),
    college_id      uuid          not null references college (id),
    role            text          not null check (role in ('CHIEF', 'INVIGILATOR')),
    amount          numeric(10,2) not null check (amount >= 0),
    currency        text          not null default 'INR' check (currency ~ '^[A-Z]{3}$'),
    effective_from  date          not null,
    created_by      uuid          references app_user (id),
    created_at      timestamptz   not null default now(),
    updated_at      timestamptz   not null default now(),
    version         bigint        not null default 0,
    constraint remuneration_rate_tenant_uk unique (college_id, id),
    constraint remuneration_rate_one_per_day unique (college_id, role, effective_from)
);
create index remuneration_rate_role_idx on remuneration_rate (college_id, role, effective_from);
