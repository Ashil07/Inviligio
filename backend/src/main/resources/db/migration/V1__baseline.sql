-- Baseline migration. Flyway creates the "invigilo" schema (spring.flyway.create-schemas)
-- and runs every migration inside it.
--
-- Conventions for all following migrations (see docs/ARCHITECTURE.md, "Database"):
--   * One file per change: V<n>__<snake_case_description>.sql. Never edit an applied migration.
--   * Primary keys: uuid default gen_random_uuid().
--   * Every tenant-owned table has college_id uuid not null references college(id),
--     and every unique constraint on such a table includes college_id.
--   * timestamptz for instants, date / time for exam calendar values.
--   * Enumerations as text + CHECK constraint (readable, easy to extend in a migration).

comment on schema invigilo is 'Invigilo application data. Accessed only through the Spring Boot API.';
