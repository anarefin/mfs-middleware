-- Baseline of this service's own schema (spec 16.2, 16.4). Flyway creates the schema named in
-- spring.flyway.schemas and runs every V<n>__*.sql here on start-up. Only expand/contract
-- (backward-compatible) changes within a release; never edit an applied migration.
COMMENT ON SCHEMA service_template IS 'Owned by mw-service-template; migrated by Flyway on start-up';
