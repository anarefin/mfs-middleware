-- S7: the final response of spec 7.3 rule 2 is never stored: a repeated key is answered from the
-- live transaction row (deviation recorded in CLAUDE.md), so the always-null column goes.
ALTER TABLE idempotency DROP COLUMN response;
