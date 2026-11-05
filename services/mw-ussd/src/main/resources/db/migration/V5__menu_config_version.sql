-- Menus published by the admin console (ticket 39, spec 9.2, 20.4) arrive on mw.config.changed.v1.
-- config_version is the admin's version of that menu (the event's `version`): one row per admin
-- version, so a redelivered or replayed record (the topic is compacted) is stored once and an
-- older one is ignored. Seeded menus have none. The row's own `version` (the one sessions keep)
-- is numbered here, from 100 up, so a published version never collides with a shipped seed.
ALTER TABLE menu ADD COLUMN config_version bigint;

CREATE UNIQUE INDEX menu_config_version_idx ON menu (menu_id, config_version) WHERE config_version IS NOT NULL;
