-- Internal query API (ticket 35, spec 14.3, 15.2, 16.3): search by MSISDN through a keyed hash
-- (HMAC-SHA256 under a key from OpenBao, util/SearchHash), so the raw initiator_id is never
-- indexed; partner reference, status and date; and the partner calls of the saga timeline.
--
-- initiator_hash is nullable: rows written before V8 have none (dev data only; they are still
-- found by txnId, partner reference, status and date).
ALTER TABLE transaction ADD COLUMN initiator_hash bytea;
CREATE INDEX transaction_initiator_hash_idx ON transaction (initiator_hash, created_at DESC);
CREATE INDEX transaction_created_idx ON transaction (created_at DESC);
CREATE INDEX transaction_partner_ref_idx ON transaction (partner_ref) WHERE partner_ref IS NOT NULL;

-- One row per request sent to a partner adapter (PAY, STATUS_QUERY) and per partner callback,
-- with the answer once it came (spec 16.2 partner_call). Read by the saga timeline only.
CREATE TABLE partner_call (
  id           bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  request_id   text        NOT NULL UNIQUE,
  txn_id       uuid        NOT NULL,
  operation    text        NOT NULL,
  family       text        NOT NULL,
  partner_id   text,
  sent_at      timestamptz NOT NULL,
  outcome      text,
  partner_ref  text,
  error_code   text,
  answered_at  timestamptz
);
CREATE INDEX partner_call_txn_idx ON partner_call (txn_id, id);
