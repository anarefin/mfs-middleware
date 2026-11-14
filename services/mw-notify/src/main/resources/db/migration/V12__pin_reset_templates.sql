-- The PIN lifecycle SMS of mw-auth (ticket 70, spec 18.7): the reset OTP (priority OTP, param otp),
-- the approved care reset and the PIN changed / reset notice (priority TXN, no params).
insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('PIN_RESET_OTP', 'SMS', 'en', 1,
   'Your Bits code to reset your PIN is {{otp}}. Valid 2 minutes. Never share it.', 'Bits', '2026-01-01T00:00:00Z'),
  ('PIN_RESET_OTP', 'SMS', 'bn', 1,
   'পিন রিসেট করতে আপনার Bits কোড {{otp}}। ২ মিনিট বৈধ। কাউকে জানাবেন না।', 'Bits', '2026-01-01T00:00:00Z'),
  ('PIN_RESET_APPROVED', 'SMS', 'en', 1,
   'Your Bits PIN reset was approved. Open the app, ask for a reset code and set a new PIN within 72 hours.', 'Bits', '2026-01-01T00:00:00Z'),
  ('PIN_RESET_APPROVED', 'SMS', 'bn', 1,
   'আপনার Bits পিন রিসেট অনুমোদিত হয়েছে। ৭২ ঘণ্টার মধ্যে অ্যাপে রিসেট কোড চেয়ে নতুন পিন সেট করুন।', 'Bits', '2026-01-01T00:00:00Z'),
  ('PIN_CHANGED', 'SMS', 'en', 1,
   'Your Bits PIN was changed. If this was not you, call Bits care at once.', 'Bits', '2026-01-01T00:00:00Z'),
  ('PIN_CHANGED', 'SMS', 'bn', 1,
   'আপনার Bits পিন পরিবর্তন করা হয়েছে। এটি আপনি না করে থাকলে এখনই Bits কেয়ারে ফোন করুন।', 'Bits', '2026-01-01T00:00:00Z');
