# 84: Copy the route-as-config engine to mw-adapter-telco and mw-adapter-bank

**What to build:** Copy the `partner/` engine, the Kamelet library and the Spring Kafka side from mw-adapter-billpay (tickets 82, 83) into
mw-adapter-telco and mw-adapter-bank. The copies are identical apart from the package, as the `partner/` rule requires; check with `diff -r` after the package
rename. Each adapter publishes `mw.partner.route.status.v1` for its own `family`.

- **Copy everything from 82:** the observability and health config (`camel-observation`, `camel-micrometer`, health checks, tracing and message history
  off in production), the Camel vault function for secrets, the `routeConfiguration`, the no-redelivery-for-PAY rule and the Kamelet lint test.
  Check each Camel feature against the current Camel 4.x docs (Context7) before use.
- **Migrate the seeds:** `ocs` (telco top-up) and `c2wbank` (bank, async callback) become YAML routes.
- **Bank's async-callback flow:** the inbound callback keeps going through the existing controller to `mw.partner.inbound.v1`; only the outbound call moves to the route.
  Add `deploy/partner-routes/examples/rest-async-callback.yaml`.
- **Service-layer rules stay in Java:** telco's MSISDN credit party, and bank's `ADD_MONEY_BANK`/`C2W` and `BANK_ACCOUNT`/`WALLET` checks. They are channel rules, not partner rules.
- **Drop the legacy fields:** remove the `template`/`operations` fields from `PartnerConfig` in all three adapters, plus the derived schema and admin-api
  `UPSERT` (old action), once no seed uses them. Change the contract (owner mw-admin-api) in the same commit.
- **mw-admin-api's dev seeds:** `partner-seeds/dev/` gets all three partners (`ocs`, `billagg`, `c2wbank`). Today only `ocs` is seeded, so admin-api refuses change requests for `billagg`.
- **Docs:** update `CLAUDE.md` in each adapter.

**Blocked by:** 83

**Status:** open

- [ ] `diff -r` of the three `partner/` trees (after normalising the package) is empty; the Kamelet folders are identical
- [ ] `./gradlew :mw-adapter-telco:build :mw-adapter-bank:build :mw-adapter-billpay:build :mw-admin-api:build :contractsLint` are green with Docker
- [ ] With the stack up: the top-up, bill-pay, add-money and C2W API suite folders pass; `scripts/tester-e2e.sh @app` passes
- [ ] There are no `security.partner_config_refused` logs for other families' records after a change request
- [ ] The "PAY is sent exactly once" test from 82 passes for a telco top-up and a bank C2W
