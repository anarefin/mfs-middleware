-- Ticket 96b: which compensation leg a REVERSING transaction is waiting on.
-- Null for a completed-transaction reversal, which still asks the core (ReverseTransaction).
-- PARTNER_REVERSE: partner REVERSE after a refused commit, not yet confirmed.
-- HOLD_QUERY: the partner confirmed; waiting for the core to say whether the hold remains.
-- HOLD_RELEASE: the hold remains; waiting for ReleaseHold.

ALTER TABLE transaction ADD COLUMN pending_leg text;

ALTER TABLE transaction ADD CONSTRAINT transaction_pending_leg_chk
  CHECK (pending_leg IS NULL OR pending_leg IN ('PARTNER_REVERSE', 'HOLD_QUERY', 'HOLD_RELEASE'));
