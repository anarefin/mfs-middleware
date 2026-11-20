-- WS-Security nonces of the inbound ATM API (ticket 120b2, security fix on 120b1, FRS PLT-008).
-- Their own table, apart from the partner callbacks' callback_nonce (V7): a callback purges
-- callback_nonce with its own, shorter window, which freed ATM nonces still inside the 600 s ATM
-- window. Each row carries its own expiry and only rows past it are purged. A nonce is not
-- personal data.
CREATE TABLE atm_ws_nonce (
    partner_id text        NOT NULL,
    nonce      text        NOT NULL,
    expires_at timestamptz NOT NULL,
    PRIMARY KEY (partner_id, nonce)
);
CREATE INDEX atm_ws_nonce_expires_at ON atm_ws_nonce (expires_at);
