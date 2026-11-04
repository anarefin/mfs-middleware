-- USSD menus as data (spec 9.2): immutable versions of a JSON tree, and per-MNO activations with
-- a scheduled go-live. The live version for an MNO is its latest activation whose go-live has
-- passed; a running session keeps the version it started on.
CREATE TABLE menu (
    menu_id    text        NOT NULL,
    version    integer     NOT NULL CHECK (version >= 1),
    tree       jsonb       NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (menu_id, version)
);

CREATE TABLE menu_activation (
    mno        text        NOT NULL,
    menu_id    text        NOT NULL,
    version    integer     NOT NULL,
    go_live_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (mno, go_live_at),
    FOREIGN KEY (menu_id, version) REFERENCES menu (menu_id, version)
);
