-- REC-05 (M9 review): an approved recon_exception action is kept until mw-recon has recorded it. The row is written
-- in the decision transaction (with the ticket 37 message); scheduler/ReconActionRetryJob re-sends it while
-- mw-recon is unreachable and deletes it once mw-recon answered (recorded or refused).
CREATE TABLE recon_action_pending (
  change_request_id  uuid PRIMARY KEY,
  exception_id       uuid NOT NULL,
  action             text NOT NULL,
  checker_id         text NOT NULL,
  created_at         timestamptz NOT NULL,
  attempts           integer NOT NULL DEFAULT 0,
  last_attempt_at    timestamptz
);
CREATE INDEX recon_action_pending_due_idx ON recon_action_pending (last_attempt_at);
