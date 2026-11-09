-- M6: faculty leave and unavailability. Approved requests are hard constraints for allocation.

create table availability_request (
    id            uuid        primary key default gen_random_uuid(),
    college_id    uuid        not null references college (id),
    faculty_id    uuid        not null,
    from_date     date        not null,
    to_date       date        not null,
    -- Set for a single-session request (then from_date = to_date = the session's date).
    session_id    uuid,
    type          text        not null check (type in ('LEAVE', 'UNAVAILABLE')),
    reason        text,
    status        text        not null check (status in ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by  uuid        references app_user (id),
    reviewed_by   uuid        references app_user (id),
    reviewed_at   timestamptz,
    review_note   text,
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    version       bigint      not null default 0,
    constraint availability_dates check (to_date >= from_date and to_date - from_date <= 92),
    constraint availability_session_single_day check (session_id is null or from_date = to_date),
    constraint availability_faculty_fk foreign key (college_id, faculty_id) references faculty (college_id, id) on delete cascade,
    constraint availability_session_fk foreign key (college_id, session_id) references exam_session (college_id, id) on delete cascade
);
create index availability_faculty_idx on availability_request (college_id, faculty_id);
create index availability_status_idx on availability_request (college_id, status);
create index availability_dates_idx on availability_request (college_id, from_date, to_date);
