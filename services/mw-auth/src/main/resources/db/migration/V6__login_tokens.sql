-- Login and tokens (ticket 69, spec 18.7, D21).
-- The OTP's purpose: BIND (device binding) or LOGIN (first PIN: a wallet without a PIN logs in with
-- an OTP and must set one). One current OTP per wallet, as before; an OTP of one purpose is never
-- accepted for the other.
ALTER TABLE device_otp ADD COLUMN purpose text NOT NULL DEFAULT 'BIND' CHECK (purpose IN ('BIND', 'LOGIN'));

-- A refresh token's family is the session id (`sid` claim); the row also keeps how the session was
-- authenticated (`amr`, comma separated), so a refreshed access token carries the same methods.
ALTER TABLE refresh_token ADD COLUMN amr text NOT NULL DEFAULT 'pin,device';
