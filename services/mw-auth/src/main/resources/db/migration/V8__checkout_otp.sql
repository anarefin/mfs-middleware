-- The checkout OTP (ticket 72, spec 9.3): mw-checkout asks mw-auth for the hosted page's OTP, purpose
-- CHECKOUT, bound to the checkout sessionId (kept in device_id) instead of a device. Still one current
-- OTP per wallet: a checkout OTP replaces a pending BIND/LOGIN/RESET OTP of the same wallet.
ALTER TABLE device_otp DROP CONSTRAINT device_otp_purpose_check;
ALTER TABLE device_otp ADD CONSTRAINT device_otp_purpose_check CHECK (purpose IN ('BIND', 'LOGIN', 'RESET', 'CHECKOUT'));
