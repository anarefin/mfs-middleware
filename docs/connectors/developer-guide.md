# Connector developer guide (FRS CON-06, CON-02, 10.4 criterion 4)

How to add a partner to the Bits MFS middleware without the vendor. A new partner of an existing category
(a biller, an operator, a bank profile) is **configuration only**: no Java, no restart. A new partner
*category* is a code change: a new adapter module. This guide covers both and walks through the sample
connector `deploy/partner-routes/examples/sample-rest-biller.yaml`, which runs against mw-simulators'
`samplebiller` mock.

Background: ADR [D28 (partner routes as configuration)](../adr/D28-partner-routes-as-config.md), the concept
page [Camel partner routes](../concepts/camel-partner-routes.md), the mock trigger tables in
[docs/mocks/README.md](../mocks/README.md) and every exposed API in [docs/api-catalogue.md](../api-catalogue.md).

## 1. Where a connector lives

One adapter module per partner family, each with its own copy of the partner engine (`partner/`, ADR D14):

| Family | Module | Partners today |
| --- | --- | --- |
| `telco` | `services/mw-adapter-telco` | the OCS of GP, Robi, Teletalk, Banglalink |
| `billpay` | `services/mw-adapter-billpay` | billagg, SSLCommerz, eKpay, aggregator 3 and 4, any new biller |
| `bank` | `services/mw-adapter-bank` | the C2W bank and the bank gateway profiles `bank-<code>` |
| `interop` | `services/mw-adapter-interop` | the NPSB switch (ISO 20022) |

The card, remittance, ATM and merchant adapters (`services/mw-adapter-card`, `services/mw-adapter-remit`,
`services/mw-adapter-atm`, `services/mw-adapter-merchant`) carry the same engine for their one partner each.

A partner is one **partner record**: the `value` of a `mw.config.changed.v1` PARTNER record (key
`partner/<id>`), which mw-admin-api publishes after maker-checker approval and every adapter of the
record's `family` loads. The compacted topic replays the latest record per partner at every adapter start.

## 2. The partner engine, layer by layer

From the saga's request to the partner's wire and back (packages of `services/mw-adapter-billpay/src/main/java/mfs/mw/adapter/billpay/partner`):

1. **Record store** (`partner/config/`): `PartnerConfigStore` keeps one runtime per partner id and swaps a new
   version in only after it loaded (and, for a record with `dryRun`, passed the dry run). `PartnerConfig` is
   the record: route, mappings, hosts, timeouts, error map, auth, endpoints, maintenance.
2. **Route loader** (`partner/route/PartnerRouteLoader`): parses the record's Camel YAML DSL and refuses
   anything outside the allowlist: only `kamelet:mw-*` endpoints from the shipped catalogue
   (`partner/route/KameletCatalogue`), strict Kamelet parameter keys, every `mw-http-call` host in
   `allowedHosts`, no property placeholders except `{{endpoint}}`, the route ending in `mw-audit`. The same
   rules run in mw-admin-api before a change request exists (`PartnerRouteValidator`), so a bad route never
   reaches the checker.
3. **Gateway** (`partner/route/PartnerGateway`): one call = one operation (`mwOperation` header) through
   `direct:partner-<id>-v<version>`; puts our stable reference, the amount, the parties and the biller code
   into the mapping input; turns the route's result into `PartnerResponse` (Success, Rejected, Pending,
   NotFound, Ambiguous, Unknown, NotSent).
4. **Kamelets** (`src/main/resources/kamelets/`, Java steps in `partner/kamelet/`): the building blocks below.
5. **Guards**: `PartnerHostGuard` (every send, the real addresses connected, DNS rebinding), the partner's
   circuit breaker and bulkhead (resilience4j instance named by the partner id, survives a swap), the
   payload store and audit (`PartnerAudit`, masked by `sensitiveFields`).
6. **Status**: every version ends LIVE or REJECTED on `mw.partner.route.status.v1`; health (UP, DEGRADED,
   DOWN, MAINTENANCE, DISABLED) is reported the same way and read by the orchestrator's routing.

## 3. The Kamelet catalogue

A route uses only these. Parameters are in each Kamelet's YAML under `services/mw-adapter-billpay/src/main/resources/kamelets`.

| Kamelet | What it does |
| --- | --- |
| `mw-map` | `mapping=<OPERATION>.request`: builds the partner body from the call with the record's JSONata mapping. |
| `mw-http-call` | `url`, `method`, `path` (with `{reference}`), `auth`, `secretField`, `authHeader`, `keyId`, ...: sends the mapped body inside the partner's breaker, time limit and bulkhead; signs it with the `mw-auth-<auth>` Kamelet. |
| `mw-soap-call` | the SOAP 1.1/1.2 call: XSLT stylesheets or the JSON/XML convention (`request=json`, `response=json`, `namespace`), WS-Security UsernameToken. |
| `mw-auth-none`, `mw-auth-apikey`, `mw-auth-basic`, `mw-auth-hmac-sha256`, `mw-auth-oauth2-cc`, `mw-auth-jws` | the auth schemes `mw-http-call` delegates to (`auth=none|apikey|basic|hmac-sha256|oauth2-cc|jws`). The secret is a field of the partner's own Vault secret `secret/mw-adapter-<family>/<id>`, read when the route loads, never in the record. |
| `mw-classify` | `mapping=<OPERATION>.response`, `definiteStatuses`, `pending`: maps `{status, body}` to `{outcome, partnerRef, code, message}` and enforces the rules no mapping can override (below). |
| `mw-audit` | the last step of every request route: writes the masked partner payload and audit. |
| `mw-sftp-source` | starts a batch route from a partner's SFTP folder (`delay` or `cron`, known hosts from Vault). |
| `mw-sftp-sink` | writes a file to the partner's SFTP folder (`fileName`, a JSONata `mapping` producing the text). |
| `mw-recon` | the last step of a batch route: publishes each line of a reconciliation file on `mw.partner.recon.v1`. |

Examples of each shape: `deploy/partner-routes/examples/` (`rest-apikey-biller.yaml`, `rest-oauth2-biller.yaml`,
`rest-async-callback.yaml`, `soap-biller.yaml`, `sftp-recon.yaml`, `sftp-sink-payment-file.yaml`, and the sample
`sample-rest-biller.yaml`).

## 4. Operations

A route has one `choice` branch per operation it supports (`${header.mwOperation} == '<OP>'`):

| Operation | Sent | Mapping input you can use | Result |
| --- | --- | --- | --- |
| `INQUIRY` | again after a timeout, 5xx, reset or failed connect (`inquiryRetries`) | `partner.billerCode`, `creditParty.id`, `billMonth` | SUCCESS with `attributes` (`amount`, `dueDate`, `billPeriod`) or FAILED |
| `PAY` | **once**; the saga never resends a PAY it cannot classify | `reference`, `amount.major`, `amount.valueMinor`, `creditParty.id`, `partner.billerCode` | SUCCESS with `partnerRef`, PENDING (with `pending=true`), FAILED, AMBIGUOUS, UNKNOWN |
| `STATUS_QUERY` | again like INQUIRY; the saga asks after an UNKNOWN PAY | `{reference}` in the path | SUCCESS, PENDING, FAILED, NOT_FOUND (only for a 404 the mapping believes) |
| `REVERSE` | once per send; the saga resends with the same `reversalReference` (`reverse:<txnId>`) | `{reference}` (the original payment), `reversalReference`, `amount.major` | SUCCESS with the reversal reference, FAILED |
| `HEALTH_CHECK` | on a schedule when the record has `healthCheck: true` | none | SUCCESS = UP; anything else DOWN |

A partner that calls back (`rest-async-callback.yaml`) also has a `CALLBACK.response` mapping; the callback
endpoint verifies it with the record's `auth` scheme and secret (`auth.credentialsRef`).

## 5. Response-code mapping

`mw-classify` decides the outcome in this order, whatever the mapping says:

- SUCCESS and PENDING need a 2xx and a partner reference;
- FAILED needs a code and an HTTP status listed in `definiteStatuses` (else it is UNKNOWN: a gateway's bare
  404 or 500 never fails a payment that may have been applied);
- a code listed in `ambiguousCodes` is AMBIGUOUS (applied or not, the saga asks with STATUS_QUERY);
- the code is looked up in `errorMap` (`<partner code>: {suffix, retryable}`): the canonical error is
  `MW-PTR-<PARTNER>-<suffix>` (`MW-PTR-SAMPLEBILLER-AMOUNT_MISMATCH`), an unlisted code `UNMAPPED`, not retryable;
- a result that fails `partner/partner-result.schema.json` is UNKNOWN `MALFORMED_REPLY`.

The error map can be replaced on its own (console: the partner's error map; API
`POST /admin/v1/partners/{partnerId}/error-map/change-requests`), without touching the route.

## 6. Resilience fields

| Field | Default | Meaning |
| --- | --- | --- |
| `connectTimeoutMs` | 3000 | a failed connect means NOT_SENT (safe to fail over) |
| `timeoutMs` | 30000 | time limit per call; past it the outcome is UNKNOWN |
| `readTimeoutMs` | `timeoutMs` + 2000 | so the time limit decides first |
| `inquiryRetries` | 1 | resends of INQUIRY / STATUS_QUERY / HEALTH_CHECK; never PAY or REVERSE |
| `resilience.failureRateThreshold`, `slidingWindowSize`, `minimumNumberOfCalls`, `slowCallDurationThresholdMs`, `waitDurationInOpenStateMs`, `bulkheadMaxConcurrentCalls` | the resilience4j instance's | the partner's circuit breaker and bulkhead; an open breaker is a definite pre-send refusal, so routing may fail over |
| `maintenance`, `maintenanceWindow` | off | refuse every call unsent with the bn/en message (console: SET_MAINTENANCE) |

## 7. Dry run and rollback

A change request with action `UPSERT_ROUTE` may carry `dryRun {operation, sample, baseUrl?}` (PAY, INQUIRY or
STATUS_QUERY). After approval the adapter loads the new version next to the live one, sends the sample to
`baseUrl` (default: mw-simulators' programmable partner stub `/sim/partners/<id>`), and swaps the version in
only if the call classifies; otherwise the new version is REJECTED with its problems and the live one keeps
serving. A REJECTED current version makes mw-admin-api republish the last LIVE version as the next one
(`system/rollback`), so an adapter restart never replays a broken record. ENABLE, DISABLE, SET_TIMEOUTS,
SET_MAINTENANCE and SET_ENDPOINT republish a proven route without a dry run.

## 8. Mock or real

A route that calls `url={{endpoint}}` takes its base URL from `endpoints` by the selected `endpoint`
(`MOCK` or `REAL`). Switching is one approved change (action `SET_ENDPOINT`), no route edit; both hosts must
be in `allowedHosts`. The mocks are mw-simulators parties: each one's data triggers, scenarios, switch and
journal are in [docs/mocks/README.md](../mocks/README.md), and the test console drives them (`/tester`).

## 9. Walkthrough: a new REST biller from the console

The sample connector is the `samplebiller` biller `SAMPLEWATER` (category WATER), REST JSON with an API key,
played by mw-simulators (`/samplebiller/v1/...`, seeded bills `1360000001`..`1360000005`). Six records, each a
maker-checker change request (the maker and the checker are two different people; `scripts/creds.sh` prints the
dev logins, `scripts/totp.sh` the TOTP), none needing a restart. The seeded personas `seed-ops-maker` /
`seed-ops-checker` (roles ops-maker / ops-checker) can do every step but the credential, which only an
integration-maker uploads and an integration-checker approves (no seeded persona has those roles yet):

| Step | Console | API (behind the console) | Approved by |
| --- | --- | --- | --- |
| 1. Route, mappings, error map, endpoints | Partners -> new partner `samplebiller`, family billpay: paste `sample-rest-biller.yaml` | `POST /admin/v1/partners/samplebiller/change-requests {action: UPSERT_ROUTE, family: billpay, config, dryRun?}` | ops- or integration-checker |
| 2. Credential | Partners -> `samplebiller` -> Credentials: name `api-key`, type API_KEY | `POST /admin/v1/partners/samplebiller/credentials/api-key/change-requests` | integration-checker |
| 3. Dry run | the route's status turns LIVE (or REJECTED with the problems) | `GET /admin/v1/partners/samplebiller` (`route.status`) | automatic |
| 4. Biller catalogue entry | Billers -> `SAMPLEWATER`, partner `samplebiller` | `POST /admin/v1/billers/SAMPLEWATER/change-requests` | ops- or integration-checker |
| 5. Routing rule | Routing -> BILL_PAY, biller `SAMPLEWATER` -> connector `samplebiller` | `POST /admin/v1/routing-rules/{id}/change-requests` | ops- or integration-checker |
| 6. Try it | the app (or the test console): bill fetch `SAMPLEWATER` / `1360000001`, then pay 845.00 | `POST /app/v1/...` (`docs/api-catalogue.md`) | - |

Approve each on Approvals (`POST /admin/v1/change-requests/{id}/approve`). The adapter loads the route from
`mw.config.changed.v1` and reads the key from Vault when a version loads. A credential needs the partner to exist
(404 otherwise), so after the credential is approved, approve one more change of the partner (SET_TIMEOUTS, for
example): that version loads with the key. The orchestrator picks the routing rule up on the same topic. The
Billers and Routing screens arrive with ticket 132; until then steps 4 and 5 are the API calls.

Check it without the stack:

- `./gradlew :mw-adapter-billpay:test --tests '*SampleConnectorRouteIT'`: the sample record loads as is (a
  checked copy) and serves INQUIRY, PAY, STATUS_QUERY, REVERSE and HEALTH_CHECK with its key and error map;
- `./gradlew :mw-simulators:test --tests '*SampleBillerMockIT'`: the `samplebiller` mock party.

Try a failure: a mock-control scenario (`POST /mock-control/samplebiller/scenarios {"operation": "pay",
"behaviour": "TIMEOUT"}`) or a data trigger (amount ending `.13`, bill number ending `999`): the outcomes are in
the `samplebiller` section of [docs/mocks/README.md](../mocks/README.md).

## 10. A new partner category (code change)

A partner that fits no family (a new kind of money movement, a new inbound channel) gets its own adapter:

1. `scripts/new-service.sh mw-adapter-<name>`: the module from `templates/mw-service-template`, picked up by
   `settings.gradle.kts`; the script prints its `deploy/docker-compose.yml` block.
2. Copy the partner engine from mw-adapter-billpay: `partner/` (package renamed), `src/main/resources/kamelets/`
   and `src/main/resources/partner/`, and add the module to `scripts/test/test_partner_engine_copies.sh`, which
   keeps every copy identical apart from the package.
3. The family: add it to the `family` enum of `mw.partner.request.v1` / `mw.partner.reply.v1` (owned by
   mw-orchestrator: a change request to its owner, `contracts/OWNERS.yml`) and to mw-admin-api's family rule.
4. Vault: the adapter's AppRole and policy for `secret/mw-adapter-<name>/*` in `deploy/provision/services.sh`.
5. Inbound partner calls: an APISIX route under `deploy/apisix/routes/`.
6. The mock: a mock party in mw-simulators (`MockParties`, `MockControl.decide` per operation) and its section
   in the trigger registry (`MockTriggers`; `MockTriggerDocsTest` fails until it has one), then the README block.
7. `./gradlew :mw-adapter-<name>:build`, `./gradlew :checkIsolation`, `./gradlew :contractsLint` and
   `./gradlew :scriptTests`.
