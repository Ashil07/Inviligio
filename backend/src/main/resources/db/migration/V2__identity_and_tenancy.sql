-- M1: colleges (tenants), global login identities, per-college memberships and refresh tokens.

create table college (
    id                       uuid        primary key default gen_random_uuid(),
    code                     text        not null,
    name                     text        not null,
    time_zone                text        not null default 'Asia/Kolkata',
    status                   text        not null default 'ONBOARDING'
                                         check (status in ('ONBOARDING', 'ACTIVE', 'SUSPENDED')),
    onboarding_completed_at  timestamptz,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    version                  bigint      not null default 0,
    constraint college_code_format check (code ~ '^[A-Z0-9][A-Z0-9_-]{1,31}$')
);
-- Codes are stored upper-case (enforced above), so a plain unique index is case-insensitive in practice.
create unique index college_code_uk on college (code);

-- A person who can log in. Global (not tenant-owned): one email may hold memberships in several colleges.
create table app_user (
    id             uuid        primary key default gen_random_uuid(),
    email          text        not null,
    password_hash  text        not null,
    full_name      text        not null,
    status         text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    last_login_at  timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    version        bigint      not null default 0,
    constraint app_user_email_lower check (email = lower(email))
);
create unique index app_user_email_uk on app_user (email);

-- Tenant-owned: a user's access to one college. The JWT "cid" claim is derived from this row.
create table college_membership (
    id          uuid        primary key default gen_random_uuid(),
    college_id  uuid        not null references college (id),
    user_id     uuid        not null references app_user (id),
    status      text        not null default 'ACTIVE' check (status in ('ACTIVE', 'DISABLED')),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    version     bigint      not null default 0,
    constraint college_membership_user_uk unique (college_id, user_id),
    -- Target for same-tenant composite foreign keys from other tenant tables.
    constraint college_membership_tenant_uk unique (college_id, id)
);
create index college_membership_user_idx on college_membership (user_id);

create table college_membership_role (
    membership_id  uuid not null references college_membership (id) on delete cascade,
    role           text not null check (role in ('EXAM_CELL_ADMIN', 'HOD', 'FACULTY')),
    primary key (membership_id, role)
);

-- Opaque refresh tokens; only the SHA-256 hash is stored. Rotated on every use.
create table refresh_token (
    id             uuid        primary key default gen_random_uuid(),
    membership_id  uuid        not null references college_membership (id) on delete cascade,
    token_hash     text        not null,
    expires_at     timestamptz not null,
    revoked_at     timestamptz,
    replaced_by    uuid        references refresh_token (id),
    created_at     timestamptz not null default now()
);
create unique index refresh_token_hash_uk on refresh_token (token_hash);
create index refresh_token_membership_idx on refresh_token (membership_id);
