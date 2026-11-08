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

## Developer 1 → Developer 2  (date)

**Completed milestones:**  
**Endpoints (see `/swagger-ui.html`):**  
**Database migrations (latest version):**  
**Policy settings implemented (vs ALLOCATION_ENGINE.md §3):**  
**Allocation engine status (constraints implemented, performance measured):**  
**Known bugs / limitations:**  
**Deviations from ARCHITECTURE.md and why:**  
**Seed / demo data (how to load, test accounts location):**  
**Product questions answered (Q#: answer):**  
**Suggested first task for Developer 2:**  
