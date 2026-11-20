-- Ticket 117b (FRS FL-08): the signed-in wallet's linked bank accounts, each validated (AVL) by
-- mw-adapter-bank before it is stored. The account number is never stored in the clear:
-- account_ciphertext is AES-256-GCM (util/AccountCipher, wallet id as associated data),
-- account_lookup a keyed HMAC-SHA256 for the uniqueness check, account_last4 the only part shown.
-- holder_name is the first initial the bank adapter returns (masked there).

create table linked_bank_account (
  id                  uuid        primary key,
  wallet_id           text        not null,
  bank_code           text        not null,
  account_ciphertext  text        not null,
  account_lookup      text        not null,
  account_last4       char(4)     not null,
  holder_name         text        not null,
  verified_at         timestamptz not null,
  created_at          timestamptz not null,
  unique (wallet_id, account_lookup)
);

create index linked_bank_account_wallet_idx on linked_bank_account (wallet_id, created_at desc);
