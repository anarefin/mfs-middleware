-- Notification rules edited in the console (ticket 133c, FRS NOT-01) are content versions too:
-- configKey notify-rule/<ruleId>, value {ruleId, version, eventType, outcome, customerType,
-- recipient, channels, templateCode, enabled}, broadcast as configType NOTIFY_RULE.
ALTER TABLE content_version DROP CONSTRAINT content_version_config_type_check;
ALTER TABLE content_version ADD CONSTRAINT content_version_config_type_check
  CHECK (config_type IN ('USSD_MENU', 'TEMPLATE', 'NOTIFY_RULE'));
