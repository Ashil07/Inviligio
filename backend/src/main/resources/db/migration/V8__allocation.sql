-- M7: allocation runs, versioned duty charts, assignments and unfilled positions.

create table allocation_run (
    id            uuid        primary key default gen_random_uuid(),
    college_id    uuid        not null references college (id),
    period_id     uuid        not null,
    policy_id     uuid,
    status        text        not null check (status in ('COMPLETED', 'FAILED')),
    input_hash    text        not null,
    positions     integer     not null default 0,
    filled        integer     not null default 0,
    stats         jsonb,
    error         text,
    started_at    timestamptz not null,
    finished_at   timestamptz not null,
    created_by    uuid        references app_user (id),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    version       bigint      not null default 0,
    constraint allocation_run_tenant_uk unique (college_id, id),
    constraint allocation_run_period_fk foreign key (college_id, period_id) references exam_period (college_id, id) on delete cascade,
    constraint allocation_run_policy_fk foreign key (college_id, policy_id) references college_policy (college_id, id)
);
create index allocation_run_period_idx on allocation_run (college_id, period_id, started_at desc);

-- A chart version. At most one DRAFT and one PUBLISHED per period; publishing comes in M10.
create table duty_chart (
    id                 uuid        primary key default gen_random_uuid(),
    college_id         uuid        not null references college (id),
    period_id          uuid        not null,
    chart_version      integer     not null check (chart_version > 0),
    status             text        not null check (status in ('DRAFT', 'PUBLISHED', 'SUPERSEDED')),
    policy_id          uuid,
    allocation_run_id  uuid,
    input_hash         text        not null,
    published_by       uuid        references app_user (id),
    published_at       timestamptz,
    created_by         uuid        references app_user (id),
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    version            bigint      not null default 0,
    constraint duty_chart_version_uk unique (period_id, chart_version),
    constraint duty_chart_tenant_uk unique (college_id, id),
    constraint duty_chart_period_fk foreign key (college_id, period_id) references exam_period (college_id, id) on delete cascade,
    constraint duty_chart_policy_fk foreign key (college_id, policy_id) references college_policy (college_id, id),
    constraint duty_chart_run_fk foreign key (college_id, allocation_run_id) references allocation_run (college_id, id)
);
create unique index duty_chart_one_draft_uk on duty_chart (period_id) where status = 'DRAFT';
create unique index duty_chart_one_published_uk on duty_chart (period_id) where status = 'PUBLISHED';

create table duty_assignment (
    id               uuid        primary key default gen_random_uuid(),
    college_id       uuid        not null references college (id),
    chart_id         uuid        not null,
    session_hall_id  uuid        not null,
    faculty_id       uuid        not null,
    role             text        not null check (role in ('CHIEF', 'INVIGILATOR')),
    source           text        not null default 'AUTO' check (source in ('AUTO', 'MANUAL', 'SWAP', 'SUBSTITUTE')),
    status           text        not null default 'ASSIGNED' check (status in ('ASSIGNED', 'COMPLETED', 'ABSENT', 'CANCELLED')),
    explanation      jsonb,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          bigint      not null default 0,
    constraint duty_assignment_uk unique (chart_id, session_hall_id, faculty_id),
    constraint duty_assignment_chart_fk foreign key (college_id, chart_id) references duty_chart (college_id, id) on delete cascade,
    constraint duty_assignment_session_hall_fk foreign key (college_id, session_hall_id) references session_hall (college_id, id) on delete cascade,
    constraint duty_assignment_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id)
);
create index duty_assignment_chart_idx on duty_assignment (chart_id);
create index duty_assignment_faculty_idx on duty_assignment (college_id, faculty_id);

create table unfilled_position (
    id               uuid        primary key default gen_random_uuid(),
    college_id       uuid        not null references college (id),
    chart_id         uuid        not null,
    session_hall_id  uuid        not null,
    role             text        not null check (role in ('CHIEF', 'INVIGILATOR')),
    missing          integer     not null check (missing > 0),
    reason           jsonb       not null,
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          bigint      not null default 0,
    constraint unfilled_chart_fk foreign key (college_id, chart_id) references duty_chart (college_id, id) on delete cascade,
    constraint unfilled_session_hall_fk foreign key (college_id, session_hall_id) references session_hall (college_id, id) on delete cascade
);
create index unfilled_position_chart_idx on unfilled_position (chart_id);
