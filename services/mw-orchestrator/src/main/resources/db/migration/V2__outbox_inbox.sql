-- Transactional outbox and inbox (spec 6.4, 16.3, 19.3). Unqualified names: Flyway and the
-- application both run with this service's own schema as search_path.

-- A business change and its event are written in one transaction; OutboxRelay publishes
-- unsent rows in id order and stamps sent_at.
CREATE TABLE outbox (
  id           bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  topic        text   NOT NULL,
  msg_key      text   NOT NULL,
  headers      jsonb  NOT NULL,
  payload      jsonb  NOT NULL,
  created_at   timestamptz NOT NULL DEFAULT now(),
  sent_at      timestamptz
);
CREATE INDEX outbox_unsent_idx ON outbox (id) WHERE sent_at IS NULL;

-- One row per (consumer, eventId) already applied, written in the same transaction as the
-- consumer's side effect, so a redelivered event is a no-op.
CREATE TABLE inbox (
  consumer      text NOT NULL,
  event_id      text NOT NULL,
  processed_at  timestamptz NOT NULL DEFAULT now(),
  PRIMARY KEY (consumer, event_id)
);
