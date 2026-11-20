-- Ticket 130 (FRS AUD-01, AUD-03, AUD-04, AUD-05; CON-04 audit side): per-hop facts and masked
-- payloads of an audit event, and the transaction projection the search filters on.
--
-- audit.event gains the hop facts (hop, direction, duration_ms) a producer may send; they are part
-- of the event's chained hash when present (service/HashChain). Rows written before stay valid:
-- an event without hop facts and payloads hashes exactly as before.

ALTER TABLE event ADD COLUMN hop text;
ALTER TABLE event ADD COLUMN direction text;
ALTER TABLE event ADD COLUMN duration_ms bigint;

-- audit.payload: the hop's masked bodies, append-only like audit.event, partitioned by the same
-- month (recorded_at of its event) and linked by audit_id. Each body's size and SHA-256 are part of
-- its event's chained hash, and the verifier recomputes the SHA-256 from the body, so a changed
-- body, digest or a deleted payload row breaks the chain.
CREATE TABLE payload (
  month         text        NOT NULL,              -- the event's month (partition and chain)
  audit_id      uuid        NOT NULL,              -- the event it belongs to
  ordinal       int         NOT NULL,              -- position in the event's payload list, from 1
  recorded_at   timestamptz NOT NULL,              -- the event's recorded_at
  direction     text        NOT NULL,              -- IN (received by the middleware) | OUT (sent)
  content_type  text        NOT NULL,
  body          text,                              -- masked (producer) and scrubbed again (here)
  payload_ref   text,                              -- the full masked body in an adapter's store
  size          int,                               -- UTF-8 bytes of body
  sha256        bytea                              -- SHA-256 of body's UTF-8 bytes
) PARTITION BY RANGE (recorded_at);

CREATE INDEX payload_audit_idx ON payload (audit_id, ordinal);

CREATE TRIGGER payload_append_only
  BEFORE UPDATE OR DELETE ON payload
  FOR EACH ROW EXECUTE FUNCTION refuse_change();

-- audit.txn_index: the search projection of mw.txn.status.v1 (consumed here, ticket 130). One row
-- per transaction, its latest status by updatedAt. MSISDNs are never stored: only their keyed
-- tokens (mw-crypto TOKENISE_MSISDN). A projection, not evidence: the writer may update it.
CREATE TABLE txn_index (
  txn_id         uuid        PRIMARY KEY,
  channel        text,
  txn_type       text,
  status         text        NOT NULL,
  amount_minor   bigint,
  currency       text,
  partner_id     text,
  msisdn_tokens  text[]      NOT NULL DEFAULT '{}',
  requested_at   timestamptz,
  updated_at     timestamptz NOT NULL
);

CREATE INDEX txn_index_partner_idx ON txn_index (partner_id, requested_at) WHERE partner_id IS NOT NULL;
CREATE INDEX txn_index_channel_idx ON txn_index (channel, requested_at);
CREATE INDEX txn_index_status_idx ON txn_index (status, requested_at);
CREATE INDEX txn_index_amount_idx ON txn_index (amount_minor, requested_at);
CREATE INDEX txn_index_requested_idx ON txn_index (requested_at);
CREATE INDEX txn_index_msisdn_idx ON txn_index USING gin (msisdn_tokens);

-- ensure_month_partition now creates both monthly partitions of a month: event_yyyy_MM and
-- payload_yyyy_MM (each only when missing).
CREATE OR REPLACE FUNCTION ensure_month_partition(p_month text) RETURNS void
LANGUAGE plpgsql SECURITY DEFINER
SET search_path FROM CURRENT
SET timezone = 'UTC'
AS $$
DECLARE
  part  text;
  ppart text;
  first timestamptz;
BEGIN
  IF p_month IS NULL OR p_month !~ '^[0-9]{4}-(0[1-9]|1[0-2])$' THEN
    RAISE EXCEPTION 'invalid month %', p_month;
  END IF;
  first := (p_month || '-01T00:00:00Z')::timestamptz;
  part := 'event_' || replace(p_month, '-', '_');
  IF to_regclass(part) IS NULL THEN
    EXECUTE format('CREATE TABLE %I PARTITION OF event FOR VALUES FROM (%L) TO (%L)',
                   part, first, first + interval '1 month');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (month = %L)', part, part || '_month_check', p_month);
    EXECUTE format('CREATE UNIQUE INDEX %I ON %I (seq)', part || '_seq_key', part);
  END IF;
  ppart := 'payload_' || replace(p_month, '-', '_');
  IF to_regclass(ppart) IS NULL THEN
    EXECUTE format('CREATE TABLE %I PARTITION OF payload FOR VALUES FROM (%L) TO (%L)',
                   ppart, first, first + interval '1 month');
    EXECUTE format('ALTER TABLE %I ADD CONSTRAINT %I CHECK (month = %L)', ppart, ppart || '_month_check', p_month);
    EXECUTE format('CREATE UNIQUE INDEX %I ON %I (audit_id, ordinal)', ppart || '_key', ppart);
  END IF;
END
$$;

-- Months that already have a chain get their payload partition now.
DO $$
DECLARE
  m text;
BEGIN
  FOR m IN SELECT month FROM chain_head LOOP
    PERFORM ensure_month_partition(m);
  END LOOP;
END
$$;

REVOKE ALL ON payload, txn_index FROM PUBLIC;
REVOKE ALL ON FUNCTION ensure_month_partition(text) FROM PUBLIC;

GRANT EXECUTE ON FUNCTION ensure_month_partition(text) TO audit_writer;
GRANT INSERT ON payload TO audit_writer;
GRANT SELECT, INSERT, UPDATE ON txn_index TO audit_writer;
GRANT SELECT ON payload, txn_index TO audit_reader;
