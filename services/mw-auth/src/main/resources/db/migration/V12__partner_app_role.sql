-- A seeder mark (dev seed pins): an organisation wallet named as a partner user logs in with app
-- role PARTNER. The core type stays ORG. The wallet projection never writes this column.
ALTER TABLE wallet ADD COLUMN app_role text
  CHECK (app_role IS NULL OR app_role = 'PARTNER');
