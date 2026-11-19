-- Merchant request nonces (ticket 99, FRS PLT-008; repository/MerchantNonceRepository): the signed
-- X-Nonce of a session create is accepted once per merchant while its Date can still be accepted;
-- older rows are deleted by the next request. A nonce is not personal data.
CREATE TABLE merchant_nonce (
    merchant_id text        NOT NULL,
    nonce       text        NOT NULL,
    seen_at     timestamptz NOT NULL,
    PRIMARY KEY (merchant_id, nonce)
);
CREATE INDEX merchant_nonce_seen_at ON merchant_nonce (seen_at);
