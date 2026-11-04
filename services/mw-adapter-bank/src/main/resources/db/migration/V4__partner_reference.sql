-- Stable partner reference per transaction (spec 7.3 rule 5, 4.1 "PG partner_ref"). The first
-- request for a txnId fixes the reference sent to the partner; every later request for the same
-- txnId (retry, redelivery, status inquiry) reads the stored one, so the partner recognises a
-- duplicate and never applies the top-up twice. The reference is not personal data.
CREATE TABLE partner_reference (
    txn_id      text        PRIMARY KEY,
    partner_id  text        NOT NULL,
    partner_ref text        NOT NULL UNIQUE,
    created_at  timestamptz NOT NULL
);
