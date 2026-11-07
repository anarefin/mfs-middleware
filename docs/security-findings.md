# Security findings register (ticket 46, spec WP29)

`make security` (scripts/security.sh) is the security pass. This register holds the decision
for every finding it reports: the fixes made, the HIGH/CRITICAL findings accepted (with the
scanner ignore entry that carries the same reason and an expiry), and the decisions for every
finding below high. Last full offline run: 2026-11-06, trivy 0.67.2 (DB of that day), gitleaks
8.28.0, images built from this branch (tag `sec46`). The live stages (ZAP baseline, container
log scan) need the running stack and are run by the milestone gate (`make up TIMERS=fast && make seed && make api-test &&
SECURITY_LIVE=1 make security`).

## How the gate reads it

| Stage | Fails on | Accepted findings live in |
| --- | --- | --- |
| secrets (gitleaks, files not gitignored) | any leak | `.gitleaks.toml` (narrow fixture allow-list) |
| deps (CycloneDX SBOM + trivy per module, trivy fs for lockfiles) | HIGH, CRITICAL | `.trivyignore.yaml` |
| own images (`mfs-mw/mw-*:local`, `mfs-mw/mw-*-init:local`, `mfs-mw/mw-admin-ui:local`; OS packages without a fix skipped) | HIGH, CRITICAL | `.trivyignore.yaml` |
| third-party images pinned in `deploy/docker-compose.yml` | HIGH, CRITICAL | `deploy/trivyignore-third-party.yaml` |
| ZAP baseline on APISIX (`https://apisix:9443/`, `/console/`, from the dmz network) | High, any alert without a decision, any `fix` alert | `deploy/zap/decisions.tsv` |
| log scan (`scripts/log-scan.sh`, every container of the project) | any PIN, PIN ciphertext, unmasked MSISDN or NID | `scripts/log-scan.allow` (empty) |

Every ignore entry has a statement and an `expired_at` (2027-02-01); after that date the scan
fails again and the entry must be re-checked (`test_security.sh` enforces statement + expiry).

## Fixed in this pass

| Finding | Where | Fix |
| --- | --- | --- |
| HIGH CVE-2026-89407, -89425 (jackson-core), -68497, -91776, -91777 (jackson-databind) 2.22.1 | every Gradle module (Jackson 2 comes through Kafka / Camel; the services use Jackson 3) | `jackson-bom` 2.22.3 platform (`security-jackson2` in each catalog) |
| HIGH CVE-2026-106451 lz4-java 1.11.1 | every module with kafka-clients | constraint 1.11.4 |
| HIGH CVE-2026-108106, -93451 snappy-java 1.1.10.7 | every module with kafka-clients | constraint 1.1.10.9 |
| CRITICAL CVE-2026-111497 brotli, HIGH CVE-2026-92392 libcurl (Alpine) | the dev provisioning image `mfs-mw/provision` (formerly every `mw-*-init` image) and platform PostgreSQL | postgres:18.6-alpine re-pinned to the 2026-11-05 digest; the provisioning image runs `apk upgrade` |
| CRITICAL/HIGH Go stdlib (26 CVEs) in gosu | the dev provisioning image `mfs-mw/provision` (formerly every `mw-*-init` image) | gosu removed (the provisioning entry point never uses it) |
| CRITICAL GHSA-2464-fhr4-w9xw, HIGH CVE-2026-94482 next 16.3.8 | mw-admin-ui | Next.js 16.4.1 (minor; spec D6 and section 3 updated to 16.4.1 by ticket 47) |
| HIGH undici 6.28.0, brace-expansion 5.0.9 (npm bundled in node:24-alpine) | mw-admin-ui image | npm, npx and corepack removed from the runtime image; `apk upgrade` |
| CRITICAL/HIGH openssl, libssh2, musl, nghttp2, zlib (curl 8.17.0, Alpine) | etcd-init, smoke and edge-test helper | curlimages/curl 8.23.0 (pinned by digest) |
| ZAP 10035 HSTS, 10021 nosniff, 10020/10038 on edge-produced errors, 10036 server version | APISIX edge | `lua/edge-security-headers.lua` in the global rule; `enable_server_tokens: false` |
| Unmasked MSISDN in the APISIX access log (M6 gate log scan: 1184 lines, e.g. `POST /admin/v1/wallets/<MSISDN>/change-requests`, `GET /test/core-mock/v1/wallets/<MSISDN>`, `?msisdn=` queries, the number repeated in the upstream URI) | APISIX nginx access log (raw `$request`, `$http_referer`, `$upstream_uri`) | `config.yaml` access log format logs masked copies: query string dropped, MSISDN to `0171****789` (`deploy/apisix/README.md` "Access log masking"); takes effect when APISIX restarts |
| Log scan false positive: an 11-digit `01…` run inside a system UUID (txnId `01a1ae14-…-c01653930180`, 5 lines in mw-orchestrator / mw-core-mock) | `scripts/log-scan.sh` | system ids set aside by shape before the MSISDN rule (root `CLAUDE.md` "Masking"): a whole UUID, a traceparent value, the 32/16-hex value of a traceId/spanId field; digits next to one still count |

## HIGH / CRITICAL accepted (no fixed upstream release)

All have no fixed upstream image or release as of 2026-11-06 (latest tags re-scanned). The
platform images stay pulled by digest (spec 20.1); re-pin when upstream rebuilds.

| Finding | Image | Why it is not exploitable here |
| --- | --- | --- |
| perl-base CVE-2026-13221, -42496, -8376 (CRITICAL), -42497, -48962, -57432, -57433 | apache/apisix:3.19.0-debian | perl only serves Debian maintainer scripts; APISIX is OpenResty + Lua |
| libssl3/openssl CVE-2026-84782 (DTLS) | apisix | no DTLS listener; the edge's TLS uses the OpenSSL bundled in apisix-runtime |
| pcre2 CVE-2026-103111 | apisix | regexes come from our route config only |
| apt CVE-2026-109518 | apisix, greenmail; every service image (eclipse-temurin:25.0.5_8-jre-noble, newest Temurin 25 JRE; the images stopped running `apt-get upgrade` for the laptop footprint, `.trivyignore.yaml`) | the running containers never run apt |
| brotli CVE-2026-111497 (CRITICAL) | apache/kafka:4.3.2, greenmail 2.1.15 | JVM services never load the native libbrotli |
| libexpat CVE-2026-113351, -117610, -118370, -77214 | apache/kafka:4.3.2 | Kafka parses no XML with the native expat |
| guava CVE-2026-102554 | keycloak 26.8.1 (26.8.2 ships the same guava) | Keycloak deserializes no Guava objects from requests |
| go-jose CVE-2026-116587 | openbao 2.7.2 (latest) and the bao CLI in `provision` | no JWT/OIDC auth method is enabled (AppRole + token); the CLI parses no foreign JWK |
| Go stdlib in gosu (26 CVEs) | platform postgres:18.6-alpine | gosu runs once in the entrypoint to drop root, opens nothing, parses nothing |

## Secrets

gitleaks (default rules) flagged 25 test fixtures and nothing else: UUID idempotency keys in
contract samples, `partner/<id>` config keys, the `tok_abcdef1234` card-token masking sample, a
key generated inside `PinCipherTest`, base64 of "not a key" in `RequestSignaturesTest`, and the
`k3yFromDotEnv` / `s3cretFromDotEnv` placeholders in `test_apisix_edge.sh`. Decision: allow-list
each exact fixture shape in `.gitleaks.toml` (a real secret beside one is still found). The scan
covers the files git would commit (tracked + untracked, not ignored); the generated dev
credentials (`deploy/.env`, gitignored) are out of scope by design. History is
not rescanned (decision: the working tree is what ships; a history scan with the gitleaks binary
is `gitleaks git` and can be added to the gate).

## Containers: non-root, read-only root FS

Every long-running container built here (12 services, mw-admin-api, mw-admin-ui) runs as
`10001:10001`, `read_only: true` with a tmpfs `/tmp`, `cap_drop: [ALL]`, `no-new-privileges`
(`test_own_long_running_containers_are_non_root_and_read_only` checks `deploy/docker-compose.yml`).
Decisions for the rest:

- The `provision` one-shot (dev/MVP only; it replaced the 13 `mw-*-init` ones): runs as root on a
  writable FS, with `no-new-privileges`; it creates database roles and writes each service's AppRole
  files into that service's named volume, then exits. Accepted for
  the MVP; production provisions roles and AppRoles outside Compose.
- Third-party platform images keep their image user: APISIX `apisix`, Kafka `appuser`,
  Keycloak `1000`, OpenBao `openbao`, GreenMail `greenmail` (all non-root) with a writable root
  FS, because they write state or generated config into it at start; PostgreSQL and Valkey start
  as root and drop to their own user in the entrypoint, etcd runs as root (internal `etcd`
  network only); those three and the platform one-shots already run read-only. Accepted for the
  MVP; read-only roots for APISIX, Kafka, Keycloak, OpenBao and GreenMail (tmpfs for their
  write paths) and a non-root etcd user are post-MVP hardening items.

## Live stages (run by the gate)

- ZAP baseline: `deploy/zap/decisions.tsv` lists the decision for every passive alert a baseline
  can raise on this edge (High always fails; `fix` alerts fail because the edge or service sets
  the header; `accept` alerts carry their reason). An alert not listed fails the gate: add its
  decision there, with this register updated. The pipeline (zap-baseline.py, JSON report,
  `scripts/lib/zap-gate.py`) was checked against a dummy target; against APISIX it runs at the gate.
- Log scan: `scripts/log-scan.sh` over every container log after the full `make api-test` run. Rules
  in the script header; a match is printed masked. Self-tests in `scripts/test/test_security.sh`.

## Below high

Scope: every SBOM, lockfile and image above, MEDIUM/LOW/UNKNOWN, OS packages without a fix
left out for images (same `--ignore-unfixed` decision as the gate). Reports:
`build/security/*-below-high.json` (regenerated by every `make security`). Result of the 2026-11-06
run: **no finding below high in any service dependency (SBOMs), the admin UI lockfile, or the
service and admin UI images.** What remains is in the dev-only init images (the OpenBao CLI) and the
pinned third-party images:

| Package | Ids (M medium, L low, U unknown) | Where | Decision |
| --- | --- | --- | --- |
| go-jose v4 | CVE-2026-116585 (M), CVE-2026-116586 (M) | bao CLI in `provision`, openbao | Accept until OpenBao ships go-jose >= 4.1.6 (same reason as the HIGH one: no JWT/OIDC auth, CLI parses no foreign JWK) |
| golang.org/x/crypto, x/net | GO-2026-5932 (U), CVE-2026-78661 (U), CVE-2026-97045 (U) | bao CLI in `provision`, etcd | Accept: dev-only CLI on the internal network; etcd sits on the internal `etcd` network shared only with APISIX; re-pin with upstream |
| Go stdlib (33 ids) | CVE-2025-47912, -58183, -58185, -58186 (L), -58187, -58188, -58189, -61723, -61724, -61725, -61727, -61728, -61730, CVE-2026-27139 (L), -27142, -32282, -32288, -32289, -39823, -39825, -39826, -42505, -42507, -56857 (L), -56866, -78659, -78660, -78663, -94439, -94440, -94448, -97030, -97032 (M unless marked) | gosu in postgres:18.6-alpine | Accept: gosu only drops root once in the entrypoint (as the HIGH ones); removed from the provisioning image |
| github.com/moby/sys/user, golang.org/x/sys | CVE-2026-61801 (M), CVE-2026-39824 (U) | gosu in postgres | Accept: same as above |
| perl-base | CVE-2025-15649, CVE-2026-12087, -19487, -48959, -7010, -7017 (M) | apisix | Accept: perl is not on any request path (as the HIGH ones) |
| openssl, libssl3 | CVE-2026-35189 (L), CVE-2026-54872, -75805, -75806, -77696 (M) | apisix | Accept: the edge's TLS uses the OpenSSL bundled in apisix-runtime, not Debian's libssl3; re-pin with upstream |
| libsasl2-2, libsasl2-modules-db | CVE-2026-107161 (M) | apisix | Accept: no SASL use at the edge |
| ca-certificates, tzdata | DLA-4845-1, DLA-4792-1, DLA-4854-1, DLA-4867-1 (U) | apisix, etcd | Accept: data-package refreshes; the edge trusts only its configured upstreams (internal network); re-pin with upstream |
| libexpat | CVE-2026-102633 (M), CVE-2026-115506 (U) | kafka | Accept: no native XML parsing in Kafka (as the HIGH ones) |
| musl, musl-utils | CVE-2026-107891 (U) | kafka | Accept: re-pin with the next apache/kafka digest; the broker is on the internal `app` network only |
| mariadb-java-client | CVE-2026-119210, -119276, -119281 (M), -119280 (L) | keycloak | Accept: Keycloak runs on PostgreSQL; the MariaDB driver is never loaded |
| opentelemetry-api | CVE-2026-118259 (M) | keycloak | Accept: Keycloak tracing is not enabled in the MVP; upgrade with Keycloak |

The secret scanner inside the trivy image scan also flags two upstream sample keys in the APISIX
image (`/usr/local/apisix/conf/cert/ssl_PLACE_HOLDER.key`, the placeholder certificate nginx needs
before an SSL object matches, and a key in the lua-resty-rsa docs). Decision: skipped by path for
the third-party scan only (`scripts/security.sh`); the edge serves the certificate applied by
`deploy/apisix/apply.sh` (`fallback_sni: localhost`), never the placeholder.

ZAP and log-scan findings below high: decided per alert in `deploy/zap/decisions.tsv` (the gate
fails on an alert without a decision); the log scan has no "below high": any finding fails.
