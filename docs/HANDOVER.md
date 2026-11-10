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
| My duties (M11) | `GET /api/v1/me/duties`, `GET /api/v1/me/duties.ics` | linked faculty |
| Department (M12) | `GET /api/v1/department/overview[?periodId=]` | HOD |
| Remuneration (M17) | `GET/POST /api/v1/remuneration/rates`, `DELETE /remuneration/rates/{id}`, `GET /remuneration`, `PUT /exam-periods/{id}/duty-chart/assignments/{assignmentId}/attendance`, `GET .../reports/remuneration` | admin |
| Reports (M16) | `GET /api/v1/exam-periods/{id}/reports/{duty-chart,workload,fairness}?format=` | admin |
| Analytics (M15) | `GET /api/v1/analytics/fairness[?periodId=]` | admin |
| Substitutes (M14) | `GET/POST /api/v1/exam-periods/{id}/duty-chart/assignments/{assignmentId}/substitute` | admin |
| Swaps (M13) | `GET /api/v1/me/swap-options`, `POST /me/swap-requests[/validate]`, `GET /me/swap-requests`, `POST /swap-requests/{id}/{accept,decline,cancel}`, `GET /swap-requests`, `POST /swap-requests/{id}/{approve,reject}` | faculty / HOD / admin |

**Database migrations:** V1-V10 (latest `V10__remuneration`). All tables in schema `invigilo`, every
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

---

## M10 Review and publish: done  (2026-11-09)

Merged before this: pull requests #1 (leave-form fix) and #2 (M9); CI passed on both.

**Built** (branch `feat/allocation-publish`, no migration)
- `ChartPublishService`: publish (draft as reviewed, blocked by rule breaks, supersedes the old
  version, freezes the timetable by setting the period PUBLISHED) and "start new version" (copy of
  the published chart as a new draft). `ExamPeriod.allowsChartChanges()` lets drafts be generated,
  edited and published after publishing; only CLOSED is final. Edits bump the draft's version.
- `AllocationService.versions()/version(n)`; `ChartView` gained `version`, `publishedAt`,
  `publishedBy`, `publishedVersion`.
- Frontend: "Review and publish" checklist and button, "Start new version" / "Generate new draft"
  on a published chart, versions table, read-only version page
  (`/admin/exams/:periodId/allocation/versions/:n`). Shared test setup moved to `ChartScenario`.

**Verified**
- Backend: 4 new integration tests (`ChartPublishIntegrationTest`); full suite green.
- Frontend: 71 tests (3 new), lint, typecheck, build clean.

**Known limits / decisions for the product owner**
- After publishing, the timetable is frozen. Rescheduling an exam after publication has no flow yet
  (would need an explicit "unlock timetable" that keeps published duties safe).
- Faculty and HOD cannot see the published chart yet: that is M11/M12.
- No notifications on publish (Q8: in-app only).

---

## M11 Faculty dashboard: done  (2026-11-09)

**Built** (branch `feat/faculty-dashboard`, no migration)
- Backend: `MyDutiesService` + `MyDutiesController` (`/api/v1/me/duties`, `/api/v1/me/duties.ics`);
  repository queries `findPublishedForFaculty` and `findInHalls` (colleagues).
- Frontend: `features/myduties/` (`FacultyDashboard`, `api.ts`) on `/faculty`: next duty, counts,
  month calendar (weeks start Monday, today ringed), upcoming and past tables, leave-request status
  with a link to `/faculty/availability`, and "Download calendar" (.ics).
- Test support: `ChartScenario` faculty now have emails, and `loginAs(code)` signs in as one.

**Verified**
- Backend: 3 new integration tests (`MyDutiesIntegrationTest`: drafts hidden, published duties with
  colleagues, no duplicates after a new version, calendar content and line folding, unlinked login
  403, faculty with no duties); full suite green.
- Frontend: 75 tests (4 new); lint, typecheck, build clean.
- In the browser as demo faculty Arun Nair (F004): dashboard with two published duties; the .ics
  file fetched from the running API is valid.

**Known limits**
- Duty status (COMPLETED/ABSENT) is not set anywhere yet; "Done" means the session has ended.
  Marking attendance belongs with remuneration (M17, product question Q3).
- The test clock only moves forward and is shared, so the upcoming/past split is not tested by
  moving time; the split uses session end time in the college's zone.

## M12 HOD dashboard: done  (2026-11-09)

**Built** (branch `feat/hod-dashboard`, no migration)
- Backend: `DepartmentDashboardService` + `DepartmentDashboardController`
  (`GET /api/v1/department/overview[?periodId=]`, role HOD). Department = the HOD's linked faculty
  record's department (`NO_DEPARTMENT_SCOPE` 403 otherwise). Published charts only; unknown or
  unpublished period → `PUBLISHED_CHART_NOT_FOUND` 404.
- Engine: the workload rule is now public as `ChartChecker.load(duties, average, dutyWeight)`
  (UNDER / BALANCED / OVER: more than one duty from an even share). The editor's workload warning
  and the dashboard both use it, so they always agree.
- Lookups: `FacultyService.inDepartment`, `activeCount`; `DutyChartRepository.findByStatus`.
- Frontend: `features/hod/` (`HodDashboard`, `api.ts`) on `/hod`: period picker (when more than one
  period is published), counts, leave waiting for a decision (existing `/availability?status=PENDING`,
  link to `/hod/requests`), workload table with badges, and the department's duties.
- Test support: `ChartScenario.loginAs(code, roles)`.

**Verified**
- Backend: `DepartmentDashboardIntegrationTest` (3: own department only, before/after publish, the
  other HOD sees only their department, faculty/exam cell 403, unknown period 404, another college's
  HOD gets 404 for this college's period) and a `ChartCheckerTest` case for the load rule; full
  suite 166 green.
- Frontend: 79 tests (4 new); lint and build clean.
- In the browser as demo HOD Dr. Asha Menon (CSE): 10 faculty, 19 duties, averages 1.9 / 1.6, the
  half-weight member's even share 0.8. The demo policy approves leave automatically, so the
  waiting-leave list was checked by the frontend test, not in the demo.

**Known limits**
- Swap approvals are part of M13 (swaps do not exist yet); add them to this page then.
- Over/under compares with the college-wide average of one published chart, not across periods;
  history across periods belongs to fairness analytics (M15).

## M13 Duty swaps: done  (2026-11-10)

**Built** (branch `feat/duty-swaps`, migration `V9__duty_swaps`)
- Backend (`allocation/`): `SwapRequest` entity and repository, `SwapService`, `SwapController`.
  Handover or exchange of duties in a PUBLISHED chart; colleague accepts (policy
  `recipientMustAccept`), optional HOD approval (`hodApprovalRequired`, HOD of the requester's
  department or the exam cell); applied under a chart lock after re-checking with `ChartChecker`,
  otherwise INVALIDATED with the reason. Audited on `swap_request`, and on `duty_chart` so swaps show
  in the chart history. `UsageCheck<Faculty>` for swap requests.
- Shared helpers: `OwnFaculty` (own faculty record, HOD department) and `CollegeTime` (college-local
  now); `MyDutiesService` and `DepartmentDashboardService` now use them.
- `ChartView.swapsNotInDraft` (renamed `changesNotInDraft` in M14) + publish checklist warning: publishing a draft started before a swap
  undoes that swap.
- Frontend `features/swaps/`: `/faculty/swaps` (ask with live rule check, exchange-only colleagues,
  answer, cancel, history), `/hod/swaps` and `/admin/swaps` (approve/reject, filter by status);
  "Swap" links on the faculty dashboard, swap notices on the faculty and HOD dashboards; new menu
  entries.
- Test support: `ChartScenario.loginAtAnotherCollege`, `swapRules`, `publishCurrentDraft`.

**Verified**
- Backend: `SwapIntegrationTest` (6: handover with acceptance, invalidated by leave approved
  meanwhile, exchange with HOD approval and department scope, who may act incl. another college,
  stale version, swaps disabled, immediate apply, draft warning); full suite 172 green.
- Frontend: 86 tests (7 new); lint, typecheck, build clean.
- In the browser with demo data: Dr. Asha Menon offered Arun Nair a hall trade in the same session
  (the clashing 27 Nov option was blocked with the reason), Arun saw the notice on his dashboard and
  accepted, the duties moved, the exam cell list shows it, and the demo's draft v3 reports 1 swap not
  in it. (The demo now has that swap applied; delete `backend/.dev-db` for a fresh demo.)

**Known limits**
- Swaps are not copied into a draft that already exists; the exam cell is warned before publishing.
- No notifications beyond the in-app notices (product question Q8).
- `adminCanReassignDirectly` is not used yet: the exam cell can change a published chart only by
  starting a new draft. Substitutes (M14) are the natural place for direct reassignment.

## M14 Emergency substitutes: done  (2026-11-10)

**Built** (branch `feat/substitutes`, no migration)
- Backend: `SubstituteService` + `SubstituteController` (exam cell). Ranked candidates
  (`ChartChecker.alternatives`, up to 15) with soft-cost and pending-leave notes plus the exclusion
  funnel; the substitution re-checks with `ChartChecker.check` under the chart lock, hands the duty
  over in place (`DutyAssignment.substitute`, source SUBSTITUTE), audits `DUTY_SUBSTITUTED` with the
  required reason and invalidates open swaps of the duty (`SwapService.invalidateOpenFor`).
  Uses policy `adminCanReassignDirectly`; refused once the session has ended.
- `ChartView.swapsNotInDraft` became `changesNotInDraft` and now counts swaps and substitutes from the
  audit log (`AuditRepository.countBy...ActionIn...OccurredAtAfter`).
- Frontend: "Substitute" on each duty of the published chart (main allocation page, or the published
  version's page while a draft is current, linked from the draft), `SubstitutePanel` (ranked list,
  best fit marked, exclusion summary, required reason), "swapped"/"substitute" badges and matching
  duty explanations.

**Verified**
- Backend: `SubstituteIntegrationTest` (3: ranked list and hand-over with history and the
  substitute's dashboard; reason required, leave blocks, stale version, faculty 403, other college
  404, policy off; open swap invalidated and draft warned); full suite 175 green.
- Frontend: 88 tests (2 new + extended); lint, typecheck, build clean.
- Against the running app with demo data (API): Dr. Anitha Thomas's 23 Nov FN H101 duty went to the
  best-ranked Suresh Rao, marked SUBSTITUTE; the demo draft's warning count went from 1 to 2. The
  substitute panel itself was not clicked through in the browser this time (the exam-cell sign-in
  was not available to the assistant); try it at `/admin/exams/<period>/allocation/versions/2`.

**Known limits**
- The session-ended rule is not tested by moving time (shared forward-only test clock).
- Open positions of a published chart cannot be filled from here; start a new version for that.
- No ABSENT marking: the absent person simply loses the duty (relevant for remuneration, M17/Q3).

## M15 Fairness analytics: done  (2026-11-10)

**Built** (branch `feat/fairness-analytics`, no migration)
- Backend: `FairnessService` + `FairnessController` (`/api/v1/analytics/fairness`, exam cell). Reads
  every published chart once: summary, faculty rows (even share and over/under via
  `ChartChecker.load`, all-periods totals), departments, history oldest first, open positions.
- Frontend `features/analytics/`: `/admin/analytics` ("Fairness" in the exam-cell menu) with stat
  tiles, a verdict line, a duties-per-person distribution chart with the average, a department chart
  against the college average, a faculty table (department filter, "only over or under"), open
  positions, and the trend across periods (average and spread) with a table. Recharts 3, first use in
  the app; validated categorical slots 1-2 (blue, orange) from the dataviz reference palette, thin
  bars, recessive grid, tables under every chart. The route is lazy-loaded so the charts library is
  its own chunk.
- `vite.config.ts`: `testTimeout` 15 s. Form tests typing with user-event take a few seconds each
  and timed out under full parallel runs on an 18-core Windows machine; they pass alone.

**Verified**
- Backend: `FairnessIntegrationTest` (3: drafts ignored and one removed position shows as unfilled
  with its reasons; two periods give a two-point history, latest by default, all-periods totals;
  faculty and HOD 403, unknown period 404, another college sees nothing); full suite 178 green.
- Frontend: 93 tests (5 new); lint, typecheck, build clean.
- Against the running app with demo data (API): 81 of 82 filled, 1.62 duties per person, spread
  0.49, five departments (MA lightest at 1.0), one open position with reasons; faculty get 403.
  The page itself was not opened in the browser (the exam-cell sign-in was not available to the
  assistant); it is at `/admin/analytics`.

**Known limits**
- Per-person figures use today's active faculty, also for older periods.
- Exam cell only; HODs see their own department on their dashboard (M12).

## M16 Reports: done  (2026-11-10)

**Built** (branch `feat/reports`, no migration; adds OpenPDF and POI to `pom.xml`)
- `report/`: `Report` model, `ReportFormat` (pdf, xlsx, csv) and `ReportWriters` (pure renderers).
- `allocation/ReportService` + `ReportController`: duty chart (5 groupings, filters, any version, open
  positions), workload and fairness reports, as attachments named after the period and grouping.
- Frontend `features/reports/`: `/admin/reports` ("Reports" in the exam-cell menu): pick the report,
  period, format, grouping, chart version and filters; the file downloads with the server's name;
  problem details from a failed download are shown.
- Fixes found on the way: `useChartVersions` no longer runs without a period; the swap request form,
  the substitute panel and the reports form no longer leave a failed save as an unhandled promise.

**Verified**
- Backend: `ReportWritersTest` (5: flat and block CSV with quoting and BOM, Excel title/sections/real
  numbers, PDF text and footer, column widths) and `ReportIntegrationTest` (3: every grouping and
  filter incl. the open position and draft versions; PDF and Excel content, workload and fairness;
  unknown format/grouping/version, faculty 403, other college 404); full suite green.
- Frontend: 98 tests (5 new); lint, typecheck, build clean.
- Against the running app with demo data: every report and format downloaded; the PDFs were opened
  and checked page by page (headings stay with their tables, columns sized to content, open
  position listed).

**Known limits**
- Exam cell only; HODs and faculty have their own screens and the faculty calendar file (M11).
- Remuneration reports belong to M17.
- PDF uses the built-in Helvetica (Latin-1); names in other scripts would need an embedded font.

## M17 Remuneration: done  (2026-11-10)

**Built** (branch `feat/remuneration`, migration `V10__remuneration`)
- Backend: `RemunerationRate` (+ repository), `RemunerationService`, `RemunerationController`.
  Rates per role from an effective date (one currency per college, default INR; add and delete with
  version, audited). Pay computed from the published chart: paid once the session has ended unless
  ABSENT; duties without a rate on their date are counted, not priced. Attendance endpoint marks a
  published duty absent or present again after its session (audited, shown in the chart history,
  counted in `changesNotInDraft`). `ChartView` duties now carry `status`. Remuneration report in
  `ReportService` (faculty, departments, total, rates; department filter).
- Frontend: `/admin/remuneration` ("Remuneration" in the exam-cell menu): rates table with add/delete,
  period picker, totals, faculty and department pay, PDF/Excel/CSV downloads, a warning for duties
  without a rate. On the published chart: "Mark absent" / "Mark present" once a session is over (and
  no "Substitute" any more), an "absent" badge. "Remuneration" is also on the Reports page.

**Verified**
- Backend: `RemunerationIntegrationTest` (3: rate of the day incl. a later rate not applied, absent and
  present again with history, deleting a rate leaves duties unpriced, future exam not paid; duplicate,
  negative, three-decimal and other-currency rates refused, stale delete, attendance before the end
  and unchanged attendance refused, faculty 403, other college sees nothing and gets 404; the CSV
  report); full suite 189 green.
- Frontend: 103 tests (5 new); lint, typecheck, build clean.
- Against the running app with demo data (API): two rates added (invigilator 500, chief 750 from
  1 Jan 2026; delete them on the page if not wanted); the demo exams are still ahead, so all 81 duties
  are "still to come" and nothing is paid yet; early attendance is refused; the remuneration PDF was
  opened and checked. The page itself was not clicked through (no exam-cell sign-in for the assistant).

**Known limits**
- One rate per role; no exam-type or session-type rates.
- No faculty view of their own pay yet; HODs do not see pay.
- Attendance is only "absent" or not; no partial duties or COMPLETED marking.

## M18 Final integration: in progress  (2026-11-10)

**Done** (branch `chore/final-integration`, no migration)
- Security review of the whole app (results and checklist in `docs/DEPLOYMENT.md` §5). Fixed: no limit
  on password guessing → `LoginThrottle` (429 after 5 failures per email in 15 minutes); CSV formula
  injection in reports → `ReportWriters.safe`; Swagger UI/OpenAPI always on → `API_DOCS_ENABLED`.
  Checked: tenant filtering (only raw SQL is `select 1`), role checks on every exam-cell endpoint,
  login college choice limited to the user's memberships, no stack traces in errors, cookie flags,
  tokens in memory only, no raw HTML in the frontend, no committed secrets, `npm audit` clean.
- Performance check: a script builds a 300-faculty, 25-hall, 30-session test college through the API
  and times the heavy operations (`docs/DEPLOYMENT.md` §6). Improvements: Hibernate JDBC batching
  (generation 7.7 → 6.3 s locally, more against a remote database), Excel widths without
  `autoSizeColumn` (4.5 → 2.7 s). Everything a user waits for interactively is under one second.
  (The local demo database now also holds two test colleges named "Perf College ..."; delete
  `backend/.dev-db` for a clean demo.)
- Walkthrough per role on the demo data: 30 checks over the main exam-cell, HOD and faculty features
  through the API (all OK, including the 403s for features of other roles), and every HOD and faculty
  page opened in the browser without errors. The exam-cell pages were checked through the API only.
- `docs/DEPLOYMENT.md`: Supabase setup, environment variables, frontend hosting (same-site rule for
  the refresh cookie), first start, security checklist, expected performance.
- Tests: `LoginThrottleIntegrationTest`, a CSV formula case in `ReportWritersTest`; full backend
  suite 191 green.

- **Supabase connected** (2026-11-10): project in South Asia (Mumbai), session pooler
  `aws-0-ap-south-1.pooler.supabase.com:5432`, Data API switched off at creation. `backend/.env` holds
  the connection (git-ignored); start with the `backend-supabase` launch configuration
  (`./mvnw spring-boot:run`, no demo profile). On first start Flyway applied V1-V10 to an empty schema
  `invigilo` in 6.2 s (PostgreSQL 17.11); `/api/v1/system/status` reports the database UP at version 10.
  The local demo (`LocalDevApplication`) still uses its embedded database and ignores `DB_URL`.

