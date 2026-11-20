-- Ticket 107a: the customer inbox marks a sent message read. OTP rows are never listed.
alter table message add column read_at timestamptz;

create index message_inbox_idx on message (recipient_msisdn, created_at desc)
  where priority in ('TXN', 'INFO', 'PROMO') and status in ('SENT', 'DELIVERED');
