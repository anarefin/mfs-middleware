-- Ops alert when a transaction enters MANUAL_REVIEW (FRS PLT-024, ticket 96a). mw-orchestrator
-- publishes the MANUAL_REVIEW status on mw.txn.status.v1 as for every state; these rules send it
-- to operations, not to a party: recipient OPS = the addresses in mw.notify.ops.{msisdn,email}
-- (service/RuleEngine; an unset address skips that channel's rule), texts in mw.notify.ops.locale.
-- Params: txnId, txnType, amount, errorCode ("-" when the status carries none), partner if any.
-- The customer's own "processing" SMS (V7) is unchanged.

alter table rule drop constraint rule_recipient_check;
alter table rule add constraint rule_recipient_check
  check (recipient in ('INITIATOR', 'DEBIT_PARTY', 'CREDIT_PARTY', 'OPS'));

insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('OPS_MANUAL_REVIEW', 'SMS', 'en', 1,
   'Bits ops: {{txnType}} of Tk {{amount}} needs manual review. Reason {{errorCode}}. TrxID {{txnId}}',
   'Bits', '2026-01-01T00:00:00Z'),
  ('OPS_MANUAL_REVIEW', 'SMS', 'bn', 1,
   'Bits ops: ৳{{amount}} এর {{txnType}} লেনদেন ম্যানুয়াল রিভিউ প্রয়োজন। কারণ {{errorCode}}। TrxID {{txnId}}',
   'Bits', '2026-01-01T00:00:00Z');

insert into template (code, channel, locale, version, subject, body, html_body, valid_from) values
  ('OPS_MANUAL_REVIEW', 'EMAIL', 'en', 1,
   'Bits ops: manual review needed, TrxID {{txnId}}',
   E'A transaction entered MANUAL_REVIEW and needs an operator.\n\nType: {{txnType}}\nAmount: Tk {{amount}}\nReason: {{errorCode}}\nTrxID: {{txnId}}\n\nResolve it in the ops console (force commit, force release or inquire).',
   '<html><body><p>A transaction entered MANUAL_REVIEW and needs an operator.</p><table>'
     || '<tr><td>Type</td><td>{{txnType}}</td></tr><tr><td>Amount</td><td>Tk {{amount}}</td></tr>'
     || '<tr><td>Reason</td><td>{{errorCode}}</td></tr><tr><td>TrxID</td><td>{{txnId}}</td></tr></table>'
     || '<p>Resolve it in the ops console (force commit, force release or inquire).</p></body></html>',
   '2026-01-01T00:00:00Z'),
  ('OPS_MANUAL_REVIEW', 'EMAIL', 'bn', 1,
   'Bits ops: ম্যানুয়াল রিভিউ প্রয়োজন, TrxID {{txnId}}',
   E'একটি লেনদেন MANUAL_REVIEW অবস্থায় গেছে, অপারেটরের সিদ্ধান্ত প্রয়োজন।\n\nধরন: {{txnType}}\nপরিমাণ: ৳{{amount}}\nকারণ: {{errorCode}}\nTrxID: {{txnId}}\n\nঅপস কনসোলে সমাধান করুন (force commit, force release বা inquire)।',
   '<html><body><p>একটি লেনদেন MANUAL_REVIEW অবস্থায় গেছে, অপারেটরের সিদ্ধান্ত প্রয়োজন।</p><table>'
     || '<tr><td>ধরন</td><td>{{txnType}}</td></tr><tr><td>পরিমাণ</td><td>৳{{amount}}</td></tr>'
     || '<tr><td>কারণ</td><td>{{errorCode}}</td></tr><tr><td>TrxID</td><td>{{txnId}}</td></tr></table>'
     || '<p>অপস কনসোলে সমাধান করুন (force commit, force release বা inquire)।</p></body></html>',
   '2026-01-01T00:00:00Z');

insert into rule (rule_id, condition, template_code, recipient, channel, sort_order) values
  ('ops-manual-review-sms', 'status == ''MANUAL_REVIEW''', 'OPS_MANUAL_REVIEW', 'OPS', 'SMS', 900),
  ('ops-manual-review-email', 'status == ''MANUAL_REVIEW''', 'OPS_MANUAL_REVIEW', 'OPS', 'EMAIL', 901);
