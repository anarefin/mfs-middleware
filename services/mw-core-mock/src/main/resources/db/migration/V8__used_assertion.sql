-- mw-auth's transaction assertions already used (spec 18.7, D22; ticket 71): one jti authorises one
-- transaction (its ValidateTransaction, HoldFunds and PostTransfer); the same jti for another txnId
-- is refused MW-AUTH-046. Purged a retention after expires_at (service/UsedAssertionPurge).
CREATE TABLE used_assertion (
  jti        text        PRIMARY KEY,
  txn_id     text        NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX used_assertion_expires_at_idx ON used_assertion (expires_at);
