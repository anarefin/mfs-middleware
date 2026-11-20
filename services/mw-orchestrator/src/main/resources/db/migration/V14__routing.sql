-- Routing (ticket 98, FRS PLT-040..043). V13 is the Java migration WalletPartyMigration,
-- so this is V14. The orchestrator owns these rows; mw-admin-api publishes changes on
-- mw.config.changed.v1 and this service applies them. A rule names the connector
-- (primary_partner_id) and the operator, biller or bank a channel may ask for.
CREATE TABLE routing_rule (
  id                 text        PRIMARY KEY,
  txn_type           text        NOT NULL,
  product_code       text,
  biller_code        text,
  bank_code          text,
  operator_code      text,
  amount_min_minor   bigint,
  amount_max_minor   bigint,
  channel            text,
  primary_partner_id text        NOT NULL,
  fallbacks          text[]      NOT NULL DEFAULT '{}',
  priority           int         NOT NULL DEFAULT 0,
  enabled            boolean     NOT NULL DEFAULT true,
  version            int         NOT NULL DEFAULT 1
);

CREATE TABLE msisdn_prefix (
  prefix  text    PRIMARY KEY,
  mno     text    NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  version int     NOT NULL DEFAULT 1
);

CREATE TABLE mnp_override (
  msisdn  text    PRIMARY KEY,
  mno     text    NOT NULL,
  enabled boolean NOT NULL DEFAULT true,
  version int     NOT NULL DEFAULT 1
);

CREATE TABLE maintenance_message (
  id           text        PRIMARY KEY,
  partner_id   text,
  biller_code  text,
  message_en   text        NOT NULL,
  message_bn   text        NOT NULL,
  valid_from   timestamptz,
  valid_until  timestamptz,
  enabled      boolean     NOT NULL DEFAULT true,
  version      int         NOT NULL DEFAULT 1,
  CHECK (partner_id IS NOT NULL OR biller_code IS NOT NULL)
);

-- 017/013 GP, 018 Robi, 015 Teletalk, 019/014 Banglalink (FRS PLT-042). The MNO id is the
-- one the channels send (gp, robi, teletalk, banglalink).
INSERT INTO msisdn_prefix (prefix, mno) VALUES
  ('017', 'gp'),
  ('013', 'gp'),
  ('018', 'robi'),
  ('015', 'teletalk'),
  ('019', 'banglalink'),
  ('014', 'banglalink');

-- Today's connectors, so a request that already names its partner keeps that partner:
-- every MNO is served by ocs, billers by billagg, add-money by c2wbank.
INSERT INTO routing_rule (id, txn_type, operator_code, primary_partner_id, priority) VALUES
  ('topup-gp', 'TOPUP', 'gp', 'ocs', 10),
  ('topup-robi', 'TOPUP', 'robi', 'ocs', 10),
  ('topup-banglalink', 'TOPUP', 'banglalink', 'ocs', 10),
  ('topup-teletalk', 'TOPUP', 'teletalk', 'ocs', 10);
INSERT INTO routing_rule (id, txn_type, primary_partner_id, priority) VALUES
  ('billpay', 'BILL_PAY', 'billagg', 0);
INSERT INTO routing_rule (id, txn_type, bank_code, primary_partner_id, priority) VALUES
  ('add-money-c2wbank', 'ADD_MONEY_BANK', 'c2wbank', 'c2wbank', 0);
