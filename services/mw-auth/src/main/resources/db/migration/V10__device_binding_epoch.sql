-- Binding epoch (ticket 110b, security review of ticket 110): every binding of a device to a wallet takes the
-- next value of one sequence (a new row by the default, a re-bind by DeviceRepository.bind), so a later
-- binding always has a higher epoch whatever the services' clocks. The DEVICE_BOUND notice and the live
-- session lookup carry it; mw-notify orders bindings and push token registrations by it. Rows bound before
-- this migration get an epoch each (all lower than any new binding).
CREATE SEQUENCE device_binding_epoch_seq;

ALTER TABLE device ADD COLUMN binding_epoch bigint NOT NULL DEFAULT nextval('device_binding_epoch_seq');
ALTER SEQUENCE device_binding_epoch_seq OWNED BY device.binding_epoch;
