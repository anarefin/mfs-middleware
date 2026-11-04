-- PIN lockout (spec 9.1: "5 PIN failures -> lock, rule owned by core"). A wallet whose PIN was
-- wrong mw.coremock.pin-max-failures times in a row turns LOCKED: it can no longer pay, it can
-- still receive. pin_failures counts consecutive wrong PINs and is reset by a correct one.
ALTER TABLE wallet DROP CONSTRAINT wallet_status_check;
ALTER TABLE wallet ADD CONSTRAINT wallet_status_check
  CHECK (status IN ('ACTIVE', 'BLOCKED', 'HOLD', 'LOCKED'));
ALTER TABLE wallet ADD COLUMN pin_failures integer NOT NULL DEFAULT 0 CHECK (pin_failures >= 0);
