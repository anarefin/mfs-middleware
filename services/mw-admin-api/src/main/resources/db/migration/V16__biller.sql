-- Biller catalogue (ticket 113, FRS FL-04, FL-05, ADM-05). The approved catalogue: mw-admin-api
-- owns it and publishes each entry on mw.config.changed.v1 (configType BILLER, key biller/<code>);
-- mw-adapter-billpay keeps its own read projection. version is the event's monotonic version.
CREATE TABLE biller (
  code                 text        PRIMARY KEY,
  name_en              text        NOT NULL,
  name_bn              text        NOT NULL,
  category             text        NOT NULL CHECK (category IN ('UTILITY', 'OTHER')),
  primary_aggregator   text        NOT NULL,
  fallback_aggregator  text,
  fields               jsonb       NOT NULL,
  amount_rule          text        NOT NULL CHECK (amount_rule IN ('FIXED', 'MINIMUM', 'OPEN')),
  min_amount_minor     bigint,
  max_amount_minor     bigint,
  bill_month_required  boolean     NOT NULL,
  enabled              boolean     NOT NULL,
  maintenance_en       text,
  maintenance_bn       text,
  version              int         NOT NULL,
  updated_at           timestamptz NOT NULL,
  updated_by           text        NOT NULL
);
