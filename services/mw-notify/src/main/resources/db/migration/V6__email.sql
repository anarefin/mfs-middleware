-- Email (spec 10.2, ticket 25): HTML + text templates, one message row per requested channel and
-- the parties' registered email addresses.

-- An EMAIL template: subject and body (the text part) as before, html_body the HTML part
-- (optional: text only when null). Params are HTML-escaped in html_body.
alter table template add column html_body text;

-- A request for SMS and EMAIL is one row per channel: the key is (message_id, channel), the
-- messageId stays the request's idempotency key (10.1).
alter table segment drop constraint segment_message_id_fkey;
alter table attempt drop constraint attempt_message_id_fkey;
alter table message drop constraint message_pkey;
alter table message add primary key (message_id, channel);
alter table message add column recipient_email text;
alter table message add column subject text;
alter table message add column html_body text;

-- Only SMS has parts.
alter table segment add column channel text not null default 'SMS' check (channel = 'SMS');
alter table segment add foreign key (message_id, channel) references message (message_id, channel);

alter table attempt add column channel text not null default 'SMS';
alter table attempt drop constraint attempt_pkey;
alter table attempt add primary key (message_id, channel, attempt_no);
alter table attempt add foreign key (message_id, channel) references message (message_id, channel);

-- A party's registered email address (party id = wallet MSISDN, merchant id, ...). EMAIL rules
-- send only to parties listed here. Maintained by the admin console later (WP23); seeded in dev
-- only (db/dev, MW_NOTIFY_DEV_SEED).
create table contact (
  party_id   text        primary key,
  email      text        not null,
  updated_at timestamptz not null default now()
);
