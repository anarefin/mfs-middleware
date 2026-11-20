-- Inbound remittances accepted from a partner (ticket 119, FRS UC-16 / PLT-014): one row per partner
-- reference, so a re-sent credit is answered with the same Upay reference (txnId) and published once.
-- fingerprint = SHA-256 hex of beneficiary, amount and currency: a re-send that changes them is refused
-- (no MSISDN or name is stored here). status is the adapter's view (ACCEPTED until ticket 119b reports
-- the outcome).
CREATE TABLE remittance (
    partner_id  text        NOT NULL,
    partner_ref text        NOT NULL,
    txn_id      text        NOT NULL UNIQUE,
    fingerprint text        NOT NULL,
    status      text        NOT NULL CHECK (status IN ('ACCEPTED', 'CREDITED', 'REJECTED', 'MANUAL_REVIEW')),
    received_at timestamptz NOT NULL,
    PRIMARY KEY (partner_id, partner_ref)
);
