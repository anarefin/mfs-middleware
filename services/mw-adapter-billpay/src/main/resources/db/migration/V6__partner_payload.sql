-- The raw payload store of the partner engine (ticket 97, FRS CON-05): the full request and
-- response of every partner call, masked before they get here (the record's sensitiveFields, then
-- free-text scrubbing: no PIN, no unmasked MSISDN, NID or account number), with the call's latency.
-- The audit event (mw.audit.event.v1) keeps a 4 KB excerpt and names the rows of a call by
-- payload_ref (partner-payload/<uuid>). txn_id is null for a call without a transaction (bill
-- fetch, dry-run, health probe). Rows older than mw.retention.partner-payload are purged
-- (service/PartnerPayloadRetentionService).
CREATE TABLE partner_payload (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  payload_ref  text        NOT NULL,
  txn_id       text,
  partner_id   text        NOT NULL,
  op           text        NOT NULL,
  direction    text        NOT NULL CHECK (direction IN ('REQUEST', 'RESPONSE')),
  body         text        NOT NULL,
  latency_ms   bigint      NOT NULL,
  at           timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX partner_payload_ref_idx ON partner_payload (payload_ref);
CREATE INDEX partner_payload_txn_idx ON partner_payload (txn_id) WHERE txn_id IS NOT NULL;
CREATE INDEX partner_payload_at_idx ON partner_payload (at);
