-- B2B float (ticket 106, UC-07): both parties get an SMS when the transfer completes.

insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('TXN_B2B_SENT', 'SMS', 'en', 1,
   'You have sent Tk {{amount}} float to {{counterparty}}. Fee Tk {{fee}}. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_B2B_SENT', 'SMS', 'bn', 1,
   'আপনি {{counterparty}} নম্বরে ৳{{amount}} ফ্লোট পাঠিয়েছেন। ফি ৳{{fee}}। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_B2B_RECEIVED', 'SMS', 'en', 1,
   'You have received Tk {{amount}} float from {{counterparty}}. TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z'),
  ('TXN_B2B_RECEIVED', 'SMS', 'bn', 1,
   'আপনি {{counterparty}} নম্বর থেকে ৳{{amount}} ফ্লোট পেয়েছেন। TrxID {{txnId}}', 'Bits', '2026-01-01T00:00:00Z');

insert into rule (rule_id, condition, template_code, recipient, sort_order) values
  ('b2b-sent', 'status == ''COMPLETED'' and txnType == ''B2B_TRANSFER''', 'TXN_B2B_SENT', 'DEBIT_PARTY', 80),
  ('b2b-received', 'status == ''COMPLETED'' and txnType == ''B2B_TRANSFER'' and creditPartyId != debitPartyId',
   'TXN_B2B_RECEIVED', 'CREDIT_PARTY', 81);
