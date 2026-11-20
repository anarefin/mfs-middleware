-- Ticket 121b (security review, replay): every NPSB inbound credit accepted, kept for good (never
-- purged, unlike inbox), so a validly signed pacs.008 re-sent at any time credits once. Dedup key:
-- the debtor's participant and the switch's EndToEndId (PLT-014).
CREATE TABLE npsb_inbound (
  debtor_agent   text NOT NULL,
  end_to_end_id  text NOT NULL,
  txn_id         text NOT NULL,
  received_at    timestamptz NOT NULL,
  PRIMARY KEY (debtor_agent, end_to_end_id)
);
