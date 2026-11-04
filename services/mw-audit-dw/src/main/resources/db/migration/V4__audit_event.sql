-- Audit trail (spec 14.3, 16.2, 16.4): append-only audit.event, monthly range partitions on the
-- time the row was written (recorded_at), one SHA-256 hash chain per partition (month), and the
-- chain heads that anchor them. Partitions are created on demand by ensure_month_partition when
-- the first event of a month arrives.
--
-- Roles: this migration runs as the schema owner (audit_dw_rw in the stack). The service itself
-- connects as audit_writer, which may only INSERT into event (no SELECT, UPDATE, DELETE or
-- TRUNCATE); nobody is granted UPDATE or DELETE on event, and a trigger refuses both even for the
-- owner. A superuser can still bypass all of that, which is what the hash chain detects.

-- The writer role is normally created by the stack's init step (init/init.sh) with a login and
-- password; create it here only if it is missing (tests, a fresh database) so the grants apply.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'audit_writer') THEN
    CREATE ROLE audit_writer NOLOGIN;
  END IF;
END
$$;

CREATE TABLE event (
  month           text        NOT NULL,              -- yyyy-MM (UTC) of recorded_at: partition and chain
  seq             bigint      NOT NULL,              -- position in the month's chain, from 1
  audit_id        uuid        NOT NULL,
  recorded_at     timestamptz NOT NULL,
  at              timestamptz NOT NULL,              -- when it happened, as the producer says
  txn_id          uuid,
  correlation_id  text        NOT NULL,
  actor_type      text        NOT NULL,
  actor_id        text        NOT NULL,
  action          text        NOT NULL,
  resource        text        NOT NULL,
  outcome         text        NOT NULL,
  service         text        NOT NULL,
  source_ip       text,
  payload_ref     text,
  before          jsonb,
  after           jsonb,
  prev_hash       bytea       NOT NULL,
  hash            bytea       NOT NULL
) PARTITION BY RANGE (recorded_at);

CREATE INDEX event_txn_idx ON event (txn_id, recorded_at) WHERE txn_id IS NOT NULL;
CREATE INDEX event_at_idx ON event (at);
CREATE INDEX event_actor_idx ON event (actor_type, actor_id, recorded_at);

-- Last seq and hash of each month's chain: the writer locks it to append (one writer per chain
-- at a time) and it lets a verifier notice deleted rows at the end of a chain.
CREATE TABLE chain_head (
  month       text        PRIMARY KEY,
  seq         bigint      NOT NULL,
  hash        bytea       NOT NULL,
  updated_at  timestamptz NOT NULL
);

-- Every auditId ever stored (not partitioned, never purged): the dedup of 14.3, so a redelivery
-- is refused however late it comes. Written in the same transaction as the event row.
CREATE TABLE event_key (
  audit_id     uuid        PRIMARY KEY,
  recorded_at  timestamptz NOT NULL
);

CREATE FUNCTION refuse_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'audit.event is append-only (% refused)', TG_OP
    USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER event_append_only
  BEFORE UPDATE OR DELETE ON event
  FOR EACH ROW EXECUTE FUNCTION refuse_change();

-- Creates the partition for one month (yyyy-MM) if it is missing, with a unique seq index.
-- SECURITY DEFINER so the INSERT-only writer can add a month without owning the table; it can do
-- nothing else through it.
CREATE FUNCTION ensure_month_partition(p_month text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path FROM CURRENT
SET timezone = 'UTC'
AS $$
DECLARE
  part  text;
  first timestamptz;
BEGIN
  IF p_month IS NULL OR p_month !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' THEN
    RAISE EXCEPTION 'invalid month %', p_month;
  END IF;
  part := 'event_' || replace(p_month, '-', '_');
  IF to_regclass(part) IS NOT NULL THEN
    RETURN;
  END IF;
  first := (p_month || '-01T00:00:00Z')::timestamptz;
  EXECUTE format('CREATE TABLE %I PARTITION OF event FOR VALUES FROM (%L) TO (%L)',
                 part, first, first + interval '1 month');
  EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (month = %L)', part, part || '_month_check', p_month);
  EXECUTE format('CREATE UNIQUE INDEX %I ON %I (seq)', part || '_seq_key', part);
END
$$;

REVOKE ALL ON event, event_key, chain_head FROM PUBLIC;
REVOKE ALL ON FUNCTION ensure_month_partition(text) FROM PUBLIC;

GRANT USAGE ON SCHEMA audit TO audit_writer;
GRANT INSERT ON event, event_key TO audit_writer;
GRANT SELECT, INSERT, UPDATE ON chain_head TO audit_writer;
GRANT EXECUTE ON FUNCTION ensure_month_partition(text) TO audit_writer;
-- Chassis tables (V2, V3): inbox and its purge, outbox relay, ShedLock.
GRANT SELECT, INSERT, DELETE ON inbox TO audit_writer;
GRANT SELECT, INSERT, UPDATE, DELETE ON outbox, shedlock TO audit_writer;
