-- First-PIN login OTP of mw-auth (ticket 69, spec 18.7 step 4): a wallet without a PIN signs in
-- with this OTP and must set a PIN (templateCode LOGIN_OTP, priority OTP, param otp).
insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('LOGIN_OTP', 'SMS', 'en', 1,
   'Your Bits code to sign in and set your PIN is {{otp}}. Valid 2 minutes. Never share it.', 'Bits', '2026-01-01T00:00:00Z'),
  ('LOGIN_OTP', 'SMS', 'bn', 1,
   'সাইন ইন করে পিন সেট করতে আপনার Bits কোড {{otp}}। ২ মিনিট বৈধ। কাউকে জানাবেন না।', 'Bits', '2026-01-01T00:00:00Z');
