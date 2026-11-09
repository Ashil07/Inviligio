# Handover Log

Each developer fills in a section when handing over. Keep it factual: what exists, what works,
what does not, and where to look. Developer 2 should read this, run `graphify update .`, and read
`graphify-out/GRAPH_REPORT.md` before writing code.

---

## Initialization → Developer 1  (2026-11-08)

**State**
- Backend boots on Spring Boot 3.5.16 / Java 21; security chain validates HS256 JWTs (no issuance yet).
- `GET /api/v1/system/status` (public) reports API + DB status and Flyway schema version.
- Errors are RFC 9457 ProblemDetail with `code` / `fieldErrors` (`common/error`).
- Flyway V1 baseline in schema `invigilo`; no domain tables yet.
- Frontend: Vite/React/TS/Tailwind app shell, router, Axios client with token hook, TanStack
  Query, status page proving the browser → API → DB path.
- CI workflow for backend and frontend.

**Verified**
- `./mvnw verify`: 9 tests pass; 2 Testcontainers tests skipped locally (Docker daemon not running).
- `npm run lint`, `npm test` (2 tests), `npm run build` pass.

**Not yet verified** (superseded: database hosting is deferred to Developer 2; integration tests now use embedded PostgreSQL)
- App started against a real PostgreSQL / Supabase, and the integration tests. First step for
  Developer 1: start Docker (`docker compose up -d`) or configure Supabase in `backend/.env`,
  run `./mvnw verify` and `./mvnw spring-boot:run`, open http://localhost:5173 and see both checks green.

**Next:** milestone M1 in [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md).

---

## M1 Auth & tenancy: done  (2026-11-08)

**Built**
- Migration `V2__identity_and_tenancy`: `college`, `app_user`, `college_membership`
  (+ `college_membership_role`), `refresh_token`.
- `common/persistence`: `BaseEntity` (UUID, `@Version`, timestamps), `TenantScopedEntity` (`@TenantId college_id`).
- `tenant/`: `TenantContext`, `TenantFilter`, `TenantIdentifierResolver` (tenant / system root / fail-closed NONE),
  `SystemTransactions`, `CurrentUser`.
- `auth/`: register-college, login (multi-college picker), rotating refresh tokens with reuse detection,
  logout, `me`. Endpoint table in ARCHITECTURE.md §4.
- Frontend: `features/auth` (sessionStore, AuthProvider, guards, LoginPage, RegisterCollegePage),
  Axios refresh-and-retry, `AppLayout` with role areas `/admin`, `/hod`, `/faculty`; status page moved to `/status`.
- Test infrastructure: embedded PostgreSQL (`support/IntegrationTest`, `TestData`, `TenantTestSupport`).

**Verified**
- Backend `./mvnw verify`: 40 tests pass (20 auth, 9 tenant isolation, 2 app boot, 9 security/config),
  all against real PostgreSQL 17 via embedded-postgres.
- Frontend: lint clean, typecheck, 20 tests, production build.

**Not verified**
- No running instance has been exercised in a browser: by decision the database (Supabase) is
  connected later by Developer 2. The frontend is tested against mocked API responses whose shapes
  match the backend DTOs.

**For M2+:** every new tenant entity extends `TenantScopedEntity`; add a case to a tenant isolation
test; use `SystemTransactions` only for pre-tenant flows.

---

## M2 College onboarding & policy: done  (2026-11-08)

**Built**
- Migration `V3__college_profile_policy_audit`: institution columns on `college`, `college_policy`, `audit_log`.
- Backend: `policy/` (PolicySettings, CollegePolicy, PolicyService, PolicyController),
  `college/` (CollegeService, CollegeController), `audit/` (AuditEntry, AuditService).
- Frontend: onboarding wizard `/admin/setup`; `/admin/settings/college`; `/admin/settings/policy`
  with version history and read-only past versions; admin overview; exam-cell sub-navigation.
  Shared `PolicySections` (one form section per policy area), Zod mirror of PolicySettings,
  `describePolicy` plain-language summary.

**Verified**
- Backend: 55 tests pass (15 new: profile, validation, roles, versioning, stale edit, explicit
  rules, cross-college isolation, onboarding completion, audit).
- Frontend: 34 tests pass (wizard end to end with mocked API, policy page save/conflict/field
  errors/history, schemas); lint, typecheck, build clean.

**Not verified**
- Not exercised in a browser against a running backend (database deferred to Developer 2).
- The wizard keeps policy answers in memory until the final step; leaving midway loses them
  (institution details are saved at step 1).

---

## M3 Departments, subjects, halls: done  (2026-11-08)

**Built**
- Migration `V4__departments_subjects_halls` (composite FK `subject(college_id, department_id)`).
- Backend packages `department/`, `subject/`, `hall/`; shared `common/persistence` helpers
  `Codes`, `Versions` (stale-edit check), `UsageCheck<T>` (delete guards); `common/web/PageResponse`.
- Frontend: `/admin/departments`, `/admin/subjects` (search, department filter, paging),
  `/admin/halls`; shared `components/ui/layout.tsx` (PageHeader, EditPanel, Table, StatusBadge, RowActions).

**Verified**
- Backend: 71 tests (16 new, incl. cross-college reads/updates/deletes → 404, cross-college
  department reference → 404, and the DB itself rejecting a cross-college FK).
- Frontend: 41 tests; lint, typecheck, build clean.

**Not verified**
- Not exercised in a browser against a running backend (database deferred to Developer 2).
- No bulk import yet (planned with M4).

**For M4:** faculty → department must register a `UsageCheck<Department>`; `faculty_subject`
should use composite FKs to `subject (college_id, id)` and `faculty (college_id, id)`.

---

## M4 Faculty, access and CSV import: done  (2026-11-08)

**Built**
- Migration `V5__faculty_access`: `faculty`, `faculty_subject`, `faculty_restriction`,
  `invitation`, `college_membership.faculty_id`.
- Backend: `faculty/` (records, subjects taught, restrictions, delete guards for departments,
  subjects and halls), `access/` (invitations, accept flow, role/status management, last-admin and
  self-lock-out rules), `importer/` (CSV import), `auth` invitation endpoints and `SessionRevoker`,
  `common/security/Tokens`, `common/persistence/UpsertResult`, `AuditService.recordForCollege`.
  `/auth/me` and login now return `facultyId`.
- Frontend: `/admin/faculty` (search/filters/paging), `/admin/faculty/new` and `/:id` (details,
  subject picker, restrictions, sign-in access with copyable invitation link), `/admin/import`
  (template download, check, import), public `/accept-invite`.

**Verified**
- Backend: 98 tests (27 new: faculty CRUD and subject changes, uniqueness, validation, delete guards,
  restrictions, cross-college isolation; invitation create/replace/revoke/expire/accept for new and
  existing accounts, role changes, disabling revokes sessions, last admin, self-lock-out; CSV dry
  run errors, ordered import with upserts, BOM/encoding, header checks, rollback of a failing file).
- Frontend: 51 tests; lint, typecheck, build clean.
- Bug found by tests and fixed: duty weight `0.5` vs stored `0.50` counted as a change on re-import.

**Not verified**
- Not exercised in a browser against a running backend (database deferred to Developer 2).
- Invitation links are copied by the admin; there is no email sending (Q8).

---

## M5 Exam scheduling: done  (2026-11-08)

**Built**
- Migration `V6__exam_schedule`: `exam_period`, `exam_session`, `exam`, `session_hall`,
  `hall_seating` (composite tenant FKs, cascades from session/period).
- Backend `exam/`: `ExamPeriodService` (periods, sessions, bulk generation), `ScheduleService`
  (exams, halls, seating, warnings, session summaries), `Staffing` (pure staffing rule),
  delete guards for subjects and halls in use.
- Frontend: `/admin/exams` (periods), `/admin/exams/:periodId` (sessions, add/generate),
  `/admin/exams/sessions/:sessionId` (exams, halls, seating editor, overrides, warnings).

**Verified**
- Backend: 116 tests (18 new: staffing rule unit tests; session rules, bulk generation, period
  date changes, cascade delete, capacity/registration/session checks, hall clashes across
  overlapping sessions incl. moving a session, overrides, delete guards, roles, isolation).
- Frontend: 55 tests; lint, typecheck, build clean.

**Not verified**
- Not exercised in a browser against a running backend (database deferred to Developer 2).
- No CSV import for the timetable yet (sessions are entered or generated; exams and seating by hand).

---

## M6 Availability and leave: done  (2026-11-09)

**Built**
- Migration `V7__availability`: `availability_request`.
- Backend `availability/`: `AvailabilityService` (self-service within policy, HOD/exam-cell
  review with department scope, exam-cell recording, cancellation, per-session overview,
  `unavailableFacultyIds` for the engine), `AvailabilityController`.
- Frontend: `/faculty/availability`, `/hod/requests`, `/admin/availability` (requests, record
  leave, per-session overview); `AreaLayout` navigation for the HOD and faculty areas.

**Verified**
- Backend: 128 tests (12 new: auto-approval, session requests, policy limits, overlaps, past dates,
  cancellation, unlinked logins, HOD scope, own-request rule, exam-cell recording, overview,
  cross-college isolation). A flaky existing audit-order assertion was made order-independent.
- Frontend: 60 tests; lint, typecheck, build clean.

**Not verified**
- Not exercised in a browser against a running backend (database deferred to Developer 2).
- No notifications when a request is decided (in-app status only, Q8).

---

## M7 Allocation engine: done  (2026-11-09)

**Built**
- `allocation/engine/` (pure Java): `AllocationProblem`, `AllocationEngine`, `Hungarian`, `Reason`,
  `AllocationResult`, `ProblemFingerprint`.
- Migration `V8__allocation`: `allocation_run`, `duty_chart` (one DRAFT / one PUBLISHED per period),
  `duty_assignment`, `unfilled_position`.
- `AllocationService`: builds the problem from schedule, staffing, faculty, restrictions, approved
  unavailability, historical published duties and the latest policy; stores runs and charts;
  `GET/POST /api/v1/exam-periods/{id}/duty-chart[/generate]`. Faculty with duties cannot be deleted.

**Verified**
- 15 new tests (9 engine unit tests incl. determinism, repair and the 300-faculty benchmark; 6
  integration tests incl. hard rules on real data, regeneration, stale detection, explanations,
  roles and isolation). Backend total 143.

**Not done / known limits**
- Improvement (pairwise swap) pass not implemented (see ALLOCATION_ENGINE.md §8a).
- Generation is synchronous; fine at college scale (< 1 s).

---

## M8 Basic allocation review: done  (2026-11-09)

- Frontend `features/allocation/` (`AllocationPage`, `api.ts`), linked from the period page
  ("Allocate duties"). Shows draft version and policy version, warns when inputs or policy changed,
  statistics, unfilled positions (reason counts + suggestions), the chart per date/session/hall with
  an expandable explanation per duty, and the workload table.
- 3 frontend tests (generate first chart, stale warnings + confirm before replacing, refusal message);
  frontend total 63. Reason wording reworked so suggestions read as sentences.
- Not verified in a browser against a running backend (database deferred to Developer 2).

---

## Developer 1 → Developer 2  (2026-11-09)

**Completed milestones:** M0-M8 and H1 (this section). Commits on `main`, one per milestone:
foundation `42f1bfc`, M1 `c08d89f`, M2 `ddfcc59`, M3 `68cd4e5`, M4 `0f1b975`, M5 `09a7e46`,
M6 `9c98dce`, M7 `11daf7b`, M8 `c546885`, H1 `9f06ee4`.

**Test status:** backend 144 tests (`./mvnw verify`, embedded PostgreSQL 17, no Docker needed);
frontend 63 tests (`npm test`), lint and production build clean. **CI has never run**: the
repository has no GitHub remote yet (see "Before you start").

**Endpoints** (full contract: `/swagger-ui.html` and `/v3/api-docs` once the app runs):

| Area | Endpoints | Who |
|---|---|---|
| Auth | `POST /api/v1/auth/{register-college,login,refresh,logout}`, `GET /auth/me`, `POST /auth/invitations/{preview,accept}` | public / signed in |
| College & policy | `GET/PUT /api/v1/college`, `POST /college/complete-onboarding`, `GET/PUT /api/v1/policy`, `GET /policy/versions[/{n}]` | read: all; write: admin |
| Academic | `/api/v1/departments`, `/api/v1/subjects`, `/api/v1/halls` (CRUD) | read: all; write: admin |
| Faculty | `/api/v1/faculty` (CRUD, search), `/faculty/{id}/restrictions`, `/faculty/{id}/access[/invitation]` | admin |
| Import | `POST /api/v1/imports/{type}?dryRun=`, `GET /imports/{type}/template` | admin |
| Timetable | `/api/v1/exam-periods[/{id}/sessions[/bulk]]`, `/api/v1/exam-sessions/{id}[/exams,/halls]`, `/api/v1/exams/{id}`, `/api/v1/session-halls/{id}` | read: all; write: admin |
| Availability | `GET/POST /api/v1/me/availability`, `POST /availability/{id}/cancel`, `GET/POST /api/v1/availability`, `POST /availability/{id}/{approve,reject}`, `GET /exam-periods/{id}/availability` | faculty / HOD / admin |
| Allocation | `GET /api/v1/exam-periods/{id}/duty-chart`, `POST .../duty-chart/generate` | admin |

**Database migrations:** V1-V8 (latest `V8__allocation`). All tables in schema `invigilo`, every
tenant table has `college_id` and composite same-college foreign keys.

**Policy settings implemented:** all of ALLOCATION_ENGINE.md §3 are stored, validated and editable,
and used by the engine. The availability and swap settings are stored; availability rules are
enforced (M6); **swap settings are not used yet** (M13).

**Allocation engine status:** every hard rule and soft cost in ALLOCATION_ENGINE.md §8a is
implemented and tested. Per-group optimal assignment, look-ahead, repair pass, full validation,
explanations. Measured: 3000 positions / 300 faculty in ~0.5 s. The demo college (≈100
positions) fills completely. Not implemented: the pairwise improvement pass (not needed so far).

**What the DRAFT chart gives you for M9-M10:** `duty_chart` (one DRAFT per period, `chart_version`
keeps counting, `policy_id`, `input_hash`), `duty_assignment` (`role`, `source` = AUTO,
`status` = ASSIGNED, `explanation` jsonb), `unfilled_position` (`reason` jsonb).
`AllocationService.view()` already reports `inputsChanged` / `policyChanged`. Statuses PUBLISHED /
SUPERSEDED and `published_by/at` exist in the schema but nothing sets them: that is M10.
For the editor (M9), reuse the engine's rules rather than writing new checks: the cleanest path is
to expose a public "check this one assignment against the rest of the chart" method built from
`AllocationEngine.Run.reasons(...)` (today it is private to the engine run).

**Known limitations:**
- Nothing has been exercised in a browser against a running backend: the database connection was
  deferred to Developer 2. All UI is tested with mocked API responses; all API is tested for real.
- Access tokens keep their roles for up to 15 minutes after a role change or disable.
- No email or notifications (Q8); invitation links are copied by the admin.
- No login rate limiting (risk R10).
- Wizard policy answers are lost if the admin leaves the onboarding wizard midway.
- Overlap groups are conservative: with a chain of partly overlapping sessions a person gets at most
  one duty across the whole chain.
- `availability_override` (exam cell overriding approved leave for one session) is not built (M9).

**Deviations from ARCHITECTURE.md:** integration tests use embedded PostgreSQL instead of
Testcontainers (no Docker on the development machine; still real PostgreSQL). College
registration moved from M2 into M1. Policy versions use `policy_version` (not `version`, which is
the optimistic-lock column everywhere). Overrides of unavailability moved to M9.

**Seed / demo data:** `demo/DemoData` creates college `DEMO` through the normal services: 5
departments, 20 subjects, 12 halls (2 labs), 50 faculty (visiting, reduced weight and two
restrictions included), a saved default policy, a one-week exam period starting two weeks from
the load date with 10 sessions, seated exams (mixed seating, one practical) and 5 leave records.
Load it with the `demo` profile and the two `DEMO_*` variables from `backend/.env.example`
(the exam-cell login is the email/password you put there):

```bash
cd backend
SPRING_PROFILES_ACTIVE=demo ./mvnw spring-boot:run
```

It is idempotent (does nothing if `DEMO` exists) and covered by `DemoDataIntegrationTest`, which
also generates the chart and checks every position is filled.

**Product questions:** Q1-Q10 in DEVELOPMENT_PLAN.md §5 are still open; the working defaults are
implemented (Q4: both seniority options exist; Q8: in-app only).

**Run it without any database setup:** `LocalDevApplication` (test sources) starts the
backend with an embedded PostgreSQL in `backend/.dev-db`; see README "Run locally". Use it to
click through the app before Supabase is connected. Note: in Windows, fixed ports can fall in
Hyper-V reserved ranges, so it picks a free port for the database automatically.

**Before you start (Developer 2):**
1. Create the GitHub repository, add it as `origin`, push `main`, and check that CI goes green
   (it has never run).
2. Connect Supabase (ARCHITECTURE.md §10: session pooler, `backend/.env`), start the backend and
   frontend, load the demo data, and click through every admin, HOD and faculty page once. Expect
   small UI issues: this is the first time the UI meets a real API.
3. `graphify hook install`, `graphify update .`, read `graphify-out/GRAPH_REPORT.md`.

**Suggested first task for Developer 2:** M9, starting with the shared single-assignment
validator described above, then the editor UI on top of `AllocationPage`.

---

## Developer 2 — first day and M9 Duty chart editor  (2026-11-09)

**First day**
- Cloned, installed graphify and built the graph (`graphify update .`). The git hook is not
  installed yet (`graphify hook install`, run it yourself once per clone).
- Ran the app locally with `LocalDevApplication` + demo data and clicked through every admin, HOD
  and faculty page against the real API: all load. One bug found and fixed on branch
  `fix/availability-sessions-query`: the leave form requested `/exam-periods//sessions` before a
  period was chosen (400).
- CI on GitHub still not checked. Supabase still not connected (M18).

**M9 built** (branch `feat/allocation-chart-editor`, no migration)
- Engine: `engine/ChartChecker` reuses the engine's `Run` for single-change checks, ranked
  alternatives, whole-chart violations and open-position explanations (ALLOCATION_ENGINE.md §5).
  `Reason.singular()` gives one-person wording. The generator's final validation now uses the same
  `violations()` method.
- Backend: `ChartEditService` + endpoints on `AllocationController` (ARCHITECTURE.md "Manual
  editing"): validate, add to an open position, replace, remove, history. Draft row locked per edit,
  duty `version` checked, every change audited. `ChartView` gained `issues`, live `stats`,
  `DutyView.version`, `WorkloadRow.active`. `PolicyService.find(id)`.
- Frontend: `features/allocation/ChartEditor.tsx` (replace / fill / remove panels with suggestions,
  live rule check, reason) and on `AllocationPage`: Replace/Remove/Fill buttons, "edited" badge,
  issues banner with a Replace shortcut, edit history. No editing on non-draft charts.

**Verified**
- Backend: 155 tests (`./mvnw verify`), 11 new (6 `ChartCheckerTest`, 5 `ChartEditIntegrationTest`:
  blocking rules and alternatives, replace/stale edit, remove/fill with unfilled bookkeeping,
  leave-after-generation issue, roles and cross-college isolation).
- Frontend: 68 tests (5 new), lint, typecheck and build clean.
- In the browser against the running backend with demo data: blocked choice (busy at that time),
  replace with reason, remove then fill, history entries. A crash only a real browser shows
  (`scrollIntoView` returning a Promise inside `useEffect`) was found this way and fixed.

**Not done / known limits**
- `availability_override` (exam cell overriding approved leave for one session) is still not built;
  the editor simply blocks people on leave.
- Adding a person beyond a hall's requirement is refused (`POSITION_NOT_OPEN`); raise the
  requirement on the session page and generate again instead.
- Validation and alternatives ignore look-ahead scarcity (generation-only); they rank by the same
  soft costs otherwise.
- Tip: VS Code's Java extension compiles into `backend/target` too and can leave broken test
  classes ("Unresolved compilation problems"); delete the class or run `./mvnw clean` if that happens.

**Next:** M10 review and publish (use `ChartChecker.violations()` / `ChartView.issues` to block
publishing).
