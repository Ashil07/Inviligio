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
- Docker Desktop (for the local database and integration tests) **or** a Supabase project.
- [Graphify](https://pypi.org/project/graphifyy/) CLI: `pip install graphifyy` (or `uv tool install graphifyy`).

## Run locally

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

The home page shows a live system status (API, database, schema version).

## Test

```bash
cd backend && ./mvnw verify                       # unit + web + Testcontainers (needs Docker)
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
