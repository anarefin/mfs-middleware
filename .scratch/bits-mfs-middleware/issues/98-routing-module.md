# 98: Routing module in mw-orchestrator: rules, MNP, failover, maintenance message (FRS 3.5, PLT-032)

**What to build:** The FRS routing service (M07) as a module (`service/routing/`) inside `mw-orchestrator` (plan
default: latency-critical, one less JVM). Today the channel must name the partner: mw-app-gateway requires an explicit
`partnerId`, mw-ussd uses the session MNO, and `services/mw-adapter-billpay/CLAUDE.md` says "Routing and failover between
aggregators (FR-09) are not built". This ticket makes the orchestrator pick the connector at runtime.

**Principles:**
- Routing decides *which* partner; the adapters still own *how* (route-as-config engine, D28).
- A rule change never needs a restart; a bad rule never silently routes money (unknown partner → `MW-PTR-UNKNOWN`).
- Failover is only taken before money leaves: on an open circuit, `DOWN`/`MAINTENANCE` health (ticket 97) or a
  definite pre-send refusal; never after an ambiguous PAY (that goes to UNKNOWN and TSQ, PLT-023).

**Scope:**
- **Schema `txn`** (Flyway, next free `V<n>__routing.sql`): `routing_rule` (txnType, productCode, biller/bank/operator
  code, amount min/max poisha, channel, primary partnerId, ordered `fallbacks[]`, priority, enabled, version),
  `msisdn_prefix` (prefix → MNO; seeds 017/013 GP, 018 Robi, 015 Teletalk, 019/014 Banglalink), `mnp_override`
  (MSISDN → MNO), `maintenance_message` (partnerId/billerCode, text en/bn, from/until).
- `service/routing/RoutingService`: most-specific-rule-wins resolution; returns primary + fallbacks.
  Cache refreshed from `mw.config.changed.v1` (resource `ROUTING_RULE`, `MSISDN_PREFIX`, `MNP_OVERRIDE`,
  `MAINTENANCE_MESSAGE`) and a 30 s safety reload (PLT-041).
- Partner health: consume the health states published by ticket 97 into an in-memory view; a `DOWN`/`MAINTENANCE`
  partner is skipped; an open circuit reply (`MW-SYS-PARTNER_CIRCUIT_OPEN`) triggers the next fallback in the
  partner step of `FlowRegistry` flows `TOPUP`, `BILL_PAY`, `ADD_MONEY_BANK`.
- Channels: `partnerId` becomes optional in `contracts/api/mw-app-gateway/app.v1.openapi.yaml` and
  `mw.txn.request.v1` (owner mw-orchestrator); when present it is honoured only if a rule allows it.
- Maintenance (PLT-043): a disabled partner/biller with a message answers `MW-SYS-PARTNER_DISABLED` with the bn/en text
  in `message`/`messageBn`.
- `mw-admin-api`: resources `ROUTING_RULE`, `MSISDN_PREFIX`, `MNP_OVERRIDE`, `MAINTENANCE_MESSAGE` in the
  change-request engine (maker-checker, `PartnerConfigService` pattern), publishing `mw.config.changed.v1`
  (contract owner mw-admin-api: add the resource types). UI is ticket 132.
- Seeds: rules for today's partners (ocs for all four MNOs, billagg billers, c2wbank) so every existing API folder
  still passes unchanged.
- Docs: `services/mw-orchestrator/CLAUDE.md` (routing section), `docs/frs-traceability.md` rows PLT-040..043.

**FRS IDs covered:** PLT-032 (failover part), PLT-040, PLT-041, PLT-042, PLT-043; ADM-04 (API part)

**Blocked by:** 96, 97

**Status:** open

- [ ] `RoutingServiceTest`: most-specific rule wins; amount range and channel filters; disabled rule skipped
- [ ] `MsisdnRoutingTest`: prefix → MNO for all five prefixes; an MNP override beats the prefix
- [ ] `RoutingFailoverIT` (Testcontainers): primary circuit open → fallback partner gets the PAY; primary DOWN →
      fallback; no fallback → fast `MW-SYS-PARTNER_CIRCUIT_OPEN`; an ambiguous PAY timeout never fails over
- [ ] `RoutingRuleReloadIT`: an approved rule change is used within 30 s without restart
- [ ] `MaintenanceMessageIT`: disabled biller returns the bn and en message in the envelope
- [ ] admin-api `RoutingRuleChangeRequestIT`: maker ≠ checker, approval publishes `mw.config.changed.v1`
- [ ] `./gradlew :mw-orchestrator:build :mw-admin-api:build :mw-app-gateway:build :contractsLint` green with Docker
- [ ] With the stack up: new API folder `35-routing` (top-up without partnerId, MNP override, failover via mock
      control DOWN, maintenance message) and folders 05, 06, 08 still green
