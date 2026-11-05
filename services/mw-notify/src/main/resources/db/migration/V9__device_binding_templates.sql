-- Device binding SMS of mw-app-gateway (spec 9.1, 18.6; review A5): the binding OTP
-- (templateCode DEVICE_BIND_OTP, priority OTP, param otp) and the notice after a device was bound
-- (DEVICE_BOUND, priority TXN, no params).
insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('DEVICE_BIND_OTP', 'SMS', 'en', 1,
   'Your upay code to bind a new device is {{otp}}. Valid 2 minutes. Never share it.', 'upay', '2026-01-01T00:00:00Z'),
  ('DEVICE_BIND_OTP', 'SMS', 'bn', 1,
   'নতুন ডিভাইস যুক্ত করতে আপনার upay কোড {{otp}}। ২ মিনিট বৈধ। কাউকে জানাবেন না।', 'upay', '2026-01-01T00:00:00Z'),
  ('DEVICE_BOUND', 'SMS', 'en', 1,
   'A new device was bound to your upay wallet. If this was not you, call upay support now.', 'upay', '2026-01-01T00:00:00Z'),
  ('DEVICE_BOUND', 'SMS', 'bn', 1,
   'আপনার upay ওয়ালেটে একটি নতুন ডিভাইস যুক্ত হয়েছে। এটি আপনি না করলে এখনই upay সাপোর্টে কল করুন।', 'upay', '2026-01-01T00:00:00Z');
