-- Push channel (ticket 110, FRS EXT-04, NOT-01, NOT-02, FL-14).

-- The device token registry: the token the app on a wallet's bound device registered
-- (PUT /app/v1/notifications/push-token -> mw.notify.push-token.v1). One wallet has one bound device,
-- so a registration replaces the wallet's other devices; a device re-bind (mw-auth's DEVICE_BOUND
-- notice) removes the old device's token. A token is not personal data; logs show its last 6 characters.
create table push_device (
  wallet_id  text        not null,
  device_id  text        not null,
  token      text        not null,
  platform   text        not null check (platform in ('ANDROID', 'IOS')),
  updated_at timestamptz not null,
  primary key (wallet_id, device_id)
);

create unique index push_device_token_idx on push_device (token);

-- A PUSH message row: the device token it goes to; subject is the push title, body the push text.
alter table message add column recipient_push_token text;

-- Push templates (bn/en, title = subject, body). Params from service/RuleEngine.
insert into template (code, channel, locale, version, subject, body, valid_from) values
  ('PUSH_TXN_COMPLETED', 'PUSH', 'en', 1, 'Transaction complete',
   'Your {{txnType}} of Tk {{amount}} is complete. TrxID {{txnId}}', '2026-01-01T00:00:00Z'),
  ('PUSH_TXN_COMPLETED', 'PUSH', 'bn', 1, 'লেনদেন সম্পন্ন',
   'আপনার ৳{{amount}} এর {{txnType}} সম্পন্ন হয়েছে। TrxID {{txnId}}', '2026-01-01T00:00:00Z'),
  ('PUSH_TXN_FAILED', 'PUSH', 'en', 1, 'Transaction failed',
   'Your {{txnType}} of Tk {{amount}} failed. TrxID {{txnId}}', '2026-01-01T00:00:00Z'),
  ('PUSH_TXN_FAILED', 'PUSH', 'bn', 1, 'লেনদেন ব্যর্থ',
   'আপনার ৳{{amount}} এর {{txnType}} ব্যর্থ হয়েছে। TrxID {{txnId}}', '2026-01-01T00:00:00Z');

-- FL-14: SMS always, push optional. These rules add a push to the initiator's app for completed and
-- failed financial transactions; the SMS rules stay as they are. Skipped when the wallet has no token.
insert into rule (rule_id, condition, template_code, recipient, channel, sort_order) values
  ('push-txn-completed',
   'status == ''COMPLETED'' and txnType matches ''P2P|CASH_IN|CASH_OUT|TOPUP|BILL_PAY|MERCHANT_PAY|ADD_MONEY_BANK''',
   'PUSH_TXN_COMPLETED', 'INITIATOR', 'PUSH', 200),
  ('push-txn-failed',
   '(status == ''FAILED'' or status == ''REJECTED'') and txnType matches ''P2P|CASH_IN|CASH_OUT|TOPUP|BILL_PAY|MERCHANT_PAY|ADD_MONEY_BANK''',
   'PUSH_TXN_FAILED', 'INITIATOR', 'PUSH', 201);
