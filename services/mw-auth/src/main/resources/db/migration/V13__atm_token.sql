-- The ATM cash-out token (ticket 120b2, FRS FL-11, UC-17): one live token per wallet (a new one
-- replaces it), stored only as an HMAC under the mw-crypto HMAC_ATMTOKEN key (mac_kid = the key
-- version) over the wallet, the amount, the currency and the 6 digits, so it is bound to wallet +
-- amount and never at rest in clear. mac is null once spent or void; attempts counts the checks
-- (the wrong one that uses the last voids it), as channel_otp does.
CREATE TABLE atm_token (
  wallet_id     text        PRIMARY KEY,
  mac           text,
  mac_kid       text        NOT NULL,
  amount_minor  bigint      NOT NULL CHECK (amount_minor > 0),
  currency      text        NOT NULL,
  expires_at    timestamptz NOT NULL,
  attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  issued_at     timestamptz NOT NULL
);
