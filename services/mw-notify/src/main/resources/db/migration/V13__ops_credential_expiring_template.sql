-- The ops email of mw-admin-api's partner credential expiry alerts (ticket 131, FRS ADM-02): sent to
-- the operations mailbox 30, 7 and 1 days before a credential's notAfter (priority INFO). Params:
-- partnerId, credential (<partnerId>/<name>), type, kid, notAfter, daysLeft, threshold. No secret.
insert into template (code, channel, locale, version, subject, body, html_body, valid_from) values
  ('OPS_CREDENTIAL_EXPIRING', 'EMAIL', 'en', 1,
   'Bits: partner credential {{credential}} expires in {{daysLeft}} days',
   E'The partner credential {{credential}} ({{type}}, {{kid}}) of partner {{partnerId}} expires at {{notAfter}}, in {{daysLeft}} days ({{threshold}}-day alert).\n\nUpload the new one in the admin console (Partners > Credentials) and have it approved; see the runbook, certificate rotation.\n\nBits middleware',
   '<html><body><p>The partner credential <b>{{credential}}</b> ({{type}}, {{kid}}) of partner {{partnerId}} expires at '
     || '{{notAfter}}, in <b>{{daysLeft}} days</b> ({{threshold}}-day alert).</p><p>Upload the new one in the admin console '
     || '(Partners &gt; Credentials) and have it approved; see the runbook, certificate rotation.</p><p>Bits middleware</p></body></html>',
   '2026-01-01T00:00:00Z'),
  ('OPS_CREDENTIAL_EXPIRING', 'EMAIL', 'bn', 1,
   'Bits: পার্টনার ক্রেডেনশিয়াল {{credential}} {{daysLeft}} দিনে মেয়াদোত্তীর্ণ হবে',
   E'পার্টনার {{partnerId}}-এর ক্রেডেনশিয়াল {{credential}} ({{type}}, {{kid}}) {{notAfter}}-এ, {{daysLeft}} দিনের মধ্যে মেয়াদোত্তীর্ণ হবে ({{threshold}} দিনের সতর্কতা)।\n\nঅ্যাডমিন কনসোলে (Partners > Credentials) নতুনটি আপলোড করে অনুমোদন করান; রানবুকের সার্টিফিকেট রোটেশন দেখুন।\n\nBits middleware',
   '<html><body><p>পার্টনার {{partnerId}}-এর ক্রেডেনশিয়াল <b>{{credential}}</b> ({{type}}, {{kid}}) {{notAfter}}-এ, '
     || '<b>{{daysLeft}} দিনের</b> মধ্যে মেয়াদোত্তীর্ণ হবে ({{threshold}} দিনের সতর্কতা)।</p><p>অ্যাডমিন কনসোলে '
     || '(Partners &gt; Credentials) নতুনটি আপলোড করে অনুমোদন করান; রানবুকের সার্টিফিকেট রোটেশন দেখুন।</p><p>Bits middleware</p></body></html>',
   '2026-01-01T00:00:00Z');
