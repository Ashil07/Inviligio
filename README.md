# Invigilo

Smart examination invigilation duty allocator for colleges: configurable per-college rules,
automated and explainable duty allocation, manual editing with conflict detection, swaps,
substitutes, fairness analytics, reports and remuneration. Multi-college (multi-tenant).

**Stack:** Spring Boot 3.5 (Java 21) · React 19 + Vite + TypeScript + Tailwind · PostgreSQL (Supabase) · Flyway

## Documentation

| Doc | Read it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical decisions, tenancy, security, data model, API conventions, risks |
| [docs/ALLOCATION_ENGINE.md](docs/ALLOCATION_ENGINE.md) | How duties are allocated, constraints, explanations |
| [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | Workflow, definition of done, milestones, open product questions |
| [docs/HANDOVER.md](docs/HANDOVER.md) | Current state and handover notes between developers |
| [CLAUDE.md](CLAUDE.md) | Rules for Claude Code working in this repo |

## Prerequisites

- Java 21 (Temurin). Maven is **not** required — use the wrapper `./mvnw`.
- Node 24 + npm.
- Nothing else for tests: backend integration tests start an embedded PostgreSQL 17 by themselves.
- To *run* the app: Docker Desktop (`docker compose up -d`) **or** a Supabase project (connected later, see docs/DEVELOPMENT_PLAN.md).
- [Graphify](https://pypi.org/project/graphifyy/) CLI: `pip install graphifyy` (or `uv tool install graphifyy`).

## Run locally

### Quickest: no database to install

The backend can run its own embedded PostgreSQL 17 (data kept in `backend/.dev-db`, git-ignored):

```bash
cd backend
cp .env.example .env        # set JWT_SECRET (32+ characters)
./mvnw spring-boot:test-run "-Dspring-boot.run.main-class=com.invigilo.LocalDevApplication"
```

In PowerShell use `.\mvnw.cmd` instead of `./mvnw`. Add `"-Dspring-boot.run.profiles=demo"` to
load the demo college (needs `DEMO_ADMIN_EMAIL` and a `DEMO_ADMIN_PASSWORD` of 8+ characters in
`backend/.env`). The first start takes about a minute; it is ready at `Started InvigiloApplication`.
Stop it with Ctrl+C (that also stops the embedded database). If it ever reports
`could not lock ... .dev-db\epg-lock`, a previous backend is still running: stop that process.
Delete `backend/.dev-db` to start from an empty database. `DB_URL` in `.env` is ignored in this mode.

### With a real database (Docker or Supabase)

```bash
# 1. Database (or point backend/.env at Supabase - see the comments in .env.example)
docker compose up -d

# 2. Backend  ->  http://localhost:8080   (Swagger UI: /swagger-ui.html)
cd backend
cp .env.example .env
./mvnw spring-boot:run

# 3. Frontend ->  http://localhost:5173   (proxies /api to :8080)
cd frontend
npm install
npm run dev
```

Open http://localhost:5173/register to create a college, or `/status` for API/database status.

To start with realistic data instead, set `DEMO_ADMIN_EMAIL` and `DEMO_ADMIN_PASSWORD` in
`backend/.env` and run the backend once with `SPRING_PROFILES_ACTIVE=demo`: it creates college
`DEMO` (50 faculty, a seated exam week). Sign in with those credentials and open
*Examinations → Allocate duties*.

## Test

```bash
cd backend && ./mvnw verify                       # unit + web + integration (embedded PostgreSQL)
cd frontend && npm run lint && npm test && npm run build
```

## Code graph

```bash
graphify update .                 # rebuild the graph after pulling or big changes
graphify query "how is the tenant resolved"
graphify affected "SecurityConfig"
```

Output goes to `graphify-out/` (git-ignored). Once per clone, install the hooks that keep it current:

```bash
graphify hook install     # git hooks: rebuild after commit / checkout / merge
graphify claude install   # Claude Code hooks; then rename .claude/settings.json -> .claude/settings.local.json
                          # (it contains machine-specific paths and is git-ignored under that name)
pip install "graphifyy[sql]"   # optional: include Flyway SQL migrations in the graph
```
