-- Inbound ATM calls (ticket 120a, FRS UC-17, PLT-025): one row per (operation, the switch's
-- atmTxnRef). A repeated call finds its row and gets the first answer again. Publishing to
-- mw.partner.inbound.v1 waits until ticket 120b extends that contract. A cash-out's txnId is
-- minted by VERIFY and reused by CONFIRM and REVERSAL of the same atmTxnRef.
-- No token, no MSISDN: references only.
CREATE TABLE atm_inbound (
    operation   text        NOT NULL,
    atm_txn_ref text        NOT NULL,
    txn_id      text        NOT NULL,
    inbound_id  text        NOT NULL,
    received_at timestamptz NOT NULL,
    PRIMARY KEY (operation, atm_txn_ref)
);
