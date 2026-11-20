-- Biller amount rules (ticket 113c, FRS FL-04/FL-05): this service's read projection of the
-- mw-admin-api biller catalogue (mw.config.changed.v1, BILLER, key biller/<code>). Only what the
-- orchestrator enforces is kept: the amount rule and its bounds in poisha.
CREATE TABLE biller_rule (
  code             text   PRIMARY KEY,
  amount_rule      text   NOT NULL CHECK (amount_rule IN ('FIXED', 'MINIMUM', 'OPEN')),
  min_amount_minor bigint,
  max_amount_minor bigint,
  version          bigint NOT NULL
);
