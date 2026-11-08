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
