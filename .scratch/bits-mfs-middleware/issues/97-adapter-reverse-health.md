# 97: Partner engine: REVERSE operation, active health, DEGRADED/MAINTENANCE, raw payload store (CON-01, 04, 05)

**What to build:** Close the connector-framework gaps in the copied `partner/` engine of `mw-adapter-telco`, `mw-adapter-billpay`
and `mw-adapter-bank` (three copies kept identical apart from the package). Today operations are PAY, INQUIRY, STATUS_QUERY,
CALLBACK; health is UP/DOWN/DISABLED/DELETED; audit bodies are cut at 4 KB.

**Scope:**
- REVERSE: `partner/config/PartnerConfig` operations + `REVERSE`; route YAML may define a `reverse` flow (Kamelets `mw-map`,
  `mw-http-call`/`mw-soap-call`, `mw-classify`); `partner/route/PartnerGateway` dispatches it; idempotent partner reference
  `reverse:<txnId>`; never auto-retried by the HttpClient (lesson from e74c2cfa), only resent by the saga (96).
- Health: `partner/route/PartnerRouteHealth` states `UP`, `DEGRADED` (breaker half-open or error rate over threshold), `DOWN`
  (breaker open or health probe failing), `MAINTENANCE` (config window, 131 adds the console field; config flag now), plus existing
  `DISABLED`/`DELETED`; optional `healthCheck` operation in the route (probe on a ShedLock schedule).
- Publish health changes on `mw.partner.route.status.v1` (owner mw-adapter-billpay; new `health` field, samples) so routing (98)
  and the console read it.
- Raw payload store: `partner/route/PartnerAudit` writes the full masked request/response (masking via `util/MaskingPolicy`) to the
  adapter's own table `partner_payload(txn_id, partner_id, op, direction, body, latency_ms, at)` with retention; audit event keeps
  the 4 KB excerpt plus `payloadRef`; fix the dropped audit when `txnId` is null (bill fetch, dry-run: noted open item of run 77–87).
- Simulators: `services/mw-simulators` ocs, billagg, c2wbank answer a reverse call statefully (reversed once; second reverse returns
  the first result) so tests run; full mock control comes in 102.
- Docs: `services/mw-adapter-billpay/CLAUDE.md` "Partner engine", `docs/concepts/camel-partner-routes.md`, example routes in
  `deploy/partner-routes/examples/` with a reverse flow; admin-api `partner-seeds/dev` kept in sync.
- Split if over budget: 97a REVERSE + simulators; 97b health states + payload store.

**FRS IDs covered:** CON-01 (reverse, healthCheck), CON-04, CON-05, MCK-02 (reverse once)

**Blocked by:** 89

**Status:** open

- [ ] Camel route tests first per adapter: a reverse flow maps, calls, classifies; a 503 on REVERSE is not resent by the client
- [ ] `PartnerRouteHealthTest`: breaker transitions -> UP/DEGRADED/DOWN; maintenance flag -> MAINTENANCE; change published once
- [ ] `PartnerPayloadIT`: full masked body stored with latency; no unmasked MSISDN/account (assert with `MaskingPolicy` patterns); audit sent when txnId is null
- [ ] The three engine copies diff clean apart from package (`scripts/test/` check, existing or added)
- [ ] `./gradlew :mw-adapter-telco:build :mw-adapter-billpay:build :mw-adapter-bank:build :mw-simulators:build :contractsLint` green with Docker
- [ ] `scripts/api-test.sh "05, 06, 08, 15"` pass on a fresh stack
