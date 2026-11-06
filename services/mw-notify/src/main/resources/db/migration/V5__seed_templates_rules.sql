-- MVP templates (bn/en) and rules (spec 10.3). Later edits come through the admin console with
-- maker-checker; these are the initial rows. Params come from service/RuleEngine: amount, fee,
-- counterparty (masked MSISDN), txnId, coreRef.

insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('TXN_P2P_SENT', 'SMS', 'en', 1,
   'You have sent Tk {{amount}} to {{counterparty}}. Fee Tk {{fee}}. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_P2P_SENT', 'SMS', 'bn', 1,
   'আপনি {{counterparty}} নম্বরে ৳{{amount}} পাঠিয়েছেন। ফি ৳{{fee}}। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_P2P_RECEIVED', 'SMS', 'en', 1,
   'You have received Tk {{amount}} from {{counterparty}}. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_P2P_RECEIVED', 'SMS', 'bn', 1,
   'আপনি {{counterparty}} নম্বর থেকে ৳{{amount}} পেয়েছেন। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_TOPUP_SUCCESS', 'SMS', 'en', 1,
   'Recharge of Tk {{amount}} to {{counterparty}} is successful. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_TOPUP_SUCCESS', 'SMS', 'bn', 1,
   '{{counterparty}} নম্বরে ৳{{amount}} রিচার্জ সফল হয়েছে। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_TOPUP_RECEIVED', 'SMS', 'en', 1,
   'Your number has been recharged with Tk {{amount}} by {{counterparty}}. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_TOPUP_RECEIVED', 'SMS', 'bn', 1,
   '{{counterparty}} আপনার নম্বরে ৳{{amount}} রিচার্জ করেছেন। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z');

insert into rule (rule_id, condition, template_code, recipient, sort_order) values
  ('p2p-sent', 'status == ''COMPLETED'' and txnType == ''P2P''', 'TXN_P2P_SENT', 'DEBIT_PARTY', 10),
  ('p2p-received', 'status == ''COMPLETED'' and txnType == ''P2P'' and creditPartyId != debitPartyId',
   'TXN_P2P_RECEIVED', 'CREDIT_PARTY', 20),
  ('topup-success', 'status == ''COMPLETED'' and txnType == ''TOPUP''', 'TXN_TOPUP_SUCCESS', 'DEBIT_PARTY', 30),
  ('topup-received', 'status == ''COMPLETED'' and txnType == ''TOPUP'' and creditPartyId != debitPartyId',
   'TXN_TOPUP_RECEIVED', 'CREDIT_PARTY', 40);
