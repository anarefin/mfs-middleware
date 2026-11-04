-- Devices bound to a wallet (spec 9.1, ticket 29): the device's ECDSA P-256 public key (base64
-- X.509 SubjectPublicKeyInfo), which verifies the signature of sensitive app requests. With a
-- single active device per wallet, binding a device marks the others REPLACED.
CREATE TABLE app_device (
  wallet_id  text        NOT NULL,            -- wallet MSISDN of the token that bound it
  device_id  text        NOT NULL,            -- X-Device-Id
  subject    text        NOT NULL,            -- token sub (session key sess:{subject}:{deviceId})
  public_key text        NOT NULL,
  status     text        NOT NULL CHECK (status IN ('ACTIVE', 'REPLACED')),
  bound_at   timestamptz NOT NULL,
  updated_at timestamptz NOT NULL,
  PRIMARY KEY (wallet_id, device_id)
);
