# Development Plan and Workflow

Two developers work **sequentially**, each across the full stack. Developer 2 continues from
Developer 1's actual code; nothing is rebuilt. No AI subagents or parallel agents split the work.

## 1. Daily workflow

1. `git pull` → `graphify update .` (refresh the code graph; AST only, seconds, no API key).
2. Before touching a module: `graphify query "how does <X> work"` or `graphify affected "<Class>"`
   to see what depends on it. Open `graphify-out/GRAPH_REPORT.md` for the big picture.
3. Branch from `main`: `feat/<area>-<short-name>`, `fix/<short-name>`.
4. Implement with tests (definition of done below). Run locally:
   `cd backend && ./mvnw verify` · `cd frontend && npm run lint && npm test && npm run build`.
5. Commit with Conventional Commits (`feat(faculty): add bulk CSV import`). The graphify
   post-commit hook rebuilds the graph after each commit.
6. Push, open a PR into `main`. CI (`.github/workflows/ci.yml`) must be green — it runs the
   integration tests (embedded PostgreSQL, no Docker needed).
7. Squash-merge. Keep `main` always runnable.

New Flyway migration = new file `V<next>__<description>.sql`. Never edit a migration that has been
merged. If two branches race for the same number, the later one renumbers before merge.

## 2. Definition of done (every feature)

- Backend: entity + migration, service with business rules, controller with DTO records, Bean
  Validation, `@PreAuthorize` role checks, service-level scope checks (HOD/FACULTY).
- Tests: service unit tests; web-layer tests for 401/403/validation; **tenant isolation test** for
  every new tenant resource; `IntegrationTest` (embedded PostgreSQL) for anything with non-trivial SQL.
- Frontend: real API calls (no mocks in app code), loading/error/empty states, Zod form validation,
  backend `fieldErrors` shown on fields, role-appropriate navigation, at least one RTL test for
  forms or non-trivial logic.
- Audit-log entries for state changes that affect duties.
- Docs: ARCHITECTURE.md / ALLOCATION_ENGINE.md updated if a decision changed; `graphify update .` run.
- Verified by actually running the flow in the browser against a real database — not only tests.

## 3. Developer 1 — foundation and core (milestones)

| # | Milestone | Scope / acceptance |
|---|---|---|
| M0 | Foundation | **Done in initialization.** Builds, security skeleton, error format, status endpoint, CI, docs. |
| M1 | Auth & tenancy | **Done.** `college`, `app_user`, `college_membership`, `refresh_token` migrations; register-college/login/refresh/logout/`me`; `TenantContext` + `@TenantId` base entity; tenant-isolation test harness. FE: login (with college picker), college registration, in-memory session, Axios 401→refresh→retry, role areas and guards. |
| M2 | College onboarding & policy | **Done.** Institution profile on `college`; versioned `college_policy` (`PolicySettings`, HARD/SOFT/OFF modes); `audit_log`; onboarding wizard (institution → faculty rules → workload → staffing → availability → swaps → review); college and policy settings pages with version history. |
| M3 | Departments, subjects, halls | **Done.** CRUD with codes unique per college, composite tenant FK subject→department, delete only when unused (`UsageCheck`), deactivate otherwise, stale-edit protection, audit; admin pages with search/paging for subjects. |
| M4 | Faculty, access, import | **Done.** Faculty CRUD (department, designation, employment type, joined-on seniority, chief eligibility, duty weight, subjects taught), per-person restrictions, login invitations with role management, CSV import (dry run + all-or-nothing apply) for departments, subjects, halls and faculty. |
| M5 | Exam scheduling | **Done.** Periods, sessions (single or generated per day with weekday skipping), exams, session halls with mixed seating, computed staffing with reasoned overrides, capacity/registration/overlap checks and warnings; admin pages for periods, sessions and seating. |
| M6 | Availability | **Done.** Faculty request whole days or single sessions within policy limits; HOD (own department) or exam cell approves; exam cell records leave for anyone; cancellation; per-session overview; faculty, HOD and exam-cell pages. Admin override of approved unavailability moves to M9. |
| M7 | Allocation engine | **Done.** Pure-Java engine (hard rules, soft costs, per-group Hungarian, look-ahead, repair, validation, explanations), runs and DRAFT charts persisted with policy version and input fingerprint. 3000 positions in ~0.5 s. |
| M8 | Basic allocation review | **Done.** `/admin/exams/:periodId/allocation`: generate/regenerate (confirmed), stale-draft and newer-policy warnings, fill statistics, unfilled positions with reasons and suggestions, chart grid per date with per-duty explanations, workload table. |
| H1 | Handover | **Done** except pushing: HANDOVER.md filled in; demo data loader (`demo` profile) with an end-to-end allocation test; all tests green locally. CI has not run because there is no GitHub remote yet. |

**Database hosting is deferred to Developer 2** (product owner decision, 2026-11-08). Developer 1
builds and verifies everything against embedded PostgreSQL in tests; nothing requires Supabase or a
local database server. Developer 2 connects Supabase (ARCHITECTURE.md §10) as the first step of M18,
or earlier when a running instance is needed for manual testing.

## When Developer 2 starts

Developer 2 starts **after H1**, not before: M9-M17 build on the duty chart, allocation runs and
explanations that M7-M8 create, and the two developers work sequentially on one codebase.
Start Developer 2 when all of these are true:

1. M5-M8 are done: periods/sessions/halls can be scheduled, availability exists, "Generate"
   produces a DRAFT chart with unfilled positions explained, and the review grid shows it.
2. `docs/HANDOVER.md` "Developer 1 → Developer 2" is filled in (endpoints, latest migration,
   implemented policy rules, engine status and measured performance, known issues).
3. A demo seed (one college with departments, subjects, halls, ~50 faculty, one exam period)
   can be loaded, so Developer 2 can see real charts immediately.
4. `main` is green in CI and everything is committed and pushed to GitHub.

Status 2026-11-09: 1-3 are done; 4 is not (no GitHub remote yet). Pushing is the first step of
Developer 2's first day if it has not been done before.

Developer 2's first day: clone, `graphify hook install`, `graphify update .`, read HANDOVER.md
and GRAPH_REPORT.md, connect Supabase (ARCHITECTURE.md §10), load the seed, click through every
admin page, then start M9.

## 4. Developer 2 — advanced features and completion

| # | Milestone | Scope / acceptance |
|---|---|---|
| M9 | Duty chart editor | **Done** except `availability_override` (see HANDOVER.md). Replace/add/remove assignments; live `validate` endpoint (blocking errors vs warnings) using the shared constraints; alternative suggestions; optimistic locking; edit history. |
| M10 | Review & publish | Review screen; publish blocked by hard errors; immutable versions; supersede flow; audit history. |
| M11 | Faculty dashboard | Upcoming/past duties, calendar, availability requests, own schedule download. |
| M12 | HOD dashboard | Department faculty, duties, workload (over/under), leave and swap approvals. |
| M13 | Duty swaps | Request → recipient accept → optional HOD → transactional revalidated swap → audit. |
| M14 | Emergency substitutes | Ranked eligible candidates with reasons; admin selects; audited reassignment. |
| M15 | Fairness analytics | From real `duty_assignment` data: per-faculty totals, mean/min/max/std-dev, department comparison, history, unfilled. Recharts. |
| M16 | Reports | PDF (OpenPDF), Excel (POI, streaming), CSV: full, faculty-, hall-, department-, date-, session-wise charts; workload, fairness, remuneration; filters. |
| M17 | Remuneration | Rates with effective dates per college; computed from completed duties; department totals. |
| M18 | Final integration | **Connect Supabase** (session pooler, `backend/.env`), run migrations there, end-to-end walkthrough per role, security review, performance check on realistic data, deployment notes. |

## 5. Open product questions

Each has a **working default** so development is not blocked. The product owner should confirm or
change these; update ARCHITECTURE.md / ALLOCATION_ENGINE.md when answered.

| # | Question | Working default |
|---|---|---|
| Q1 | Who may register a new college — anyone (self-service) or a platform operator? Is there a platform super-admin? | Self-service registration; registrant becomes EXAM_CELL_ADMIN; no super-admin in v1. |
| Q2 | Is the "chief invigilator" per **hall**, or per session/centre (Chief Superintendent)? Does the chief count toward the hall's invigilator count? | Optional per hall; counts toward staffing. |
| Q3 | QR attendance is excluded — so what makes a duty "completed" for remuneration? | Past-dated assignments count as completed unless the admin marks them ABSENT/CANCELLED. |
| Q4 | What does "senior faculty receive preference" mean: fewer duties, chief roles, or both? | Both offered as separate policy options (implemented in M2: `seniorsGetFewerDuties`, `preferSeniorsAsChief`). |
| Q5 | Can one person belong to several colleges (e.g. visiting faculty)? | Supported in the data model; college picker at login. |
| Q6 | Do HODs (and exam-cell staff) also receive invigilation duties? | Yes, if they have an active faculty record. |
| Q7 | Who approves leave when HOD approval is *not* required — auto-approve or exam cell? | Auto-approve; admin can always reject/override. |
| Q8 | Are email notifications (published chart, swap requests) in scope? | In-app only in v1. |
| Q9 | Swap types: two-way exchange only, or also one-way handover? | Both. |
| Q10 | Can non-teaching staff invigilate? | Modelled as faculty with a different employment type. |
