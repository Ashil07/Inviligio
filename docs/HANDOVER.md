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
- CI workflow for backend (incl. Testcontainers) and frontend.

**Verified**
- `./mvnw verify`: 9 tests pass; 2 Testcontainers tests skipped locally (Docker daemon not running).
- `npm run lint`, `npm test` (2 tests), `npm run build` pass.

**Not yet verified**
- App started against a real PostgreSQL / Supabase, and the integration tests. First step for
  Developer 1: start Docker (`docker compose up -d`) or configure Supabase in `backend/.env`,
  run `./mvnw verify` and `./mvnw spring-boot:run`, open http://localhost:5173 and see both checks green.

**Next:** milestone M1 in [DEVELOPMENT_PLAN.md](DEVELOPMENT_PLAN.md).

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
