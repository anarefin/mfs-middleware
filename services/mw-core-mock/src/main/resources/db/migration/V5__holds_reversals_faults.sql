-- Full command set (ticket 14): reversal entries, one reversal per original transaction, ordering of
-- the command log (QueryTransaction asks for the latest outcome) and delayed outbox rows (fault
-- injection: a delayed reply is published only once available_at has passed).

ALTER TABLE entry DROP CONSTRAINT entry_kind_check;
ALTER TABLE entry ADD CONSTRAINT entry_kind_check CHECK (kind IN ('TRANSFER', 'FEE', 'REVERSAL'));

CREATE TABLE reversal (
  original_txn_id  text PRIMARY KEY,
  reversal_txn_id  text NOT NULL,
  created_at       timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE command_log ADD COLUMN seq bigint GENERATED ALWAYS AS IDENTITY;
CREATE INDEX command_log_txn_idx ON command_log (txn_id, seq);
CREATE INDEX hold_txn_idx ON hold (txn_id);

ALTER TABLE outbox ADD COLUMN available_at timestamptz NOT NULL DEFAULT now();
