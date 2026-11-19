# 100 split (user decisions 2026-11-20) — read with 100-edge-client-registry.md

Narrow reading: the new checks apply only to clients registered with the new auth types. billagg / c2wbank callbacks
stay HMAC (checked by the adapter as today), USSD gateways stay basic auth, merchants stay HMAC: folders 07/08/18/20 unchanged.

- **100a** (M7): registry in mw-admin-api (`channel_client`, next free Flyway V), maker-checker change-request type
  `channel_client` (makers/checkers: integration-maker/-checker AND ops-maker/-checker, as partner config),
  controller/service + `admin.v1` contract, APISIX Admin API client, `ApisixConsumerSync` run AFTER the approval commits
  plus a periodic reconcile job (ShedLock), seeder step for seed rows (today's clients). Secrets: never in registry rows;
  add a Vault **KV version 1** mount (e.g. `apisix/`) + read-only APISIX Vault token + APISIX `secrets/vault/<id>` object
  in `apply.sh`; consumers use `$secret://vault/...`. Per-client rate limit/quota for clients with an APISIX consumer.
- **100b** (before 118–121): OAuth2 client credentials only for `OAUTH2_CC` clients: Keycloak `partners` realm
  `private_key_jwt` client per partner, `openid-connect` on new routes + one test route, simulator token client;
  route-level limit keyed by token client id for consumer-less clients.
- **100c** (by M13): inbound mTLS for `MTLS` clients on a separate APISIX SSL host, test certs from Vault PKI, simulator
  presenting a client cert, remaining `edge-test.sh` cases, API folder 41.
