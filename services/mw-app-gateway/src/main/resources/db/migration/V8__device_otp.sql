-- Device binding OTP (spec 9.1, 18.6; review A5): binding a device needs an OTP sent by SMS to the
-- token's wallet through mw-notify. One row per wallet: the current OTP (hash only, bound to the
-- wallet and the device it was asked for), its checks (each reserved atomically before the guess
-- is compared) and the sends of the current window. The OTP itself is never stored.
CREATE TABLE app_device_otp (
  wallet_id    text        PRIMARY KEY,         -- wallet MSISDN of the token
  device_id    text        NOT NULL,            -- X-Device-Id the OTP was asked for
  otp_hash     text,                            -- hex SHA-256 of wallet:device:otp; null once spent or void
  locale       text        NOT NULL,            -- of the OTP SMS, reused for the "device bound" SMS
  expires_at   timestamptz NOT NULL,
  attempts     integer     NOT NULL DEFAULT 0,  -- checks of the current OTP
  sends        integer     NOT NULL,            -- OTPs sent since window_start
  window_start timestamptz NOT NULL,
  updated_at   timestamptz NOT NULL
);
