# MVP host runbook

How to run, check and recover the MVP stack on its single host (spec "Single-host topology",
D11, D12, M8). The [README](../README.md) has the quick start; this page is for whoever keeps the
host running and for anyone debugging a failed demo or gate.

## Host

| | MVP host (spec, TBC) | Smallest that works (functional runs, demos, the API suite) |
| --- | --- | --- |
| CPU | 16 vCPU | 4 cores |
| RAM | 64 GB | 16 GB (Docker gets at least 8 GB) |
| Disk | 500 GB SSD | 40 GB free |
| OS | Linux VM | Linux or macOS (Docker Desktop) |

The stack is one hand-written file, `deploy/docker-compose.yml` (project `mfs-mw`, ADR D16), with
a laptop demo footprint (about 7.9 GB of memory limits in total, capped at 8 GB by `./gradlew :composeCheck`; 1 Kafka partition per topic, 160m JVM heaps, at most 1 CPU per container but Keycloak).
They are sized for functional runs; a throughput run on a small
machine may be capped by it (`tests/perf/README.md`).

### Installing the prerequisites on a clean Ubuntu 24.04 VM

```sh
sudo apt-get update && sudo apt-get install -y ca-certificates curl git jq
curl -fsSL https://get.docker.com | sudo sh          # Docker Engine + Compose v2 plugin
sudo usermod -aG docker "$USER" && newgrp docker
# JDK 25: any build, e.g. Eclipse Temurin from https://adoptium.net (apt repo or tarball), then
java -version                                       # must say 25
docker compose version                              # 2.20 or newer
```

On macOS: Docker Desktop (Settings, Resources: memory 8 GB or more), a JDK 25 (`brew install
--cask temurin`), and `brew install jq`.

## Start, stop, status

Gradle runs from the repo root; the `docker compose` commands run from `deploy/` (Compose then reads
`deploy/.env` by itself, short test timers included).

| Task | Command |
| --- | --- |
| Build and test (after a code change) | `./gradlew clean build` (or `./gradlew :mw-notify:build` for one module); `./gradlew bootJar` for the jars only |
| Dev settings (first time) | `cd deploy && cp dev.env.defaults .env` |
| Start, build the images, seed (or bring back after a reboot) | `docker compose --profile core --profile seed up -d --build`, then `docker compose --profile core --profile seed wait mw-seeder` (ends with `exited with status code 0`) |
| Production timers instead of the test ones | delete the timer block from `deploy/.env`, then `docker compose --profile core up -d` (recreates the services) |
| Start without seeding, wait until healthy | `docker compose --profile core up -d --build --wait` |
| Start only the P2P and cash path | `docker compose --profile minimal up -d --build` (unseeded) |
| Live checks | `scripts/smoke.sh` (repo root) |
| Stop, keep the data | `docker compose --profile core --profile seed down` (or `stop`) |
| Stop and wipe every volume | `deploy/reset.sh` (every profile, orphans too) |
| Swap a rebuilt service into the running stack | `./gradlew :mw-notify:bootJar`, then `docker compose --profile core up -d --build mw-notify` and `docker compose restart apisix` |
| Run a second stack beside the first | export `COMPOSE_PROJECT_NAME=mw-t2`, `MW_HTTPS_PORT=0` and a free `MW_PORT_<NAME>` for every direct port for the `docker compose` commands and the scripts (the console login needs port 443, so use it on the default stack only) |

The step-by-step version with timings is `deploy/RUNNING.html`. For `docker compose` work from the
repo root, define once per shell (the same file and env the scripts use):

```sh
dc() { docker compose -f deploy/docker-compose.yml --profile core "$@"; }
dc ps                                   # state and health of every container
dc logs -f --tail=200 mw-orchestrator   # one service's JSON log
dc restart mw-notify                    # restart one service
dc stats --no-stream                    # memory and CPU per container
```

`up --wait` fails, naming the container, when a service is unhealthy or exited or a one-shot
(`kafka-init`, `etcd-init`, `apisix-init`, `provision`) exits non-zero; `dc logs <that container>`
shows why. A slow first start on a small machine may need `--wait-timeout 600`. Without `--wait`,
`dc ps -a` shows the same. The `provision` one-shot runs on every `up` and creates missing roles and
schemas idempotently, so a new service's schema appears without wiping volumes.

## What is published

APISIX is the edge on 443 (container port 9443, `MW_HTTPS_PORT` to change it). The edge
certificate is self-signed for `localhost`, created on the first start and kept in the
`apisix-tls` volume.

For debugging, every service and platform container also publishes a port on 127.0.0.1 only
(never on other interfaces). Each moves with its `MW_PORT_<NAME>` knob, e.g. `MW_PORT_POSTGRES`.
etcd and the APISIX Admin API stay unpublished. A direct port skips the edge policies (JWT, HMAC,
quotas); the console login still needs https://localhost on 443. Production stays edge-only (ADR
D3 amendment 2026-11-13).

| Host port | Container | Knob |
| --- | --- | --- |
| 8081 | `mw-app-gateway` | `MW_PORT_APP_GATEWAY` |
| 8082 | `mw-ussd` | `MW_PORT_USSD` |
| 8083 | `mw-orchestrator` | `MW_PORT_ORCHESTRATOR` |
| 8084 | `mw-core-mock` | `MW_PORT_CORE_MOCK` |
| 8085 | `mw-simulators` | `MW_PORT_SIMULATORS` |
| 8086 | `mw-notify` | `MW_PORT_NOTIFY` |
| 8087 | `mw-audit-dw` | `MW_PORT_AUDIT_DW` |
| 8088 | `mw-admin-api` | `MW_PORT_ADMIN_API` |
| 8089 | `mw-adapter-telco` | `MW_PORT_ADAPTER_TELCO` |
| 8090 | `mw-adapter-billpay` | `MW_PORT_ADAPTER_BILLPAY` |
| 8091 | `mw-adapter-bank` | `MW_PORT_ADAPTER_BANK` |
| 8092 | `mw-checkout` | `MW_PORT_CHECKOUT` |
| 8093 | `mw-portal-api` | `MW_PORT_PORTAL_API` |
| 3000 | `mw-admin-ui` (path `/console`) | `MW_PORT_ADMIN_UI` |
| 3001 | `mw-test-console` (profile `tester`) | `MW_PORT_TEST_CONSOLE` |
| 5432 | `postgres` | `MW_PORT_POSTGRES` |
| 6379 | `valkey` | `MW_PORT_VALKEY` |
| 9094 | `kafka` (host listener `localhost:9094`) | `MW_PORT_KAFKA` |
| 8180 | `keycloak` | `MW_PORT_KEYCLOAK` |
| 8200 | `openbao` | `MW_PORT_OPENBAO` |
| 8025 / 3025 | `greenmail` API / SMTP | `MW_PORT_GREENMAIL`, `MW_PORT_GREENMAIL_SMTP` |

Readiness of one service: `curl -s localhost:8081/actuator/health/readiness` (the actuator listens
on 8080 inside the container).

Through the edge:

| Path on https://localhost | Goes to |
| --- | --- |
| `/console` | admin console (`mw-admin-ui`) |
| `/realms/ops/*` | Keycloak realm `ops`, browser login of the console only |
| `/app/v1/*` | `mw-app-gateway` (customer, agent, merchant apps; realm `customers` JWT) |
| `/portal/org/v1/*` | `mw-portal-api` (realm `portals` JWT) |
| `/admin/v1/*` | `mw-admin-api` (realm `ops` JWT) |
| `/checkout/*`, `/ussd/<mno>`, `/partner/*/callback/*` | checkout, MNO USSD gateways, partner callbacks |
| `/healthz` | APISIX itself |
| `/tester` | test console (`mw-test-console`, dev/test only, ADR D17): only while the test-only routes are on (`scripts/tester.sh`), from private addresses; never on a shared or production host |

The full route list and every edge policy is in `deploy/apisix/README.md`. To demo from another
machine, browse on the host itself or forward the port (`sudo ssh -L 443:localhost:443
<user>@<host>`): the console's login is bound to `https://localhost` (`MW_PUBLIC_HOST`,
`MW_ADMIN_UI_PUBLIC_URL`, and the edge certificate is for `localhost` only).

## Credentials

- Every dev credential (PostgreSQL, Valkey, Keycloak admin and test users, client secrets, partner
  simulator HMAC secrets, USSD gateway passwords, the console's session key) is a fixed, obviously
  fake value in the committed `deploy/dev.env.defaults` (ADR D9 amendment; the tester logins are in
  `docs/testing-guide.html` section 1). `cd deploy && cp dev.env.defaults .env` before the first
  `up`; `scripts/smoke.sh`, `scripts/tester.sh`, `scripts/suite.sh` and `deploy/reset.sh` copy it
  when `deploy/.env` is missing. Deleting `deploy/.env` and copying it again is safe. OpenBao also gets the
  fixed token `dev-root-token` on the first start of a fresh volume. The one-shot `provision`
  (`deploy/provision`) writes the services' secrets into OpenBao; services read them from OpenBao at start.
- Test users and which variable holds their password: README, "Test users".
- Changing a value: edit `deploy/.env`, then `deploy/reset.sh` and start again. The volumes
  (PostgreSQL, Keycloak, etcd) were initialised with the old values, so a changed value with the
  old volumes keeps the stack from coming up.
- The OpenBao root token is kept in its data volume (dev only, a known gap; production unseal is
  deferred).

### An operator lost their TOTP device

The `ops` realm requires TOTP. Reset it so the next sign-in enrols again (`ops-maker01` here):

```sh
dc exec keycloak sh -c '
  k=/opt/keycloak/bin/kcadm.sh; c="--config /tmp/kc.config"
  $k config credentials $c --server http://localhost:8080 --realm master \
     --user "$KC_BOOTSTRAP_ADMIN_USERNAME" --password "$KC_BOOTSTRAP_ADMIN_PASSWORD" >/dev/null
  id=$($k get users $c -r ops -q username=ops-maker01 --fields id --format csv --noquotes)
  for cid in $($k get users/$id/credentials $c -r ops --fields id,type --format csv --noquotes | awk -F, "\$2==\"otp\"{print \$1}"); do
    $k delete users/$id/credentials/$cid $c -r ops
  done
  $k update users/$id $c -r ops -s "requiredActions=[\"CONFIGURE_TOTP\"]"
  rm -f /tmp/kc.config'
```

`deploy/reset.sh` also resets every operator (the realms are re-imported).

## Health and alerts

There are no dashboards or alert rules in the MVP (D10; alert rules deferred). Services log JSON
to stdout with fixed fields (`ts`, `level`, `service`, `correlationId`, `txnId`, `event`, ...;
root `CLAUDE.md`, "Log fields") and expose `/actuator/health` (the container healthcheck) and
`/actuator/prometheus` inside the network. Find a transaction's whole story by its correlation ID
or txnId:

```sh
dc logs --no-log-prefix mw-app-gateway mw-orchestrator mw-core-mock | grep '<txnId>'
```

Log events that need a person:

| `event` | Service | Meaning, first action |
| --- | --- | --- |
| `txn.manual_review.ageing` | mw-orchestrator | a transaction has waited in `MANUAL_REVIEW` too long: find it in the console, force commit or release through maker-checker |
| `txn.outcome.conflict` | mw-orchestrator | a late partner result contradicts the terminal state (kept as is, audited): check with the partner, reverse if needed |
| `kafka.dlq.parked` | any consumer | a record failed its retries and sits on `<topic>.<group>.dlq`: read it (below), fix the cause, replay by hand |
| `outbox.relay.failed` | any producer | the outbox cannot reach Kafka: check `kafka` health; rows are sent once it is back |
| `audit.chain.broken` | mw-audit-dw | the audit hash chain failed verification: treat as an incident, keep the database as is |
| `smpp.bind.down` | mw-ussd, mw-notify | the SMPP bind to the USSD gateway or SMSC is lost; it reconnects by itself, check the peer |
| `partner.call.failed`, `partner.route.failed` | adapters | partner errors or a route that failed to start after a config change |
| `mw.edge.rate_limit_degraded` | apisix | Valkey is unreachable and the rate limiter fails open: check `valkey` |

Read a DLQ (topic names in `deploy/kafka/topics.txt`):

```sh
dc exec -T kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server localhost:9092 \
  --topic mw.txn.request.v1.mw-orchestrator.dlq --from-beginning --timeout-ms 10000 --property print.headers=true
```

Read-only database checks (superuser, as the chaos script does; never write by hand):

```sh
dc exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "select status, count(*) from txn.transaction group by 1"'
```

## Recovery

| Symptom | Do |
| --- | --- |
| A service crashed or was killed | `dc start <service>` (or the start command again). The outbox resends, the inbox drops duplicates, saga timers and the status inquiry finish what was in flight; `scripts/chaos.sh` proves this for every service, Kafka and PostgreSQL |
| Transactions stuck in `PROCESSING` / `UNKNOWN` | wait for the status inquiry schedule (30 s, 1, 2, 5 min); what it cannot settle goes to `MANUAL_REVIEW`, handled in the console |
| `up` stops at a one-shot | `dc logs provision` (it names the service and step that failed; or `kafka-init`, `etcd-init`, `apisix-init`); most often a volume made with other credentials (see Credentials) or an OpenBao that is still sealed: `dc down`, then start again |
| `wait mw-seeder` prints a non-zero code | `dc --profile seed logs mw-seeder`: the line `seed.step.failed` names the step. `not ready yet: <svc>` = a service never became ready (fix it, seed again); `open change request of <operator>` = a partner, menu or template has a pending request someone else made: approve or reject it in the console, then run the start command again. Seeding is idempotent, so re-running is always safe |
| APISIX answers 404 for test routes (`/test/...`) | expected after an `up`; `scripts/api-test.sh`, `scripts/tester.sh` and `scripts/suite.sh perf` enable them |
| A service answers 404 `MW-VAL-003` for paths it serves, after `dc up -d <service>` recreated just that container | APISIX still sends to the old container IP, which another service may own now: `dc restart apisix` (a full start does not hit this) |
| 401 from the console API after a long idle | sign out and in again (the refresh token expired) |
| Port 443 or a direct port busy | free it, or move ours: `MW_HTTPS_PORT=8443` (APIs work, the console login does not) or `MW_PORT_<NAME>` (e.g. `MW_PORT_POSTGRES=15432`) in `deploy/.env` or in front of the `docker compose` start command |
| Docker out of disk | `docker system df`, then `docker image prune` and `docker builder prune` (keeps volumes) |
| Anything else after a crash of the host | `dc down`, then start again; as the last resort `deploy/reset.sh`, then start again (loses every transaction; the seed data comes back) |

## Backup and restore

Not automated in the MVP (spec 20.5; the restore drill is deferred). For a manual copy before a
risky change:

```sh
dc exec -T postgres sh -c 'pg_dumpall -U "$POSTGRES_USER"' > mw-backup.sql
```

Restoring means a fresh stack (`deploy/reset.sh`, then start again) and feeding that file to
`psql`. Kafka, Valkey and OpenBao are not backed up: Kafka holds only in-flight messages (each
service's outbox is in PostgreSQL), Valkey only sessions and locks, OpenBao only the generated dev
secrets that `provision` writes again on every `up`.

## Onboard a biller

ADR D26, ticket 83. A biller of a known kind (REST/JSON, an auth scheme the `mw-*` Kamelets have) is a
partner route as config: no Java, no release, no restart for the route itself.

1. **Write the route** from a Kamelet example (`deploy/partner-routes/examples/rest-apikey-biller.yaml`):
   one `- route:` from `direct:partner-<id>` (lower-case letters, digits, hyphens), steps `mw-map` ->
   `mw-http-call` -> `mw-classify`, then `mw-audit`; its JSONata `mappings` by name; the `allowedHosts` it
   calls. Kaoto (the visual Camel YAML editor) can draw and edit it offline. Try it locally with Camel
   JBang and the shipped Kamelets (flags of `camel run` in camel-jbang 4.22.2):

   ```
   camel run newco.yaml --local-kamelet-dir=services/mw-adapter-billpay/src/main/resources/kamelets --max-seconds=60
   ```

   `newco.yaml` holds the `route` text only. Point its `mw-http-call` `url` at the dev stack's
   simulator stub (`http://127.0.0.1:8085/sim/partners/newco`, port `MW_PORT_SIMULATORS`) and give the stub
   a canned answer first:

   ```
   curl -s -X POST http://127.0.0.1:8085/sim/stubs/newco -H 'content-type: application/json' \
     -d '{"operation":"PAY","method":"POST","path":"/v1/payments","status":200,"body":{"receipt":"NC-1"}}'
   curl -s http://127.0.0.1:8085/sim/stubs/newco/requests    # what the route sent
   ```

   The Kamelets' Java steps (`mwMap`, `mwHttp`, `mwClassify`, `mwAudit`, the `mw-auth-*` beans) live in
   mw-adapter-billpay, so outside the adapter JBang checks that the route and the Kamelets load and parse;
   the end-to-end check of the mappings is the dry-run of step 4. Switch the `url` back to the partner
   before submitting.
2. **Submit** it (maker: ops-maker or integration-maker): console `/console/partners/<id>`, card *Route*
   (a new id offers *Onboard with a route* and a Family), paste the YAML, the allowed hosts, the mappings,
   the dry-run operation and sample (the canonical call input) and the error map. API: `POST
   /admin/v1/partners/<id>/change-requests {action: UPSERT_ROUTE, family, config: {route, mappings,
   allowedHosts, dryRun, timeoutMs, errorMap, ambiguousCodes, sensitiveFields}, reason}`. mw-admin-api checks
   it with a copy of the adapters' loader rules (Camel's YAML DSL schema, then the allowlist): a refusal is
   `400 MW-VAL-041`, one detail per problem `line N: construct: problem`; nothing is filed.
3. **Approve** it (a different operator: ops-checker or integration-checker) in Approvals. The approval
   publishes `mw.config.changed.v1` (key `partner/<id>`, with `family`); the partner page shows the route
   `vN waiting for its dry-run`.
4. **Watch it reach LIVE**: mw-adapter-billpay loads the version beside the live one, sends the dry-run
   through its candidate `direct:partner-<id>~candidate` to `mw.partners.dry-run.base-url` (dev:
   mw-simulators' stub `/sim/partners/<id>/**`, so give the stub its answer as in step 1; production: the
   record's `dryRun.baseUrl`, a partner sandbox in `allowedHosts`), and swaps it in only when the answer
   is SUCCESS or PENDING. It reports on `mw.partner.route.status.v1`: the page shows `vN LIVE`, or `vN
   REJECTED (vM still serves)` with the problems (the previous version keeps serving; fix and submit
   again). Logs: `partner.dry_run.queued`, `config.applied`, `security.partner_route_refused`. Who still uses
   a Kamelet: `GET /admin/v1/partners/kamelet-usage` (or mw-adapter-billpay's
   `GET /internal/v1/partners/kamelet-usage`).
5. **Catalogue and secret** (static by decision): add `mw.billers.<CODE>: {name, category, partner-id}` to
   mw-adapter-billpay's config and the secret fields the route names to OpenBao
   `secret/mw-adapter-billpay/<id>`, then restart mw-adapter-billpay
   (`./gradlew :mw-adapter-billpay:bootJar`, then from `deploy/`: `docker compose --profile core up -d --build mw-adapter-billpay`).
   Auth options (ticket 87; details in `docs/concepts/camel-partner-routes.md`). Every "field" is a field
   of `secret/<adapter>/<id>` in OpenBao:

   | `mw-http-call` | Needs | OpenBao fields | Notes |
   | --- | --- | --- | --- |
   | `auth=apikey` | `secretField`, `authHeader` | the key | header default `X-Api-Key` |
   | `auth=hmac-sha256` | `secretField`, `keyId` | the shared secret | |
   | `auth=basic` | `userField`, `secretField` | user, password | RFC 7617 |
   | `auth=oauth2-cc` | `tokenUrl`, `clientId`, `secretField`, `scope`, `audience` | client secret | `tokenUrl` host in `allowedHosts`; one token per partner until `expires_in` - 30 s (`mw.partners.oauth2.expiry-skew`); a 401 takes one fresh token; log `partner.oauth2.token_fetched` / `partner.oauth2.token_failed` (no token) |
   | `auth=jws` | `secretField`, `alg` (`RS256`/`ES256`), `keyId`, `authHeader` | PKCS#8 PEM private key | detached JWS in `X-JWS-Signature` by default; no mw-crypto key purpose fits, so an OpenBao key (dev: a fake one) |
   | `sslContextRef=<name>` | https `url` | `<name>-keystore` (base64 PKCS#12), `<name>-keystore-password`, with `truststore: true` also `<name>-truststore`, `<name>-truststore-password` (a failed read leaves the context unregistered, never the JVM trust store) | static config `mw.partners.tls.contexts.<name>.partner-id: <id>`, read at startup (restart after a change); log `partner.tls_context_registered` / `partner.tls_context_unavailable` |

6. **Callbacks**: a biller that answers asynchronously needs an APISIX route file
   (`deploy/apisix/routes/partner-<id>-callback.json`, as `partner-billagg-callback.json`) and
   `deploy/apisix/apply.sh`.

After an adapter restart the compacted config topic replays the latest record per partner and the
adapter dry-runs it again; a latest record that was REJECTED leaves that partner on its seed (or no
route) until a new version is approved (open item, ticket 84).

## Batch partner over SFTP

A partner's daily reconciliation or settlement file (ticket 86) is a batch partner route: a record of
its own (e.g. `billagg-recon`) whose route starts from the `mw-sftp-source` Kamelet and ends with
`mw-recon`, onboarded through the same maker-checker as a biller (no dry-run: a batch route takes no
calls). Example: `deploy/partner-routes/examples/sftp-recon.yaml`. The adapter of the family polls the
partner's directory, reads each file once (name, size, modification time), publishes every data line as
`mw.partner.recon.v1` (key partnerId) through its outbox and moves the file to `.done`; a file it cannot
read (wrong field count, line too long, no data line) goes to `.error` and publishes nothing: fix it
and drop it again. `enabled: false` (DISABLE) stops the polling.

1. **Secrets**, in the partner's own OpenBao secret `secret/<adapter>/<id>`: the login password (field
   named by `secretField`; with `auth=key` a PEM private key) and the host's known_hosts lines (field
   named by `knownHostsField`). Host keys are checked strictly: an unknown or changed key is refused
   before the login (`HostKeyException` in the adapter's log, the file stays on the host). Get the lines
   from the partner, or check them against `ssh-keyscan -p <port> <host>`.
2. **Allowed host**: the record's `allowedHosts` names the SFTP host; a host that resolves to a private
   address is refused unless it is a static dev host of the adapter.
3. **Dev stack**: the SFTP server `partner-sftp` (profile `tester`, login `bits` / `SFTP_DEV_PASSWORD` from
   `deploy/.env`, directories `recon` and `out`). From `deploy/`:

   ```
   docker compose --profile tester up -d partner-sftp
   docker compose exec partner-sftp sh -c 'for k in /etc/ssh/ssh_host_*_key.pub; do printf "partner-sftp %s\n" "$(cut -d" " -f1,2 $k)"; done'
   MW_PARTNER_INSECURE_HTTP_HOSTS=mw-simulators,partner-sftp docker compose --profile core up -d mw-adapter-billpay
   docker compose cp recon-20261118.csv partner-sftp:/home/bits/recon/
   docker compose exec partner-sftp ls /home/bits/recon/.done /home/bits/recon/.error
   ```

   The second line prints the known_hosts lines for the `sftp-known-hosts` field; `partner-sftp` regenerates
   its host keys when the container is recreated, so write them again then. The lines are in the
   adapter's outbox (`mw.partner.recon.v1`) and on Kafka; their consumer, mw-recon, is ticket 88.

## Gate and security runs on this host

| Run | Command | Note |
| --- | --- | --- |
| Build gate | `./gradlew clean build` | with Docker running (otherwise the Testcontainers tests are skipped); best with the stack stopped: up to four modules' test PostgreSQL and Kafka run at once (`mwDockerTests`); a milestone gate adds `-PwithSlow` |
| Acceptance suite | `scripts/api-test.sh` (`scripts/api-test.sh "" 1` adds the slow folder) | on a fresh stack: `deploy/reset.sh`, then start with the seed profile (`deploy/.env` carries the test timers); under 10 minutes |
| Chaos | `scripts/chaos.sh` (or `CHAOS_TARGETS="mw-orchestrator kafka" scripts/chaos.sh`) | kills containers: never on a stack someone else is using |
| Load | `scripts/suite.sh perf` | spends the seed wallets' daily limits: fresh stack before and after |
| Security | `SECURITY_LIVE=1 scripts/security.sh` | after `scripts/api-test.sh`, so the log scan has logs to read; findings register `docs/security-findings.md` |
| Container logs only | `scripts/log-scan.sh` | no PIN or unmasked MSISDN / NID in any log |
