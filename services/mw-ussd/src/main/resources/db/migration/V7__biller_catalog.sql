-- Ticket 113b: this service's projection of mw-admin-api's biller catalogue (mw.config.changed.v1
-- BILLER, key biller/<code>), stored by messaging/ConfigChangedListener through service/BillerCatalog.
-- value is the record's value as published (no personal data); config_version is the admin version,
-- so a redelivered or older record never replaces a newer one.
CREATE TABLE biller_catalog (
    code           text        PRIMARY KEY,
    config_version bigint      NOT NULL,
    value          jsonb       NOT NULL,
    updated_at     timestamptz NOT NULL DEFAULT now()
);
