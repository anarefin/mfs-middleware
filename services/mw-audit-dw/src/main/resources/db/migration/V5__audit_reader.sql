-- Read-only role for the scheduled hash-chain verification (spec 14.3, ticket 50). The service
-- runs as the INSERT-only audit_writer, which cannot read audit.event; the verify job connects as
-- audit_reader, which may only SELECT the chain (event and chain_head) and nothing else.
-- Selecting through the partitioned parent checks the parent's privileges only, so new monthly
-- partitions need no grant.

-- The stack's init step (init/init.sh) creates the role with a login and password; create it
-- here only if it is missing (tests, a fresh database) so the grants apply.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_reader') THEN
    CREATE ROLE audit_reader NOLOGIN;
  END IF;
END
$$;

GRANT USAGE ON SCHEMA audit TO audit_reader;
GRANT SELECT ON event, chain_head TO audit_reader;
