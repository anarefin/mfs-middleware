-- The checkout OTP is removed from an outbox row in the statement that marks it sent (review A8,
-- messaging/OutboxRelay.markSent), so it never stays in clear for the 7 day purge. Rows relayed
-- before that change lose theirs now.
UPDATE outbox SET payload = payload #- '{params,otp}'
 WHERE topic = 'mw.notify.request.v1' AND payload->'params' ? 'otp' AND sent_at IS NOT NULL;
