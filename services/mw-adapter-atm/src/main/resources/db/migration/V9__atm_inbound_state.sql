-- Cross-operation state of an inbound ATM call (ticket 120b1, M9 review, FRS PLT-025): the atmId
-- the switch named, a fingerprint of the request (HMAC-SHA256 of atmId, MSISDN and amount, so no
-- clear MSISDN is stored) and, on a VERIFY row, the cash-out state VERIFIED -> CONFIRMED | REVERSED
-- (moved once, with a compare-and-set update). Rows of 120a carry none of these.
ALTER TABLE atm_inbound
    ADD COLUMN atm_id      text,
    ADD COLUMN fingerprint text,
    ADD COLUMN state       text;
