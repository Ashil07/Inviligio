-- M5: examination periods, sessions, exams, halls used per session and how students are seated.

create table exam_period (
    id          uuid        primary key default gen_random_uuid(),
    college_id  uuid        not null references college (id),
    name        text        not null,
    start_date  date        not null,
    end_date    date        not null,
    status      text        not null default 'PLANNING'
                            check (status in ('PLANNING', 'ALLOCATING', 'PUBLISHED', 'CLOSED')),
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    version     bigint      not null default 0,
    constraint exam_period_dates check (end_date >= start_date and end_date - start_date <= 366),
    constraint exam_period_name_uk unique (college_id, name),
    constraint exam_period_tenant_uk unique (college_id, id)
);

-- A sitting on one date with a time range. Overlap between sessions is computed from times, not labels.
create table exam_session (
    id            uuid        primary key default gen_random_uuid(),
    college_id    uuid        not null references college (id),
    period_id     uuid        not null,
    session_date  date        not null,
    label         text        not null,
    start_time    time        not null,
    end_time      time        not null,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    version       bigint      not null default 0,
    constraint exam_session_times check (end_time > start_time),
    constraint exam_session_label_uk unique (period_id, session_date, label),
    constraint exam_session_tenant_uk unique (college_id, id),
    constraint exam_session_period_fk foreign key (college_id, period_id)
        references exam_period (college_id, id) on delete cascade
);
create index exam_session_date_idx on exam_session (college_id, session_date);

create table exam (
    id                   uuid        primary key default gen_random_uuid(),
    college_id           uuid        not null references college (id),
    session_id           uuid        not null,
    subject_id           uuid        not null,
    exam_type            text        not null default 'THEORY' check (exam_type in ('THEORY', 'PRACTICAL')),
    registered_students  integer     check (registered_students between 0 and 10000),
    notes                text,
    created_at           timestamptz not null default now(),
    updated_at           timestamptz not null default now(),
    version              bigint      not null default 0,
    constraint exam_subject_uk unique (session_id, subject_id),
    constraint exam_tenant_uk unique (college_id, id),
    constraint exam_session_fk foreign key (college_id, session_id)
        references exam_session (college_id, id) on delete cascade,
    constraint exam_subject_fk foreign key (college_id, subject_id) references subject (college_id, id)
);
create index exam_subject_idx on exam (college_id, subject_id);

-- The unit of invigilation duty: one hall in one session.
create table session_hall (
    id                  uuid        primary key default gen_random_uuid(),
    college_id          uuid        not null references college (id),
    session_id          uuid        not null,
    hall_id             uuid        not null,
    required_override   integer     check (required_override between 0 and 20),
    override_reason     text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    version             bigint      not null default 0,
    constraint session_hall_uk unique (session_id, hall_id),
    constraint session_hall_tenant_uk unique (college_id, id),
    constraint session_hall_override_reason check (required_override is null or override_reason is not null),
    constraint session_hall_session_fk foreign key (college_id, session_id)
        references exam_session (college_id, id) on delete cascade,
    constraint session_hall_hall_fk foreign key (college_id, hall_id) references hall (college_id, id)
);
create index session_hall_hall_idx on session_hall (college_id, hall_id);

-- Students of one exam seated in one session hall. A hall may seat several exams at once.
create table hall_seating (
    id               uuid        primary key default gen_random_uuid(),
    college_id       uuid        not null references college (id),
    session_hall_id  uuid        not null,
    exam_id          uuid        not null,
    student_count    integer     not null check (student_count between 1 and 2000),
    created_at       timestamptz not null default now(),
    updated_at       timestamptz not null default now(),
    version          bigint      not null default 0,
    constraint hall_seating_uk unique (session_hall_id, exam_id),
    constraint hall_seating_session_hall_fk foreign key (college_id, session_hall_id)
        references session_hall (college_id, id) on delete cascade,
    constraint hall_seating_exam_fk foreign key (college_id, exam_id)
        references exam (college_id, id) on delete cascade
);
create index hall_seating_exam_idx on hall_seating (college_id, exam_id);
