-- Web checkout OTP SMS (spec 9.3, ticket 31): mw-checkout sends NotificationRequest
-- templateCode CHECKOUT_OTP, priority OTP, params otp, amount (taka, 2 decimals), merchant.
-- Version 6.1 (not 6) so it cannot collide with another ticket's V6.
insert into template (code, channel, locale, version, body, sender_id, valid_from) values
  ('CHECKOUT_OTP', 'SMS', 'en', 1,
   'Your upay code is {{otp}} to pay Tk {{amount}} to {{merchant}}. Valid 2 minutes. Never share it.', 'upay', '2026-01-01T00:00:00Z'),
  ('CHECKOUT_OTP', 'SMS', 'bn', 1,
   'আপনার upay কোড {{otp}}। {{merchant}}-কে ৳{{amount}} পরিশোধের জন্য। ২ মিনিট বৈধ। কাউকে জানাবেন না।', 'upay', '2026-01-01T00:00:00Z');
