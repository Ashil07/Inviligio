# Invigilo — Architecture and Technical Decisions

This is the reference for *how* Invigilo is built and *why*. Product requirements live in the
original brief; the allocation engine has its own document, [ALLOCATION_ENGINE.md](ALLOCATION_ENGINE.md).
When a decision here changes, update this file in the same PR.

---

## 1. Shape of the system

**One Spring Boot application (modular monolith) + one React SPA + one PostgreSQL database (Supabase).**

```
Browser (React SPA)  ──HTTPS/JSON──▶  Spring Boot API  ──JDBC/TLS──▶  Supabase PostgreSQL
  Vite, TS, Tailwind                   Security (JWT)                   schema "invigilo"
  TanStack Query + Axios               Feature modules                  Flyway-managed
                                       Allocation engine (pure Java)
```

Why a monolith: one team of two, one deployable, transactional consistency across features that
are tightly coupled (allocation ↔ availability ↔ swaps ↔ audit). Microservices would add network
failure modes and distributed transactions for no benefit at college scale (hundreds of faculty,
thousands of duties per period).

### Repository layout

```
backend/                 Spring Boot (Maven wrapper, Java 21)
  src/main/java/com/invigilo/
    InvigiloApplication.java
    config/              cross-cutting configuration (security, properties)
    common/              shared building blocks (errors, base entities, tenant context, audit)
    system/              status endpoint
    <feature>/           one package per feature (see below)
  src/main/resources/db/migration/   Flyway SQL migrations
frontend/                React + Vite + TypeScript
  src/app/               router, providers, layouts
  src/lib/               api client, utilities
  src/components/        shared UI components
  src/features/<feature>/  pages, components, api hooks, schemas, tests per feature
docs/                    this file, allocation engine, development plan, handover
docker-compose.yml       local PostgreSQL
```

### Backend package-by-feature

Each feature package owns its entity, repository, service, controller and DTOs:

```
auth  tenant  college  policy  department  faculty  subject  hall  exam
availability  allocation  dutychart  swap  substitute  analytics  report  remuneration  audit
```

Rules:
- Controllers are thin: map DTO ⇄ service call. No business logic, no repositories.
- Services own transactions (`@Transactional` on service methods) and business rules.
- Entities never leave the service layer; controllers return DTO records.
- A feature may call another feature's **service**, never its repository.
- `allocation/engine` is **pure Java** (no Spring, no JPA): it takes an immutable problem snapshot
  and returns a result. This keeps it deterministic and unit-testable. See ALLOCATION_ENGINE.md.

No Lombok, no MapStruct: Java records cover DTOs, and explicit mapping is short and greppable.

---

## 2. Technology versions

| Area | Choice | Notes |
|---|---|---|
| Java | **21 LTS** (Temurin) | Brief allows 17+; 21 gives records/pattern matching/virtual threads and longer support. |
| Spring Boot | **3.5.16** | Last 3.x line, as mandated. See risk R1. |
| Build | Maven via wrapper (Maven 3.9.16) | No local Maven install needed: `./mvnw`. |
| JWT | Spring Security OAuth2 Resource Server (Nimbus) | Validation and issuance from the Spring stack; no extra JWT library. |
| DB migrations | Flyway (Boot-managed 11.x) + `flyway-database-postgresql` | |
| DB | PostgreSQL 17 (Supabase; local via Docker) | |
| API docs | springdoc-openapi 2.8.17 | `/swagger-ui.html`; `/v3/api-docs` imports straight into Postman. |
| Tests (BE) | JUnit 5, Mockito, Spring Boot Test, zonky embedded-postgres 2.2.2 (PostgreSQL 17.11) | Real PostgreSQL in tests with no Docker and no hosted DB. |
| Reports (Developer 2) | OpenPDF 2.4.0, Apache POI 5.5.1, Commons CSV 1.14.1 | Versions pinned in `pom.xml` properties; add the deps when the report module is built. |
| Node | 24 | |
| Frontend | React 19, Vite 8, TypeScript 7, Tailwind CSS 4 | |
| Routing / HTTP | React Router 8, Axios 1.x | |
| Forms | React Hook Form 7 + Zod 4 (`@hookform/resolvers`) | |
| Server state | **TanStack Query 5** (added) | See §8. |
| Icons / charts | Lucide React, Recharts 3 | |
| Tests (FE) | Vitest 5, React Testing Library, jsdom | |
| Lint (FE) | oxlint (Vite template default) | |

---

## 3. Multi-tenancy

**Model: shared database, shared schema, `college_id` discriminator column on every tenant-owned table.**

Alternatives rejected: schema-per-college (migrations × N, connection routing, Supabase pooler
limits) and database-per-college (cost, ops). A discriminator is the standard choice at this scale,
*provided enforcement is layered*. Invigilo uses five layers:

1. **Tenant identity comes only from the signed JWT.** At login the server resolves the user's
   `college_membership` and writes `cid` (college id) and `roles` into the token. The API never
   reads a college id from a path, query string or body for authorization. There is no
   `/colleges/{id}/...` in the URL space.
2. **Request-scoped `TenantContext`** (`tenant/TenantContext`). `TenantFilter`, placed right after
   bearer-token authentication in the security chain, puts `cid` into a thread-local and clears it
   after the request. Flows that run before a tenant exists (registration, login, refresh, logout)
   use **`SystemTransactions`**, which sets a *system* flag **before** opening the transaction
   (Hibernate reads the tenant when the session opens) and refuses to nest inside another
   transaction. Code with neither a tenant nor the system flag resolves to a `NONE` tenant that
   matches no rows and fails the FK on insert, so it fails closed.
3. **Hibernate `@TenantId` discriminator.** Tenant-owned entities extend a `TenantScopedEntity`
   mapped superclass whose `collegeId` field is annotated `@TenantId`; a
   `CurrentTenantIdentifierResolver` reads `TenantContext`. Hibernate then adds
   `college_id = :tenant` to HQL/Criteria queries and sets the column on insert automatically.
   - It does **not** apply to native SQL. Native queries must include `college_id` explicitly; this
     is a code-review rule.
   - **Verified (Hibernate 6.6.53):** `findById`, JPQL with `join fetch`, `findAll` and `deleteById`
     are all filtered; another college's row is simply not found
     (`TenantIsolationIntegrationTest`). Plain `findById` is therefore safe for tenant entities.
   - The system context is Hibernate's *root tenant* (`isRoot`): no filter, and inserts keep an
     explicitly assigned `college_id` (`TenantScopedEntity.assignCollege`, allowed only in the
     system context).
4. **Same-tenant foreign keys in the database.** Every tenant table has `unique (college_id, id)`,
   and child tables reference parents with composite FKs, e.g.
   `foreign key (college_id, department_id) references department (college_id, id)`.
   This makes "faculty of college A in a department of college B" impossible even if code is wrong.
5. **Not-found, not forbidden.** A request for another college's resource returns **404**, so ids
   cannot be probed. UUID primary keys (not sequential) avoid enumeration.

A `TenantIsolationIntegrationTest` suite (two colleges, user of A attempts read/update/delete/
reference of B's data for every resource) is part of the definition of done for each new resource.

**Supabase-specific:** Supabase auto-exposes the `public` schema through its REST Data API. All
Invigilo tables live in a dedicated **`invigilo`** schema, which is not exposed. Never add it to
Supabase's "Exposed schemas" and never create Invigilo tables in `public`. The Spring Boot API is
the only way to the data.

---

## 4. Authentication and authorization

Supabase Auth is not used (per brief). Invigilo owns identity:

- `app_user` — global login identity: email (unique, case-insensitive), BCrypt password hash,
  name, status. A person can, in principle, belong to more than one college.
- `college_membership` — `(user, college, roles, faculty_id)`. Roles: `EXAM_CELL_ADMIN`, `HOD`,
  `FACULTY` (a user may hold several within one college, e.g. HOD + FACULTY). A HOD's department
  is the department of their linked faculty record.
- `faculty` is a college's *staff record* and can exist without a login.

**Invitations (M4).** The exam cell creates a one-time link for a faculty member (`POST
/faculty/{id}/access/invitation`, roles FACULTY + optional HOD / EXAM_CELL_ADMIN). The raw token
is returned once; only its SHA-256 is stored; it expires after 7 days; a new link revokes the old
one. The invitee opens `/accept-invite?token=…` (public `POST /auth/invitations/preview` and
`/auth/invitations/accept`):
- no account for the email → they choose a name and password; a user and membership are created;
- an account exists (e.g. visiting faculty, or the admin's own faculty record) → they must enter
  that account's password; the existing user gets a membership, or their existing membership in
  this college is linked to the faculty record.

Access management (`PUT /faculty/{id}/access`): roles always include FACULTY; disabling revokes
all refresh sessions; nobody can change their own access; the last active EXAM_CELL_ADMIN cannot
be demoted or disabled. Role changes reach existing access tokens within their 15-minute life.
There is no email delivery (Q8): the admin copies the link to the faculty member.

Tokens:
- **Access token**: HS256 JWT, 15 min, claims `sub` (user id), `cid`, `roles`, `mid` (membership id),
  `iss=invigilo`. Kept **in memory** in the SPA (never localStorage).
- **Refresh token**: random 256-bit opaque value, stored **hashed** in `refresh_token` table,
  rotated on every use, revocable, delivered as `HttpOnly; Secure; SameSite=Strict` cookie scoped to
  `/api/v1/auth`. Refresh re-checks that the membership is still active, so revocation takes effect
  within one access-token lifetime.
- A user with several memberships picks a college at login; the token is per college.

Implemented in M1 (`auth/`, `identity/`, `tenant/`, `config/SecurityConfig`):

| Endpoint | Auth | Notes |
|---|---|---|
| `POST /api/v1/auth/register-college` | public | Creates college (`ONBOARDING`), user, EXAM_CELL_ADMIN membership; signs in. 409 `COLLEGE_CODE_TAKEN` / `EMAIL_TAKEN`. |
| `POST /api/v1/auth/login` | public | `{email, password, collegeId?}` → `AUTHENTICATED` or `COLLEGE_SELECTION_REQUIRED` with the user's colleges. Unknown email and wrong password give the same 401 and similar timing (dummy BCrypt). |
| `POST /api/v1/auth/refresh` | cookie | Rotates the refresh token. Reuse of a rotated token after 30 s revokes all of that membership's refresh tokens; within 30 s it is tolerated without rotating (two tabs refreshing at once). |
| `POST /api/v1/auth/logout` | cookie | Revokes the token, clears the cookie. |
| `GET /api/v1/auth/me` | bearer | Session as stored now (tenant-filtered lookup). |

Other details: emails stored lower-case, college codes upper-case; passwords 8-72 chars (BCrypt
limit); a request to a public endpoint that carries an *invalid* bearer token is rejected by Spring
Security, so the SPA never sends the token to credential endpoints. Suspended colleges and disabled
users/memberships cannot sign in or refresh.

**Deployment constraint:** the refresh cookie is `SameSite=Strict`, so the SPA and the API must be
on the **same site** (same registrable domain, e.g. `app.example.edu` + `api.example.edu`), or the
browser will not send the cookie and sessions will not survive a reload. Login rate limiting is not
implemented yet (see risks).

Authorization is two-level:
- **Role** checks with `@PreAuthorize("hasRole('EXAM_CELL_ADMIN')")` on service or controller methods.
- **Scope** checks in services: HOD queries are restricted to `membership.departmentId`;
  FACULTY queries to `membership.facultyId`. Never rely on the frontend hiding a button.

---

## 5. Data model (target schema)

Developer 1 creates these through Flyway migrations as features land. Columns listed are the
important ones, not exhaustive. All tenant tables: `id uuid pk`, `college_id`, `created_at`,
`updated_at`, `unique (college_id, id)`.

**Platform / identity**
- `college` — code (unique, upper-case), name, affiliation, address, city, state, postal_code,
  contact_email, contact_phone, academic_year (`2026-27`), time_zone
  (default `Asia/Kolkata`), status (`ONBOARDING`, `ACTIVE`, `SUSPENDED`), onboarding_completed_at.
- `app_user`, `college_membership`, `refresh_token`.

**Policy (versioned)**
- `college_policy` — college_id, policy_version (int), settings `jsonb`, created_by, created_at,
  `unique (college_id, policy_version)` (`version` is the optimistic-lock column on every table).
  Settings are a typed Java record (`PolicySettings`) serialized to
  JSONB and validated with Bean Validation; JSONB because policy options will grow and are always
  read as a whole. **Every edit creates a new version**; allocation runs and duty charts store the
  `policy_id` they were built with, which is how *"changing rules must not silently modify published
  charts"* is guaranteed. The full catalogue of settings is in ALLOCATION_ENGINE.md §3.
  Saving requires `basedOnVersion` (the version the editor loaded); a mismatch is 409
  `POLICY_CHANGED`, so two admins cannot overwrite each other. Saving identical settings is a no-op.
  Endpoints: `GET /policy` (any member; version 0 = unsaved defaults), `PUT /policy`,
  `GET /policy/versions`, `GET /policy/versions/{n}` (admin).

**Academic**
- `department` — code, name, active. `unique (college_id, code)`. *(M3)*
- `faculty` *(M4)* — department (composite FK), employee_code (unique per college), full_name,
  email (unique per college when set; contact address and invitation target, not the login email),
  phone, designation (free text), `employment_type` (`REGULAR`, `VISITING`, `GUEST`,
  `NON_TEACHING`), joined_on (seniority = years of service), chief_eligible, duty_weight
  (0.10-2.00, 1.00 = normal share), active.
- `faculty_subject` *(M4)* — entity with its own `college_id` and composite FKs to faculty and
  subject; drives the own-subject rule.
- `faculty_restriction` *(M4)* — `EXCLUDE_HALL` (hall), `EXCLUDE_DEPARTMENT_EXAMS` (department),
  `NOT_CHIEF`, `MAX_DUTIES_PER_DAY` / `MAX_DUTIES_PER_PERIOD` (max_value), reason. A DB CHECK
  enforces which column each type uses. Always HARD for the engine.
- `invitation` *(M4)* — faculty, email, roles (jsonb), token_hash, expires/accepted/revoked.

**CSV import (M4)** — `POST /api/v1/imports/{departments|subjects|halls|faculty}?dryRun=true`
(multipart `file`, ≤ 2 MB, ≤ 5000 rows, UTF-8 with or without BOM) and
`GET /api/v1/imports/{type}/template`. Every row is validated with the same Bean Validation rules
as the API plus code references, in-file duplicates and conflicts with existing records; errors
are reported per line and column. Without errors the file is applied in one transaction through
the normal services (upsert by code, nothing deleted, normal audit entries plus one
`IMPORT_COMPLETED`); a failure on any row rolls back the whole file.
- `subject` — department (composite FK `(college_id, department_id)`), code, name, programme,
  semester (1-12), active. *(M3)*
- `hall` — code, name, building, capacity (1-2000), min_invigilators (1-20), is_lab, active. *(M3)*

Codes (department, subject, hall) are accepted in any case, stored upper-case, unique per college,
and may be edited. Delete is allowed only while nothing references the row; otherwise 409
`*_IN_USE` and the admin deactivates instead. References are reported through the
`common/persistence/UsageCheck<T>` beans each referencing feature registers (subjects → department
today; faculty → department in M4; session halls → hall in M5), so the owning feature never depends
on the features that point at it.

**Examinations**
*(Implemented in M5, `exam/`, migration V6.)*
- `exam_period` — name (unique per college), start/end date (≤ 1 year), status (`PLANNING`,
  `ALLOCATING`, `PUBLISHED`, `CLOSED`; PLANNING/ALLOCATING are editable, later milestones set the
  others).
- `exam_session` — period, date (within the period), label (`FN`/`AN`/custom, unique per date),
  start_time < end_time. Overlap is computed from **time ranges**, not labels.
- `exam` — session, subject (once per session), exam_type (`THEORY`, `PRACTICAL`), optional
  registered_students, notes.
- `session_hall` — session, hall (once per session), optional required_override with mandatory
  reason. **This is the unit of duty**, not `(exam, hall)`.
- `hall_seating` — session_hall, exam, student_count. A hall may seat **several exams in the same
  session** (mixed seating).

Rules enforced by `ScheduleService`: seated total ≤ hall capacity (422 `HALL_OVER_CAPACITY`); an
exam's seats across halls ≤ its registered students (`EXAM_OVER_SEATED`); seats only for exams of
that session (`EXAM_NOT_IN_SESSION`); **a hall cannot be used by two overlapping sessions on the
same date, across all periods** (409 `HALL_BUSY`, also checked when a session's time is changed);
period dates cannot exclude existing sessions. Warnings (not errors) are returned with the
session: registered students not seated, exams without halls, halls without students, practical
exams outside labs. Children are removed with their session/period (`on delete cascade`); halls
and subjects used in sessions cannot be deleted.

Staffing per session hall is computed by `exam.Staffing.compute` (pure, shared with the engine):
see ALLOCATION_ENGINE.md §3. Endpoints: `/api/v1/exam-periods[/{id}[/sessions[/bulk]]]`,
`/api/v1/exam-sessions/{id}[/exams|/halls]`, `/api/v1/exams/{id}`, `/api/v1/session-halls/{id}`.
Any member can read the timetable; only EXAM_CELL_ADMIN changes it.

**Availability** *(M6, `availability/`, migration V7)*
- `availability_request` — faculty, from_date..to_date (≤ 93 days) **or** one session (then both
  dates are the session's date), type (`LEAVE`, `UNAVAILABLE`), reason, status (`PENDING`,
  `APPROVED`, `REJECTED`, `CANCELLED`), requested_by, reviewed_by/at, review_note.
- Faculty (`/api/v1/me/availability`) may ask only for what the policy allows (whole days and/or
  single sessions), from today on (college time zone). With `leaveRequiresHodApproval` the request
  is PENDING, otherwise APPROVED at once. Overlapping active requests are refused, except two
  different sessions.
- Review (`/api/v1/availability/{id}/approve|reject`): the exam cell, or a HOD for faculty of
  *their* department (the department of the HOD's own faculty record). Nobody reviews their own
  request. The exam cell records leave for anyone (`POST /api/v1/availability`), approved at once.
- Faculty cancel their own current or future requests; the exam cell cancels any.
- `GET /api/v1/exam-periods/{id}/availability`: per session, who is unavailable (approved) and
  whose requests are pending. `AvailabilityService.unavailableFacultyIds(session)` is the hard
  constraint input for allocation (approved only).
- Not built yet: `availability_override` (exam cell overriding approved unavailability for one
  session, with a reason). It belongs with manual chart editing (M9).

**Allocation and charts** *(M7, `allocation/`, migration V8; see ALLOCATION_ENGINE.md §8a)*
- `allocation_run` — period, policy_id, status, started/finished, input hash, stats `jsonb`,
  unfilled count, created_by. One row per "Generate" click.
- `duty_chart` — period, version, status (`DRAFT`, `PUBLISHED`, `SUPERSEDED`), policy_id,
  allocation_run_id, published_by/at. At most one DRAFT and one PUBLISHED per period
  (partial unique indexes).
- `duty_assignment` — chart, session_hall, faculty, role (`CHIEF`, `INVIGILATOR`),
  source (`AUTO`, `MANUAL`, `SWAP`, `SUBSTITUTE`), explanation `jsonb`, status
  (`ASSIGNED`, `COMPLETED`, `ABSENT`, `CANCELLED`).
  DB guard: `unique (chart_id, session_hall_id, faculty_id)`; the cross-session double-booking rule
  is enforced by the shared validator (time overlap cannot be a simple unique index).
- `unfilled_position` — chart, session_hall, role, reason `jsonb` (from the engine).

Manual editing *(M9, `allocation/ChartEditService`, no migration)*: under
`/api/v1/exam-periods/{id}/duty-chart`, `POST /assignments/validate` (blocking issues, warnings,
ranked alternatives; nothing saved), `POST /assignments` (fill an open position), `PUT
/assignments/{aid}` (give a duty to someone else; body carries the duty's `version`), `DELETE
/assignments/{aid}?version=&reason=` (position becomes open with fresh reasons), `GET /history`.
Exam cell only; only the DRAFT of a PLANNING/ALLOCATING period. Each change locks the draft row
(`SELECT … FOR UPDATE`) and is re-checked inside that transaction, so two admins cannot together
create a violation. Adding is only allowed into an open position (so `required = duties + missing`
stays true); manual duties get `source = MANUAL` and no explanation. The chart view also carries
`issues` (duties that break a hard rule now, e.g. leave approved after generation, or inactive
faculty) and live `stats`.

Publishing *(M10, `allocation/ChartPublishService`, no migration)*: `POST /duty-chart/publish`
`{version}` publishes the draft exactly as reviewed (the draft's `version` is bumped by every edit
via `PESSIMISTIC_FORCE_INCREMENT`, so a stale review gets `STALE_UPDATE`); refused with
`CHART_HAS_ERRORS` while `issues` is not empty; open positions are allowed (the UI warns). The
previous PUBLISHED chart becomes SUPERSEDED and the period becomes PUBLISHED, which freezes the
timetable (`ExamPeriod.isEditable()` is false) so cascades can never delete published duties.
Charts stay changeable until the period is CLOSED (`allowsChartChanges()`): `POST /duty-chart/draft`
copies the published chart into a new DRAFT (or generate one); publishing it supersedes the old
one. `GET /duty-chart/versions[/{n}]` lists and shows every version. Audit actions
`DUTY_CHART_PUBLISHED` and `DUTY_CHART_DRAFTED` (on `duty_chart`) appear in the history.

Faculty view *(M11, `allocation/MyDutiesService`)*: `GET /api/v1/me/duties` returns the caller's
duties from PUBLISHED charts only (never drafts or superseded versions; cancelled duties excluded),
split into `upcoming` (until the session ends) and `past` by the college's time zone, with the
colleagues in the same hall. `GET /api/v1/me/duties.ics` returns them as an RFC 5545 calendar
(UTC times, escaped and folded lines). The faculty member comes from the caller's membership
(`NOT_A_FACULTY_MEMBER` 403 when the login is not linked), so nobody can ask for someone else's.

HOD view *(M12, `allocation/DepartmentDashboardService`)*: `GET /api/v1/department/overview
[?periodId=]` (role HOD) shows the head's own department (the department of their linked faculty
record, else `NO_DEPARTMENT_SCOPE` 403) in one PUBLISHED chart: the periods that have one (the
latest by start date by default), every department member with duties, chief duties, even share and
`load` (UNDER/BALANCED/OVER, null when inactive), and the department's duties. Workload uses the
engine's own rule `ChartChecker.load(duties, collegeAverage, dutyWeight)`, the same one behind the
editor's workload warning; the average is the chart's non-cancelled duties over the college's active
faculty. A period with no published chart (or another college's) is `PUBLISHED_CHART_NOT_FOUND` 404.
Pending leave comes from the existing `GET /api/v1/availability?status=PENDING` (already HOD-scoped).

Lifecycle: a DRAFT is freely editable (every edit validated). Publishing freezes it as a version.
After publishing, assignments change only through controlled workflows (swap, substitute, admin
reassignment) — each runs the same validator and writes `audit_log`. A larger rework goes through a
new DRAFT version that supersedes the published one on publish.

**Workflows (Developer 2)**
- `swap_request` — requester assignment, target faculty (+ their assignment for a two-way swap),
  status machine (`PENDING_RECIPIENT` → `PENDING_HOD` → `APPROVED`/`REJECTED`/`CANCELLED`/`INVALIDATED`).
- `remuneration_rate` — role, exam_type/session type, amount, currency, effective_from/to.

**Audit**
- `audit_log` — append-only: college_id, actor_user_id, action, entity_type, entity_id,
  before `jsonb`, after `jsonb`, reason, occurred_at. Written in the same transaction as the change.
  Implemented (M2): `audit.AuditService.record(...)` is `Propagation.MANDATORY`, so it can only be
  called inside the changing transaction; pass DTOs/records as before/after, never entities.
  Current actions: `COLLEGE_PROFILE_UPDATED`, `COLLEGE_ONBOARDING_COMPLETED`, `POLICY_VERSION_CREATED`,
  `DUTY_CHART_GENERATED` (on `exam_period`), and from M9 `DUTY_ASSIGNED`, `DUTY_REPLACED`, `DUTY_REMOVED`
  (on `duty_chart`, with a `DutySnapshot` before/after); the latter are the chart's edit history.

**College endpoints (M2):** `GET /api/v1/college` (any member), `PUT /api/v1/college` and
`POST /api/v1/college/complete-onboarding` (admin). Completion requires affiliation, address,
city, state, academic year and a saved policy (422 `ONBOARDING_INCOMPLETE` lists what is missing);
it is idempotent once ACTIVE. `College` is not tenant-filtered, so services load it with
`TenantContext.requireCollegeId()` only.

---

## 6. API conventions

- Base path `/api/v1`. JSON. Resource-oriented REST; workflow transitions are explicit action
  endpoints (`POST /duty-charts/{id}/publish`, `POST /swap-requests/{id}/accept`).
- IDs are UUIDs. Dates `YYYY-MM-DD`, times `HH:mm`, instants ISO-8601 UTC.
- **No `collegeId` anywhere in requests.** Tenant is the token's `cid`.
- Lists: `?page=0&size=50&sort=name,asc` → Spring `Page` mapped to `{ items, page, size, total }`.
- Errors: RFC 9457 `application/problem+json` with `code` (stable, e.g. `FACULTY_NOT_FOUND`) and
  `fieldErrors` for validation (already implemented in `common/error`).
  400 validation · 401 unauthenticated · 403 wrong role · 404 missing *or other tenant* ·
  409 state conflict · 422 business-rule violation.
- Optimistic locking: list/detail views include `version`; `PUT` requests must send it back
  (`common/persistence/Versions.requireUnchanged`): missing → 422 `VERSION_REQUIRED`, changed →
  409 `STALE_UPDATE`. JPA `@Version` additionally guards races inside a request. Policy uses its own
  `basedOnVersion` (each save is a new row).
- Malformed path ids (`/departments/not-a-uuid`) and out-of-range query params are 400.
- Academic endpoints (M3): `/api/v1/departments`, `/api/v1/halls` (full lists, `?includeInactive=`),
  `/api/v1/subjects` (paged: `q` on code/name with literal `%`/`_`, `departmentId`,
  `includeInactive`, `page`, `size` ≤ 200). Reads: any member. Writes: EXAM_CELL_ADMIN. Every
  create/update/delete is audited.

## 7. Validation strategy

Three layers, each with a different purpose:
1. **Frontend (Zod)** — instant feedback. Never trusted.
2. **Backend DTOs (Jakarta Bean Validation)** — shape and ranges. Business rules in **services**.
   Allocation/assignment rules in the **shared constraint validator** (ALLOCATION_ENGINE.md §5),
   used by generation, manual edit, swap and substitute alike, so the rules cannot drift.
3. **Database constraints** — not-null, checks, uniques, composite tenant FKs. Last line of defence.

## 8. Frontend architecture

- `features/<name>/` holds pages, components, `api.ts` (Axios calls + TanStack Query hooks) and Zod schemas.
- **Server state: TanStack Query** (added beyond the mandated list). Almost every screen is
  server data: caching, refetch after mutation (`invalidateQueries`), loading/error states and
  request de-duplication would otherwise be re-implemented per page. Axios remains the HTTP layer.
- **Client state**: React context for auth/session only; component state elsewhere. No Redux.
- Forms: React Hook Form + Zod resolver; backend `fieldErrors` mapped back with `setError`.
- Routing: role-specific layouts (`/admin`, `/hod`, `/faculty`) behind a guard that reads roles from
  the session. Guards are UX only.
- Dev: Vite proxies `/api` to `:8080` (no CORS locally). Prod: `VITE_API_BASE_URL`.

## 9. Testing strategy

| Layer | Tool | What |
|---|---|---|
| Allocation engine | JUnit 5 (pure unit) | Every hard constraint, determinism (run twice → identical), unfilled explanations, fairness bounds, scale test (≈300 faculty × 60 sessions × 25 halls < 5 s). |
| Services | JUnit 5 + Mockito | Business rules, state machines (swap, publish). |
| Web/security | `@WebMvcTest` + `spring-security-test` | 401/403 per role, validation → ProblemDetail. |
| Persistence & tenancy | `@SpringBootTest` + embedded PostgreSQL 17 (`support/IntegrationTest`) | Flyway migrations, tenant isolation, transactional swap. |
| Frontend | Vitest + RTL | Forms, conflict display in editor, role guards. |

Integration tests extend `support/IntegrationTest`: one embedded PostgreSQL 17 per test JVM
(zonky ships the binaries as a Maven artifact; no Docker, no Supabase), one cached Spring context,
a `MutableClock` to move time forward, and `TenantTestSupport` to run repository code as a given
college. Tests share the database, so create data with unique codes (`TestData`) instead of
truncating. Swapped from Testcontainers in M1 because the development machines run without Docker;
the tests still exercise real PostgreSQL, Flyway and Hibernate.

## 10. Database connectivity (Supabase)

- JDBC URL from Supabase *Connect → Session pooler* (`aws-0-<region>.pooler.supabase.com:5432`,
  user `postgres.<project-ref>`, `sslmode=require`). The *direct* connection is IPv6-only unless the
  IPv4 add-on is enabled. **Do not use the transaction pooler (port 6543)**: it breaks server-side
  prepared statements and Flyway's session-level locking.
- Hikari pool kept small (`DB_POOL_SIZE`, default 5) because Supabase limits pooler clients.
- Credentials only via env vars / `backend/.env` (git-ignored). Use separate Supabase projects (or at
  least separate databases) for dev and production.
- Local alternative: `docker compose up -d` (PostgreSQL 17) — same schema, same migrations.

## 11. Technical risks

| # | Risk | Mitigation |
|---|---|---|
| R1 | Spring Boot 3.5 is the final 3.x line; open-source support ends before 4.x does. | Mandated 3.x kept. Avoid deprecated APIs so a later move to Boot 4 is mechanical; decide with product owner before production launch. |
| R2 | Cross-tenant data leak. | Five enforcement layers (§3) + isolation test suite per resource. |
| R3 | Greedy allocation leaves positions unfilled that a global solver could fill. | Per-session optimal matching, scarcity look-ahead and a bounded repair pass (ALLOCATION_ENGINE.md); clear explanations for any remaining gaps. |
| R4 | Rules drift between generator, manual editor, swaps and substitutes. | Single shared constraint validator used by all four. |
| R5 | Concurrent edits (two admins, swap during edit). | `@Version` optimistic locking; swap re-validates inside the transaction; `SELECT … FOR UPDATE` on assignments being swapped. |
| R6 | Time zones (UTC server vs local exam calendar). | Exam dates/times stored as local `date`/`time` + college `time_zone`; instants as `timestamptz` UTC. |
| R7 | Supabase connection limits / IPv6. | Session pooler, small pool (§10). |
| R8 | Excel/PDF generation memory for large charts. | POI `SXSSFWorkbook` streaming; stream responses. |
| R9 | Data API exposure of tables. | Dedicated `invigilo` schema, not exposed (§3). |
| R10 | Brute-force login / registration spam. | Not yet mitigated. Add rate limiting (e.g. Bucket4j per IP + per email) before production. |
| R11 | Cross-site deployment breaks the SameSite=Strict refresh cookie. | Deploy SPA and API on the same site (§4). |
