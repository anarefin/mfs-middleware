-- The checkout OTP (ticket 72, spec 9.3; security review of ticket 72): mw-checkout asks mw-auth for the
-- hosted page's OTP, purpose CHECKOUT, bound to the checkout sessionId. Its own slot, never device_otp:
-- one row per wallet, purpose and session (binding_id), so a checkout OTP never replaces or uses up a
-- pending BIND/LOGIN/RESET OTP, and two sessions keep their own. Stored as a hash only (null once spent
-- or void), with its checks. The sends are counted per wallet and purpose in channel_otp_quota, apart
-- from the device OTP's quota. Expired rows of a wallet are deleted when it asks a new one.
CREATE TABLE channel_otp (
  wallet_id     text        NOT NULL,
  purpose       text        NOT NULL CHECK (purpose IN ('CHECKOUT')),
  binding_id    text        NOT NULL,
  otp_hash      text,
  locale        text        NOT NULL,
  expires_at    timestamptz NOT NULL,
  attempts      integer     NOT NULL DEFAULT 0 CHECK (attempts >= 0),
  send_no       integer     NOT NULL CHECK (send_no >= 1),
  updated_at    timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (wallet_id, purpose, binding_id)
);

CREATE TABLE channel_otp_quota (
  wallet_id     text        NOT NULL,
  purpose       text        NOT NULL CHECK (purpose IN ('CHECKOUT')),
  sends         integer     NOT NULL CHECK (sends >= 0),
  window_start  timestamptz NOT NULL,
  PRIMARY KEY (wallet_id, purpose)
);
