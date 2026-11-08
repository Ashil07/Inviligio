-- M4: faculty records, subjects they teach, per-faculty restrictions, login invitations.

create table faculty (
    id               uuid          primary key default gen_random_uuid(),
    college_id       uuid          not null references college (id),
    department_id    uuid          not null,
    employee_code    text          not null check (employee_code ~ '^[A-Z0-9][A-Z0-9_.-]{0,31}$'),
    full_name        text          not null,
    email            text          check (email is null or email = lower(email)),
    phone            text,
    designation      text,
    employment_type  text          not null default 'REGULAR'
                                   check (employment_type in ('REGULAR', 'VISITING', 'GUEST', 'NON_TEACHING')),
    joined_on        date,
    chief_eligible   boolean       not null default false,
    duty_weight      numeric(3, 2) not null default 1.00 check (duty_weight between 0.10 and 2.00),
    active           boolean       not null default true,
    created_at       timestamptz   not null default now(),
    updated_at       timestamptz   not null default now(),
    version          bigint        not null default 0,
    constraint faculty_code_uk unique (college_id, employee_code),
    constraint faculty_tenant_uk unique (college_id, id),
    constraint faculty_department_fk foreign key (college_id, department_id) references department (college_id, id)
);
create unique index faculty_email_uk on faculty (college_id, email) where email is not null;
create index faculty_department_idx on faculty (college_id, department_id);

-- Subjects a faculty member teaches (drives the own-subject rule).
create table faculty_subject (
    id          uuid        primary key default gen_random_uuid(),
    college_id  uuid        not null references college (id),
    faculty_id  uuid        not null,
    subject_id  uuid        not null,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    version     bigint      not null default 0,
    constraint faculty_subject_uk unique (faculty_id, subject_id),
    constraint faculty_subject_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id) on delete cascade,
    constraint faculty_subject_subject_fk foreign key (college_id, subject_id) references subject (college_id, id)
);
create index faculty_subject_subject_idx on faculty_subject (college_id, subject_id);

-- Hard, per-person rules the allocation engine must respect.
create table faculty_restriction (
    id             uuid        primary key default gen_random_uuid(),
    college_id     uuid        not null references college (id),
    faculty_id     uuid        not null,
    type           text        not null check (type in
                       ('EXCLUDE_HALL', 'EXCLUDE_DEPARTMENT_EXAMS', 'NOT_CHIEF', 'MAX_DUTIES_PER_DAY', 'MAX_DUTIES_PER_PERIOD')),
    hall_id        uuid,
    department_id  uuid,
    max_value      integer     check (max_value between 0 and 200),
    reason         text,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    version        bigint      not null default 0,
    constraint faculty_restriction_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id) on delete cascade,
    constraint faculty_restriction_hall_fk foreign key (college_id, hall_id) references hall (college_id, id),
    constraint faculty_restriction_department_fk foreign key (college_id, department_id) references department (college_id, id),
    constraint faculty_restriction_shape check (
        (type = 'EXCLUDE_HALL' and hall_id is not null and department_id is null and max_value is null) or
        (type = 'EXCLUDE_DEPARTMENT_EXAMS' and department_id is not null and hall_id is null and max_value is null) or
        (type = 'NOT_CHIEF' and hall_id is null and department_id is null and max_value is null) or
        (type in ('MAX_DUTIES_PER_DAY', 'MAX_DUTIES_PER_PERIOD') and max_value is not null and hall_id is null and department_id is null))
);
create index faculty_restriction_faculty_idx on faculty_restriction (college_id, faculty_id);

-- A login is linked to at most one faculty record per college.
alter table college_membership
    add column faculty_id uuid,
    add constraint college_membership_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id);
create unique index college_membership_faculty_uk on college_membership (college_id, faculty_id) where faculty_id is not null;

-- One-time invitation for a faculty member to create (or link) a login. Only the token hash is stored.
create table invitation (
    id           uuid        primary key default gen_random_uuid(),
    college_id   uuid        not null references college (id),
    faculty_id   uuid        not null,
    email        text        not null check (email = lower(email)),
    roles        jsonb       not null,
    token_hash   text        not null,
    expires_at   timestamptz not null,
    accepted_at  timestamptz,
    revoked_at   timestamptz,
    created_by   uuid        references app_user (id),
    created_at   timestamptz not null default now(),
    updated_at   timestamptz not null default now(),
    version      bigint      not null default 0,
    constraint invitation_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id) on delete cascade
);
create unique index invitation_token_uk on invitation (token_hash);
create index invitation_faculty_idx on invitation (college_id, faculty_id);
