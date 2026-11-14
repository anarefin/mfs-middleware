-- Ticket 68: device binding and the binding OTP moved to mw-auth (auth.device, auth.device_otp).
-- The gateway keeps no device data (it asks mw-auth and caches in Valkey). Dev data is not
-- migrated: a stack is reseeded and devices are bound again.
DROP TABLE IF EXISTS app_device_otp;
DROP TABLE IF EXISTS app_device;
