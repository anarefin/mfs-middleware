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
| 8094 | `mw-crypto` (HTTPS with a mandatory client certificate, D24: `scripts/smoke.sh` shows a call; its actuator is on 8081 inside the network) | `MW_PORT_CRYPTO` |
| 8095 | `mw-auth` public port (`/auth/v1/**` from ticket 69; its internal API 8443 needs a client certificate and is not published, its actuator is on 8081 inside the network) | `MW_PORT_AUTH` |
| 3000 | `mw-admin-ui` (path `/console`) | `MW_PORT_ADMIN_UI` |
| 3001 | `mw-test-console` (profile `tester`) | `MW_PORT_TEST_CONSOLE` |
| 5432 | `postgres` | `MW_PORT_POSTGRES` |
| 6379 | `valkey` | `MW_PORT_VALKEY` |
| 9094 | `kafka` (host listener `localhost:9094`) | `MW_PORT_KAFKA` |
| 8180 | `keycloak` | `MW_PORT_KEYCLOAK` |
| 8200 | `openbao` | `MW_PORT_OPENBAO` |
| 8025 / 3025 | `greenmail` API / SMTP | `MW_PORT_GREENMAIL`, `MW_PORT_GREENMAIL_SMTP` |
| 5657 / 5658 | `softhsm` PKCS#11 tokens `mw-crypto` / `paysim` (p11-kit RPC, `deploy/softhsm/README.md`) | `MW_PORT_SOFTHSM_MWCRYPTO`, `MW_PORT_SOFTHSM_PAYSIM` |

Readiness of one service: `curl -s localhost:8081/actuator/health/readiness` (the actuator listens
on 8080 inside the container).

Through the edge:

| Path on https://localhost | Goes to |
| --- | --- |
| `/console` | admin console (`mw-admin-ui`) |
| `/realms/ops/*` | Keycloak realm `ops`, browser login of the console only |
| `/app/v1/*` | `mw-app-gateway` (customer, agent, merchant apps; mw-auth access JWT, D21) |
| `/portal/org/v1/*` | `mw-portal-api` (realm `portals` JWT) |
| `/admin/v1/*` | `mw-admin-api` (realm `ops` JWT) |
| `/checkout/*`, `/ussd/<mno>`, `/partner/*/callback/*` | checkout, MNO USSD gateways, partner callbacks |
| `/healthz` | APISIX itself |
| `/tester` | test console (`mw-test-console`, dev/test only, ADR D17): only while the test-only routes are on (`scripts/tester.sh`), from private addresses; it displays the dev secrets (`/tester/credentials`, `/tester/seed-data`) and can unlock and reset accounts through the test-only routes, so never on a shared or production host and never with real values in `deploy/.env` |

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

The `ops` realm requires TOTP. On the dev stack every ops user has the pre-set code
(`scripts/totp.sh <user>`), so this does not arise there; the test console has no TOTP reset. On a host
with real operators, reset it so the next sign-in enrols again (`ops-maker01` here):

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
`/actuator/prometheus` inside the network (mw-crypto on its management port 8081: its API port
8080 is mTLS only). Find a transaction's whole story by its correlation ID
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
| `audit.anchor.broken` | mw-audit-dw | a signed daily audit anchor failed verification (reason: signature, anchor chain, anchor missing (also: its row gone while `<date>.jws` exists; that day is never re-signed), chain heads differ, export missing or differs; gauge `mw_audit_anchor_broken`; the admin console's audit view shows "Anchor check failed"): treat as an incident like a broken chain, keep `audit.anchor` and the exported `<date>.jws` files as they are |
| `audit.anchor.failed` | mw-audit-dw | the day's anchor could not be signed at mw-crypto (WARN `audit.anchor.unverifiable` when its keys cannot be read): check `mw-crypto` health, the mw-audit-dw certificate (`tls.certificate.*`) and its Keycloak client; the job retries every hour and catches up the missed days |
| `crypto.hsm.down` | mw-crypto | the HSM does not answer (gauge `crypto_hsm_up` 0): PIN checks, logins and signing fail closed with `MW-SYS-010`. Check `softhsm` health (dev) or the HSM; mw-crypto reconnects by itself (`crypto.hsm.up` when back) |
| `tls.certificate.expiring` | mw-crypto (any service with `mw.tls.enabled`) | its OpenBao service certificate has less than a third of its 72 h life left: renewal at two thirds failed (WARN `tls.certificate.renew_failed`, retried every minute; gauge `tls_certificate_expiry_seconds`). Check `openbao` health and the service's AppRole policy (`pki_int/issue/<service>`, `dc logs provision`); once the certificate expires callers fail the handshake |
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

## Security operations (mw-auth, mw-crypto; ticket 76)

### HSM down

Symptom: `crypto.hsm.down` in mw-crypto's log (gauge `crypto_hsm_up` 0); logins, PIN changes,
transactions (the assertion is signed by mw-crypto), tokens and the audit anchor fail closed with 503
`MW-SYS-050` (mw-auth) or `MW-SYS-010` (mw-crypto). Nothing is accepted that cannot be checked, and no
PIN is counted while it lasts.

1. `dc ps softhsm mw-crypto` and `dc logs --tail 50 softhsm mw-crypto`. In dev the HSM is the
   `softhsm` container (p11-kit RPC on 5657 / 5658, `deploy/softhsm/README.md`); the payShield
   simulator is in `mw-simulators` (TCP 1500, its LMK in the `paysim` token).
2. `dc start softhsm` (or `dc restart softhsm`); mw-crypto reconnects by itself within
   `hsm-probe-period` (10 s) and logs `crypto.hsm.up`. No restart of mw-crypto is needed.
3. A token that lost its keys (a `softhsm` volume dropped by hand) cannot be repaired: the dev
   bootstrap makes new keys, so every PIN, token and assertion made before is void: `deploy/reset.sh`.

### Certificate expiry (mTLS, D24)

Every internal hop to mw-crypto and mw-auth's internal API is mTLS with a 72 h certificate from
OpenBao `pki_int` that each service issues itself at start and renews at two thirds of its life.

1. WARN `tls.certificate.renew_failed` (retried every minute) then ERROR `tls.certificate.expiring`
   (less than a third left; gauge `tls_certificate_expiry_seconds`): check `dc ps openbao` (sealed or
   down?) and the service's AppRole (`dc logs provision | grep pki`).
2. Once a certificate expired, its callers fail the handshake (mw-auth: `MW-SYS-050`; the channels:
   `MW-SYS-012`). Restart the service whose certificate expired (`dc restart <service>`): it issues a
   new one at start. A stack stopped for more than 72 h does the same on the next start by itself.
3. 403 `MW-AUTH-002` with reason `CERT_MISMATCH` in mw-crypto's or mw-auth's log: the caller's token
   (`azp`) and certificate CN differ, a configuration error (Keycloak client or PKI role), not expiry.

### Key rotation and the key ceremony (ticket 75)

Keys live in the HSM; mw-crypto keeps only metadata (keyId, versions, states). Every change is
dual-control in the ops console (`/console/keys`, role `security-admin`, seeded
`seed-ops-secadmin1` / `seed-ops-secadmin2`, TOTP `scripts/totp.sh seed-ops-secadmin1`), each step with
a sign-in no older than 5 min (else 403 `MW-AUTH-031`: sign in again).

1. Rotate: one security-admin files KEY_ROTATE for the keyId with a reason, the other approves. The
   new version becomes ACTIVE, the old one VERIFY_ONLY: tokens, assertions and PINs made with it keep
   verifying (a PIN under a rotated PIN_HMAC key is re-hashed at its next correct check).
2. Retire the VERIFY_ONLY version later (KEY_RETIRE, same two-person rule) once nothing made under
   it can still arrive: the access tokens' 5 min plus the refresh tokens' 30 days for `JWT_SIGN`, 60 s
   plus the core's clock skew for `ASSERTION_SIGN`, never for a key that verifies stored data
   (`PIN_HMAC` until every wallet re-hashed, `TOKENISE_*`, `KEK_*`).
3. A top-level key (PIN_HMAC, TOKENISE_*, HMAC_*) imported from components: KEY_CEREMONY. Each
   custodian enters their own component and its check value in their own session; the request is
   approved only with both in; the KCV shown must match the paper record. A rejected or expired
   ceremony is aborted at mw-crypto.
4. mw-auth and mw-core-mock pick a new JWKS version up by themselves (an unknown kid refetches the
   set; core-mock refreshes the assertion set every 30 s).

### Wallet PIN lock and unlock

mw-auth's lockout (spec 18.7, ticket 70; knobs `MW_AUTH_LOCK_TRIES`, `MW_AUTH_LOCK_LEVEL1`,
`MW_AUTH_LOCK_LEVEL2`, `MW_AUTH_LOCK_DECAY`; production 3 tries, 1 h, 24 h, then until a reset): the
customer gets 401 `MW-AUTH-040` with `triesLeft`, then 423 `MW-AUTH-041` with `retryAfter` (levels 1
and 2) or `MW-AUTH-042` (level 3, permanent) on every channel (app, USSD, checkout).

1. Identify the customer (branch or care call). Level 1 and 2 end by themselves.
2. Unlock now: an ops-maker asks UNLOCK for the wallet (console, wallet page; `POST
   /admin/v1/wallets/{walletId}/change-requests {action: UNLOCK}`), an ops-checker approves; mw-auth
   lifts every PIN level and any ADMIN lock from `auth.wallet-lock.v1` within seconds (audit
   `auth.wallet.unlocked`).
3. Forgotten PIN: the customer resets it in the app (RESET OTP on the bound device, NID last 4 and
   birth date checked by the core), or, phone lost, care asks PIN_RESET (maker) and a checker
   approves; the customer then asks a reset OTP on the new phone within 72 h, sets the new PIN and
   binds the phone. A reset clears the PIN lock.

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

## Gate and security runs on this host

| Run | Command | Note |
| --- | --- | --- |
| Build gate | `./gradlew clean build` | with Docker running (otherwise the Testcontainers tests are skipped); best with the stack stopped: up to four modules' test PostgreSQL and Kafka run at once (`mwDockerTests`); a milestone gate adds `-PwithSlow` |
| Acceptance suite | `scripts/api-test.sh` (`scripts/api-test.sh "" 1` adds the slow folder) | on a fresh stack: `deploy/reset.sh`, then start with the seed profile (`deploy/.env` carries the test timers); under 10 minutes |
| Chaos | `scripts/chaos.sh` (or `CHAOS_TARGETS="mw-orchestrator kafka" scripts/chaos.sh`) | kills containers: never on a stack someone else is using |
| Load | `scripts/suite.sh perf` | spends the seed wallets' daily limits: fresh stack before and after |
| Security | `SECURITY_LIVE=1 scripts/security.sh` | after `scripts/api-test.sh`, so the log scan has logs to read; findings register `docs/security-findings.md` |
| Container logs only | `scripts/log-scan.sh` | no PIN or unmasked MSISDN / NID in any log |
