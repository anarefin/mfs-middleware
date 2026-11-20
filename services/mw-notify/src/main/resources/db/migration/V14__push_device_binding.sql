-- Push token revocation tombstone (ticket 110 security review): each wallet's newest device binding as
-- mw-notify learnt it from mw-auth's DEVICE_BOUND notice. A token registration arrives on another topic
-- than the notice, so it can come in after the binding that revoked it; one stamped at or before the
-- binding is refused when it names another device of that wallet, or this device under another wallet.
create table push_device_binding (
  wallet_id text        primary key,
  device_id text        not null,
  bound_at  timestamptz not null
);

create index push_device_binding_device_idx on push_device_binding (device_id);
