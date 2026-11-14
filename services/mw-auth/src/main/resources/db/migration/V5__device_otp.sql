-- The device binding OTP (ticket 68; moved from mw-app-gateway's app_device_otp, review A5): one
-- current OTP per wallet, stored as a hash bound to wallet and device (null once spent or void),
-- with its checks and the sends of the current window. Every change is one conditional statement
-- (repository/DeviceOtpRepository). Dev data is not migrated: a stack is reseeded.
CREATE TABLE device_otp (
  wallet_id     text        PRIMARY KEY,
  device_id     text        NOT NULL,
  otp_hash      text,
  locale        text        NOT NULL,
  expires_at    timestamptz NOT NULL,
  attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  sends         integer     NOT NULL DEFAULT 1 CHECK (sends >= 0),
  window_start  timestamptz NOT NULL,
  updated_at    timestamptz NOT NULL DEFAULT now()
);
