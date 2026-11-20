-- Rules edited in the admin console (ticket 133c, FRS NOT-01) arrive on mw.config.changed.v1
-- (configType NOTIFY_RULE). One admin rule is one row per channel, rule_id admin-<ruleId>-<channel>;
-- a channel the new version drops is disabled, not deleted (rule-built message ids name the rule).
-- config_version is the admin's version: a redelivered or older record changes nothing. Seeded
-- rows have none.
alter table rule add column config_version bigint;
