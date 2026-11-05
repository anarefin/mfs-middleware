-- Templates published by the admin console (ticket 39, spec 10.3, 20.4) arrive on
-- mw.config.changed.v1. config_version is the admin's version of that template (the event's
-- `version`): one row per admin version, so a redelivered or replayed record (the topic is
-- compacted) is stored once and an older one is ignored. Seeded rows have none. The row's own
-- `version` is numbered here (above every stored one): the highest ACTIVE version valid now is
-- the one rendered, so one insert swaps a template atomically.
alter table template add column config_version bigint;

create unique index template_config_version_idx on template (code, channel, locale, config_version)
  where config_version is not null;
