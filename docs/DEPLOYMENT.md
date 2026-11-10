# Deploying Invigilo

Invigilo is one Spring Boot API (Java 21), one static React app and one PostgreSQL database
(Supabase). This page lists what a production deployment needs. Local development is in
`docs/HANDOVER.md`.

## 1. Database: Supabase

1. Create a Supabase project in a region near the users (for India: *South Asia (Mumbai)*). Keep the
   database password in a password manager; it is needed once, for `DB_PASSWORD`.
2. *Connect → Session pooler*, JDBC: the URL looks like
   `jdbc:postgresql://aws-0-<region>.pooler.supabase.com:5432/postgres?sslmode=require` and the user
   `postgres.<project-ref>`. Add `&reWriteBatchedInserts=true` to the URL (the API writes chart rows
   in batches).
   - Use the **session pooler (port 5432)**, not the transaction pooler (6543): Flyway and JDBC
     prepared statements need a session (ARCHITECTURE.md §10).
3. Nothing has to be created by hand. On first start Flyway creates the schema `invigilo` and runs
   every migration (V1-V10). Tables never go in `public`.
4. *Project Settings → Data API*: make sure `invigilo` is **not** in the exposed schemas (the default
   exposes only `public` and `graphql_public`). If nothing else uses the Data API, switch it off. All
   access goes through the Spring Boot API, which enforces college separation and roles.
5. Backups: Supabase takes daily backups; enable point-in-time recovery on paid plans before an exam
   season.
6. Pool size: `DB_POOL_SIZE` (default 5) per API instance. Keep instances × pool size below the
   pooler's client limit for the plan.

## 2. API (Spring Boot)

Build: `cd backend && ./mvnw -DskipTests package` → `target/invigilo-backend-0.1.0-SNAPSHOT.jar`.
Run: `java -jar invigilo-backend-0.1.0-SNAPSHOT.jar` with these environment variables (any host that
runs a JVM 21 container works; it listens on `PORT`, default 8080):

| Variable | Production value |
|---|---|
| `DB_URL` | Session-pooler JDBC URL from §1 (with `sslmode=require&reWriteBatchedInserts=true`) |
| `DB_USERNAME` | `postgres.<project-ref>` |
| `DB_PASSWORD` | The Supabase database password (a secret: set it in the host's secret store) |
| `JWT_SECRET` | 48+ random characters, e.g. `openssl rand -base64 48`; a secret; changing it signs everyone out |
| `CORS_ALLOWED_ORIGINS` | The frontend's origin, e.g. `https://invigilo.example.edu` (comma-separated if several) |
| `REFRESH_COOKIE_SECURE` | `true` (the default; only HTTPS) |
| `API_DOCS_ENABLED` | `false` (hides `/v3/api-docs` and Swagger UI) |
| `DB_POOL_SIZE` | Optional, default 5 |
| `JWT_ACCESS_TOKEN_TTL`, `REFRESH_TOKEN_TTL` | Optional, defaults 15 minutes and 14 days |

Never set `SPRING_PROFILES_ACTIVE=demo` in production: that profile loads the demo college.

Health check for the host: `GET /actuator/health` (no details are exposed). `GET /api/v1/system/status`
shows whether the database is reachable and the schema version.

## 3. Frontend (React)

Build: `cd frontend && npm ci && VITE_API_BASE_URL=https://api.example.edu npm run build` → `dist/`.
Serve `dist/` from any static host or CDN, with a fallback to `index.html` for unknown paths (the app
uses client-side routes such as `/admin/exams`).

**Keep the app and the API on the same site.** The refresh cookie is `HttpOnly; Secure;
SameSite=Strict` and scoped to `/api/v1/auth`. Browsers send it only when the app and the API share a
registrable domain: `invigilo.example.edu` + `api.example.edu`, or one domain with the API behind
`/api` through a reverse proxy, both work. Two unrelated domains (e.g. two different free hosting
subdomains) would sign users out on every reload.

## 4. First start

1. Start the API; check `/api/v1/system/status` reports the database `UP` and schema version 10.
2. Open the app and register the college's examination cell (`/register`). The registrant becomes the
   first exam-cell admin and goes through college setup.
3. Add departments, subjects and halls, import faculty (CSV), invite staff from each faculty page.

## 5. Security checklist (M18 review)

- College separation: the college comes only from the token; every tenant table is filtered by
  Hibernate `@TenantId`; the only raw SQL is `select 1` in the status check. Other colleges' records
  answer 404; every tenant feature has an isolation test.
- Roles: exam-cell, HOD and faculty endpoints check roles on the server; ownership (own duties, own
  swaps, own department) is checked in the services.
- Sign-in: BCrypt passwords, constant-time response for unknown emails, 5 failed passwords per email
  in 15 minutes pause that email for 15 minutes (`LoginThrottle`, in memory per instance).
- Tokens: short-lived access token kept in memory only (never in browser storage); refresh token in
  the cookie above, rotated on use and revocable.
- Reports: CSV cells starting with `=`, `+`, `-`, `@` are prefixed with `'` (no formula injection);
  Excel cells are written as values.
- Errors return ProblemDetail without stack traces; unexpected errors are logged on the server only.
- No secrets in the repository; `backend/.env` is git-ignored. Frontend dependencies:
  `npm audit --omit=dev` found 0 vulnerabilities (2026-11-10).
- Open points: the login pause is per instance (use a shared store such as Redis if running several
  instances behind a load balancer); there is no account-level password reset flow yet (the exam cell
  re-invites).

## 6. Expected performance

Measured locally (one laptop, embedded PostgreSQL) on a college-sized test: 300 faculty, 25 halls,
30 sessions, 1,359 positions:

| Operation | Time |
|---|---|
| Generate the duty chart (all 1,359 filled, 4-5 duties each) | 6.3 s (engine 3.2 s) |
| Load the chart for review | 0.7 s |
| Live rule check in the editor | 0.4 s |
| Publish | 1.8 s |
| Fairness analytics / remuneration / substitute candidates | 0.3-0.4 s |
| Duty chart report by faculty: PDF / Excel / CSV | 1.2 s / 2.7 s / 0.2 s |

Against Supabase each database round trip adds network latency; inserts are batched (50 per round
trip) to keep generation and publishing quick. Re-measure after connecting Supabase.
