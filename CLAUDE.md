# Invigilo — instructions for Claude Code

Exam invigilation duty allocator. Spring Boot 3.5 / Java 21 backend in `backend/`, React + Vite +
TypeScript frontend in `frontend/`, PostgreSQL (Supabase) via Flyway. Read `docs/ARCHITECTURE.md`
before structural changes and `docs/ALLOCATION_ENGINE.md` before touching allocation, validation,
swaps or substitutes. Current state and next milestone: `docs/HANDOVER.md`, `docs/DEVELOPMENT_PLAN.md`.

## Commands

- Backend: `cd backend && ./mvnw verify` (tests) · `./mvnw spring-boot:run` (needs `backend/.env`, see `.env.example`)
- Frontend: `cd frontend && npm run dev` · `npm test` · `npm run lint` · `npm run build`
- Local DB: `docker compose up -d` (repo root)

## Non-negotiable rules

- **Tenancy:** college id comes only from the JWT `cid` claim. Never accept `collegeId` from a
  request for authorization. Tenant entities extend the `@TenantId` base; native SQL must filter
  `college_id` explicitly. Other-college resources return 404. Every new tenant resource gets a
  tenant-isolation test.
- **Hard constraints are never violated** — not by the generator, editor, swaps or substitutes.
  All four use the same constraint classes in `allocation/engine/constraint`. Don't duplicate rules.
- The allocation engine (`allocation/engine`) is pure Java: no Spring, JPA, clock or randomness.
- Schema changes only via a new Flyway migration; never edit a merged migration. `ddl-auto=validate`.
- Tables live in schema `invigilo`, never `public` (Supabase exposes `public`).
- Controllers thin, services own transactions and rules, DTOs are records, entities never leave services.
- Errors: throw `ApiException` with a stable `code`; the handler emits ProblemDetail.
- State changes that affect duties call `AuditService.record` inside the same transaction.
- Editable records: views carry `version`, updates call `Versions.requireUnchanged`. Deleting a referenced
  row is refused via `UsageCheck<T>` beans; register one when a new table references another.
- Child tables use composite FKs `(college_id, parent_id) → parent (college_id, id)`.
- Link tables are entities extending `TenantScopedEntity` (they need `college_id`), not `@ManyToMany`.
- Bulk changes go through `importer/` (validate everything, then apply in one transaction via services).
- Policy rules: change `PolicySettings` and `features/policy/schema.ts` together, plus a backfill migration.
- Frontend: server data through TanStack Query hooks in `features/<x>/api.ts`; forms with React Hook
  Form + Zod; no fake/mock-only buttons in app code.
- QR attendance is out of scope. Do not add it.
- Do not use subagents / parallel agents to split feature work (project rule).
- Pre-tenant flows (login, registration, refresh) go through `SystemTransactions`; never set the system
  context inside an open transaction. New tenant entities extend `TenantScopedEntity`.
- Integration tests extend `support/IntegrationTest` (embedded PostgreSQL, no Docker). Use unique test data.
- The hosted database (Supabase) is connected later by Developer 2; don't add a dependency on it.
- Demo data: `demo/DemoData` (profile `demo`); keep it loadable when schemas change (`DemoDataIntegrationTest`).
- Don't claim something works without running it.

## graphify

Before changing a module, also run `graphify affected "<Symbol>"` to see what depends on it.
The graph is local and git-ignored; rebuild it after pulling.

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

Rules:
- When graphify-out/graph.json exists, run `graphify query "<question>"` before your first grep, search or source-file read for a codebase question, and put this rule in every subagent prompt that explores code. Read raw files only for lines the graph or an earlier read already located. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
- A committed graphify-out/graph.json is rewritten whole on every build, so a large diff to it in a pull request is expected. Do not flag it as churn or suggest skipping the graph rebuild or its hooks.
