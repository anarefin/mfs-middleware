-- Callback replay nonces (ticket 99, FRS PLT-008/PLT-014; partner/auth/CallbackNonces): a signed
-- callback's X-Nonce is accepted once per partner while its timestamp can still be accepted;
-- older rows are deleted by the next callback. A nonce is not personal data.
CREATE TABLE callback_nonce (
    partner_id text        NOT NULL,
    nonce      text        NOT NULL,
    seen_at    timestamptz NOT NULL,
    PRIMARY KEY (partner_id, nonce)
);
CREATE INDEX callback_nonce_seen_at ON callback_nonce (seen_at);
