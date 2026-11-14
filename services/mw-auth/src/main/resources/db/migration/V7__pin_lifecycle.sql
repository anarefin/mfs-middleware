-- PIN lifecycle (ticket 70, spec 18.7, D20): lockout levels, PIN change and reset.

-- auth.lock keeps a PIN lock row after its `until` passed: its level is the history the next lock
-- escalates from (lock.created_at = when the current level was reached; the history decays after
-- mw.auth.pin.lock.decay). A reset or an ops UNLOCK deletes the row.

-- A reset: SELF (OTP on the bound device + KYC factors checked by the core: PENDING_KYC -> VERIFIED or
-- REJECTED) or CARE (approved by maker-checker in mw-admin-api: APPROVED -> VERIFIED after the
-- customer's RESET OTP); VERIFIED -> DONE when the new PIN is set. VERIFIED is new; PENDING_OTP is
-- unused.
ALTER TABLE pin_reset DROP CONSTRAINT pin_reset_status_check;
ALTER TABLE pin_reset ADD CONSTRAINT pin_reset_status_check
  CHECK (status IN ('PENDING_KYC', 'PENDING_OTP', 'APPROVED', 'VERIFIED', 'DONE', 'REJECTED', 'EXPIRED'));
ALTER TABLE pin_reset
  ADD COLUMN device_id         text,                 -- the device that opened (SELF) or took up (CARE) it
  ADD COLUMN expires_at        timestamptz NOT NULL DEFAULT now(),
  ADD COLUMN change_request_id uuid UNIQUE,          -- CARE: the mw-admin-api change request (idempotency)
  ADD COLUMN command_id        text UNIQUE,          -- SELF: VerifyCustomerFactors commandId pin-reset:<id>
  ADD COLUMN reason            text;                 -- REJECTED: the core's reason code
DROP INDEX pin_reset_one_open_per_wallet_idx;
CREATE UNIQUE INDEX pin_reset_one_open_per_wallet_idx ON pin_reset (wallet_id)
  WHERE status IN ('PENDING_KYC', 'PENDING_OTP', 'APPROVED', 'VERIFIED');

-- The OTP purpose RESET (spec 18.7 step 1).
ALTER TABLE device_otp DROP CONSTRAINT device_otp_purpose_check;
ALTER TABLE device_otp ADD CONSTRAINT device_otp_purpose_check CHECK (purpose IN ('BIND', 'LOGIN', 'RESET'));
