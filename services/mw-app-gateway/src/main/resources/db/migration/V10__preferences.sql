-- Ticket 107a (CH-APP-08): the signed-in wallet's saved billers and favourites.
-- Stored in the clear (no mw-crypto envelope). Logs and any view that is not this
-- wallet's own response mask them (@Sensitive).

create table saved_biller (
  id           uuid        primary key,
  wallet_id    text        not null,
  biller_code  text        not null,
  account_no   text        not null,
  nickname     text,
  created_at   timestamptz not null,
  unique (wallet_id, biller_code, account_no)
);

create index saved_biller_wallet_idx on saved_biller (wallet_id, created_at desc);

create table favourite (
  id                   uuid        primary key,
  wallet_id            text        not null,
  counterparty_msisdn  text        not null,
  label                text,
  created_at           timestamptz not null,
  unique (wallet_id, counterparty_msisdn)
);

create index favourite_wallet_idx on favourite (wallet_id, created_at desc);
