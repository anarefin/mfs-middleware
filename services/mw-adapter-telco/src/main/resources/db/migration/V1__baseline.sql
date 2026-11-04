-- Baseline of this service's own schema (spec 16.2, 16.4). Flyway creates the schema named in
-- spring.flyway.schemas and runs every V<n>__*.sql here on start-up. Only expand/contract
-- (backward-compatible) changes within a release; never edit an applied migration.
COMMENT ON SCHEMA partner_ref IS 'Owned by mw-adapter-telco; migrated by Flyway on start-up';
