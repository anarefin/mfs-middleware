-- DEV / MVP STACK ONLY: registered email addresses of the seeded test users (the same addresses as
-- their Keycloak accounts), so EMAIL rules have someone to send to. Applied only when Flyway's
-- locations include classpath:db/dev (deploy: MW_NOTIFY_FLYWAY_LOCATIONS in compose.yml); a real
-- environment gets its contacts from the admin console (WP23).
insert into contact (party_id, email) values
  ('01711000001', 'customer01@example.test'),
  ('01711000002', 'customer02@example.test'),
  ('01811000001', 'agent01@example.test'),
  ('01911000001', 'merchant01@example.test'),
  ('01911000002', 'merchant02@example.test')
on conflict (party_id) do update set email = excluded.email, updated_at = now();
