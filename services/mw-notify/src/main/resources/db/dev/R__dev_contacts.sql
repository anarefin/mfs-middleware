-- DEV / MVP STACK ONLY: registered email addresses of the seeded test users (the same addresses as
-- their Keycloak accounts), so EMAIL rules have someone to send to. Applied only when Flyway's
-- locations include classpath:db/dev (deploy: MW_NOTIFY_FLYWAY_LOCATIONS in compose.yml); a real
-- environment gets its contacts from the admin console (WP23).
insert into contact (party_id, email) values
  ('01711000001', 'customer01@example.test'),
  ('01711000002', 'customer02@example.test'),
  ('01811000001', 'agent01@example.test'),
  ('01911000001', 'merchant01@example.test'),
  ('01911000002', 'merchant02@example.test'),
  -- the API suite's run customers (mw-seeder, tests/api/run_customer.mjs) play customer01
  ('01714010001', 'customer01@example.test'),
  ('01714020001', 'customer01@example.test'),
  ('01714030001', 'customer01@example.test'),
  ('01714040001', 'customer01@example.test'),
  ('01714050001', 'customer01@example.test'),
  ('01714060001', 'customer01@example.test'),
  ('01714070001', 'customer01@example.test'),
  ('01714080001', 'customer01@example.test'),
  ('01714090001', 'customer01@example.test'),
  ('01714100001', 'customer01@example.test'),
  ('01714110001', 'customer01@example.test'),
  ('01714120001', 'customer01@example.test'),
  ('01714130001', 'customer01@example.test'),
  ('01714140001', 'customer01@example.test'),
  ('01714150001', 'customer01@example.test'),
  ('01714160001', 'customer01@example.test'),
  ('01714170001', 'customer01@example.test'),
  ('01714180001', 'customer01@example.test'),
  ('01714190001', 'customer01@example.test'),
  ('01714200001', 'customer01@example.test'),
  ('01714210001', 'customer01@example.test'),
  ('01714220001', 'customer01@example.test'),
  ('01714230001', 'customer01@example.test'),
  ('01714240001', 'customer01@example.test')
on conflict (party_id) do update set email = excluded.email, updated_at = now();
