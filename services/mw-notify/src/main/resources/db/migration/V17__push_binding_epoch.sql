-- Push token revocation by binding epoch (ticket 110b, security review of ticket 110): mw-auth's binding
-- epoch (one strictly increasing sequence over every binding of any device to any wallet) replaces
-- mw-notify's own clock as the order of bindings and registrations, so clock skew between mw-app-gateway,
-- mw-auth and mw-notify can no longer let a late registration of an older binding through.
--   push_device.binding_epoch: the binding the registration came from (null: a row stored before V17,
--   which follows V16__ops_manual_review_alert).
--   push_device_binding: one row per (wallet, device) binding mw-notify heard of (mw-auth's DEVICE_BOUND
--   notice, param bindingEpoch) with its newest epoch; a registration older than a binding of its wallet or
--   of its device is refused. bound_at stays when the notice was accepted (information only); rows of V14
--   have no epoch and are ignored by the check.
alter table push_device add column binding_epoch bigint;

alter table push_device_binding add column binding_epoch bigint;
alter table push_device_binding drop constraint push_device_binding_pkey;
alter table push_device_binding add primary key (wallet_id, device_id);
