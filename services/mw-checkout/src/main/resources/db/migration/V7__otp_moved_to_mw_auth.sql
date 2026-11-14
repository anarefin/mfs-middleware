-- The hosted page's OTP moved to mw-auth (ticket 72, spec 9.3): mw-auth sends and checks it (purpose
-- CHECKOUT, bound to the session id). The session keeps only the paying wallet and its OTP send count
-- (otp_sends, the per-session limit); the OTP hash, its expiry and its checks are mw-auth's now.
alter table session drop column otp_hash;
alter table session drop column otp_expires_at;
alter table session drop column otp_attempts;
