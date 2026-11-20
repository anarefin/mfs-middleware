-- Short codes (CH-USSD-08) and the language remembered per MSISDN hash (CH-USSD-10).
-- customer_pref stores only the SHA-256 hex of the canonical MSISDN, never the number.
-- The dev codes match mw.ussd.mno.<mno>.short-codes; an account type outside the list ends the session.
CREATE TABLE short_code (
    mno            text   NOT NULL,
    code           text   NOT NULL,
    root_menu_id   text   NOT NULL,
    allowed_types  text[] NOT NULL,
    PRIMARY KEY (mno, code)
);

INSERT INTO short_code (mno, code, root_menu_id, allowed_types) VALUES
    ('gp', '*268#', 'main', '{CUSTOMER,AGENT,MERCHANT,DISTRIBUTOR,ORGANISATION}'),
    ('robi', '*269#', 'main', '{CUSTOMER,AGENT,MERCHANT,DISTRIBUTOR,ORGANISATION}'),
    ('bl', '*270#', 'main', '{CUSTOMER,AGENT,MERCHANT,DISTRIBUTOR,ORGANISATION}'),
    ('teletalk', '*271#', 'main', '{CUSTOMER,AGENT,MERCHANT,DISTRIBUTOR,ORGANISATION}');

CREATE TABLE customer_pref (
    msisdn_hash text PRIMARY KEY,
    language    text NOT NULL DEFAULT 'bn' CHECK (language IN ('bn', 'en'))
);
