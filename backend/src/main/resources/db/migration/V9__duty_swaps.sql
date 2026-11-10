-- M13: duty swaps. A faculty member offers a duty of a published chart to a colleague, either as a
-- handover (target_assignment_id null) or in exchange for one of the colleague's duties. The swap is
-- re-checked against the hard rules when it is applied.

-- Same-college foreign keys to duty_assignment need (college_id, id) to be unique.
alter table duty_assignment add constraint duty_assignment_tenant_uk unique (college_id, id);

create table swap_request (
    id                       uuid        primary key default gen_random_uuid(),
    college_id               uuid        not null references college (id),
    chart_id                 uuid        not null,
    requester_assignment_id  uuid        not null,
    requester_faculty_id     uuid        not null,
    target_faculty_id        uuid        not null,
    -- Null for a one-way handover.
    target_assignment_id     uuid,
    status                   text        not null check (status in
                                 ('PENDING_RECIPIENT', 'PENDING_HOD', 'APPROVED', 'REJECTED', 'CANCELLED', 'INVALIDATED')),
    reason                   text,
    requested_by             uuid        references app_user (id),
    recipient_responded_at   timestamptz,
    recipient_note           text,
    reviewed_by              uuid        references app_user (id),
    reviewed_at              timestamptz,
    review_note              text,
    invalid_reason           text,
    applied_at               timestamptz,
    created_at               timestamptz not null default now(),
    updated_at               timestamptz not null default now(),
    version                  bigint      not null default 0,
    constraint swap_distinct_faculty check (requester_faculty_id <> target_faculty_id),
    constraint swap_distinct_duties check (target_assignment_id is null or target_assignment_id <> requester_assignment_id),
    constraint swap_chart_fk foreign key (college_id, chart_id) references duty_chart (college_id, id) on delete cascade,
    constraint swap_requester_assignment_fk foreign key (college_id, requester_assignment_id)
        references duty_assignment (college_id, id) on delete cascade,
    constraint swap_target_assignment_fk foreign key (college_id, target_assignment_id)
        references duty_assignment (college_id, id) on delete cascade,
    constraint swap_requester_faculty_fk foreign key (college_id, requester_faculty_id) references faculty (college_id, id),
    constraint swap_target_faculty_fk foreign key (college_id, target_faculty_id) references faculty (college_id, id)
);
-- At most one open request per offered duty.
create unique index swap_one_open_per_duty on swap_request (requester_assignment_id)
    where status in ('PENDING_RECIPIENT', 'PENDING_HOD');
create index swap_requester_idx on swap_request (college_id, requester_faculty_id);
create index swap_target_idx on swap_request (college_id, target_faculty_id);
create index swap_status_idx on swap_request (college_id, status);
