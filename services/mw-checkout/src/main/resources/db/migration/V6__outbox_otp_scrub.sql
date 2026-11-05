-- The checkout OTP is removed from relayed outbox rows within seconds (review A8,
-- messaging/OutboxOtpScrubber): this partial index holds only the rows that still carry one, so
-- the scrub every few seconds never scans the 7 days of sent rows.
CREATE INDEX outbox_otp_unscrubbed_idx ON outbox (id) WHERE payload->'params' ? 'otp';

-- Rows already relayed before this change lose their OTP now.
UPDATE outbox SET payload = payload #- '{params,otp}'
 WHERE payload->'params' ? 'otp' AND sent_at IS NOT NULL;
