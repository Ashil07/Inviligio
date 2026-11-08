-- M2: institution details, versioned examination policy, audit log.

alter table college
    add column affiliation    text,
    add column address        text,
    add column city           text,
    add column state          text,
    add column postal_code    text,
    add column contact_email  text,
    add column contact_phone  text,
    add column academic_year  text,
    add constraint college_academic_year_format check (academic_year is null or academic_year ~ '^[0-9]{4}-[0-9]{2}$');

-- One immutable row per policy change. Allocation runs and duty charts reference the row they were
-- built with, so later edits never alter what was already generated or published.
-- When a field is added to PolicySettings, add a migration that backfills it in existing rows.
create table college_policy (
    id              uuid        primary key default gen_random_uuid(),
    college_id      uuid        not null references college (id),
    policy_version  integer     not null check (policy_version > 0),
    settings        jsonb       not null,
    created_by      uuid        references app_user (id),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    version         bigint      not null default 0,
    constraint college_policy_version_uk unique (college_id, policy_version),
    constraint college_policy_tenant_uk unique (college_id, id)
);

-- Append-only record of changes. Written in the same transaction as the change it describes.
create table audit_log (
    id             uuid        primary key default gen_random_uuid(),
    college_id     uuid        not null references college (id),
    actor_user_id  uuid        references app_user (id),
    action         text        not null,
    entity_type    text        not null,
    entity_id      uuid,
    before_state   jsonb,
    after_state    jsonb,
    reason         text,
    occurred_at    timestamptz not null default now()
);
create index audit_log_entity_idx on audit_log (college_id, entity_type, entity_id, occurred_at desc);
create index audit_log_time_idx on audit_log (college_id, occurred_at desc);
