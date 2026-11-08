-- M3: academic structure. Codes are stored upper-case and unique per college.
-- Child tables reference parents through (college_id, id), so a row can never point at another
-- college's data even if application code is wrong.

create table department (
    id          uuid        primary key default gen_random_uuid(),
    college_id  uuid        not null references college (id),
    code        text        not null check (code ~ '^[A-Z0-9][A-Z0-9_.-]{0,31}$'),
    name        text        not null,
    active      boolean     not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    version     bigint      not null default 0,
    constraint department_code_uk unique (college_id, code),
    constraint department_tenant_uk unique (college_id, id)
);

create table subject (
    id             uuid        primary key default gen_random_uuid(),
    college_id     uuid        not null references college (id),
    department_id  uuid        not null,
    code           text        not null check (code ~ '^[A-Z0-9][A-Z0-9_.-]{0,31}$'),
    name           text        not null,
    programme      text,
    semester       integer     check (semester between 1 and 12),
    active         boolean     not null default true,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    version        bigint      not null default 0,
    constraint subject_code_uk unique (college_id, code),
    constraint subject_tenant_uk unique (college_id, id),
    constraint subject_department_fk foreign key (college_id, department_id) references department (college_id, id)
);
create index subject_department_idx on subject (college_id, department_id);

create table hall (
    id                uuid        primary key default gen_random_uuid(),
    college_id        uuid        not null references college (id),
    code              text        not null check (code ~ '^[A-Z0-9][A-Z0-9_.-]{0,31}$'),
    name              text        not null,
    building          text,
    capacity          integer     not null check (capacity between 1 and 2000),
    min_invigilators  integer     not null default 1 check (min_invigilators between 1 and 20),
    is_lab            boolean     not null default false,
    active            boolean     not null default true,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    version           bigint      not null default 0,
    constraint hall_code_uk unique (college_id, code),
    constraint hall_tenant_uk unique (college_id, id)
);
