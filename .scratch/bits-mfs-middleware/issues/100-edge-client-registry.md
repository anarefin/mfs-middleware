# 100: Edge client registry, partner OAuth2 client credentials, inbound mTLS (FRS PLT-002, PLT-003, ADM-03)

**What to build:** A registry of every channel client (app types, MNO USSD gateways, partners, merchants, portals) with
allowed APIs, auth method, IP allow-list, rate limit and daily quota, applied to APISIX. Today APISIX consumers exist only
for `ussd_*`; ip-restriction and limit-count are set per route; partners reach callbacks with an IP allow-list only;
OAuth2 client credentials and mTLS exist only outbound in the adapters' `partner/auth`.

**Principles:**
- One source of truth (mw-admin-api DB), APISIX is a projection; applying is idempotent (same rules as
  `deploy/apisix/README.md`).
- Secrets (HMAC keys, client secrets, CA certs) live in Vault (ticket 91), never in the registry rows.

**Scope:**
- **mw-admin-api**: schema table `channel_client` (clientId, type APP/MNO/PARTNER/MERCHANT/PORTAL, authMethod
  JWT/OAUTH2_CC/MTLS/HMAC/BASIC, allowedRoutes[], ipAllowList[], ratePerSec, dailyQuota, enabled, version); resource
  `CHANNEL_CLIENT` in the change-request engine (maker-checker); on approval an `service/ApisixConsumerSync` writes the
  APISIX consumer (+ consumer group with `limit-count` daily quota and `limit-req` rate) through the APISIX Admin API.
  Contract: `contracts/api/mw-admin-api/admin.v1.openapi.yaml` (owner mw-admin-api).
- **OAuth2 client credentials (PLT-002)**: Keycloak `partners` realm gets confidential clients per partner
  (`private_key_jwt`, D21); partner inbound routes (`partner-*-callback.json` and the routes of tickets 118–121) validate
  with `openid-connect` bearer-only, and the `portals` realm keeps its user login.
- **Inbound mTLS**: APISIX SSL object with client CA verification for bank/aggregator callback hosts where the client
  says `MTLS` (dev CA from Vault PKI, ticket 91); `deploy/apisix/README.md` documents the test certs.
- Seeds: the existing clients (customer/agent/merchant apps, four MNOs, billagg, c2wbank, checkout merchants, org portal)
  as registry rows so behaviour is unchanged; `mw-seeder` writes them.
- UI is ticket 132; docs `docs/concepts/` APISIX page, `README.md` knobs if any.

**FRS IDs covered:** PLT-002, PLT-003, PLT-004 (quota part), ADM-03 (API part)

**Blocked by:** 90, 91, 99

**Status:** open

- [ ] `ApisixConsumerSyncTest`: a registry row maps to the expected consumer/plugin JSON; re-apply is a no-op
- [ ] `ChannelClientChangeRequestIT`: maker ≠ checker; approval writes the consumer (APISIX admin stub)
- [ ] `scripts/edge-test.sh` cases: blocked IP 403, daily quota 429 with `Retry-After`, partner callback without a
      client-credentials token 401, with token 2xx; mTLS callback without client cert refused
- [ ] `./gradlew :mw-admin-api:build :mw-seeder:build :contractsLint :scriptTests` green with Docker
- [ ] With the stack up: new API folder `37-edge-clients`; folders 07, 08, 18, 20 still green
