# D16: One hand-written Compose file with profiles

Status: Accepted, 2026-11-06 (ticket 56, plan "BitsPay-style deployment and testing", Phase A).
Supersedes the per-module `compose.yml` rule of D12 and of spec 4.2 / 4.3.

## Context

Until ticket 56 the stack was assembled from many files: an environment project
(deploy/compose/mvp/compose.yml) that `include`d a platform file (deploy/compose/platform/compose.yml)
and one `compose.yml` per module, a low-resource override (compose.lowmem.yml) merged over it by
every make target, and a repo-root compose.yml generated from all of them by
scripts/compose-flat.sh, whose staleness `make verify` checked. Every `make up` ran `bootJar` for
13 modules one after another and rebuilt every image (`up --build`); per-module files had to mark
every cross-file dependency `required: false` so `make up M=` could start one module alone.
Reading or changing a setting meant finding which of four layers set it last.

BitsPay (the reference project) keeps its whole sandbox in one hand-written Compose file with
profiles, reuses images on `up`, and builds jars and images only on request; it starts and tests
noticeably faster.

## Decision

- The whole single-host stack is one hand-written file, `deploy/docker-compose.yml` (project name
  `mfs-mw`). No module has a `compose.yml`; `make check-isolation` fails on one, and on any file
  outside `docs/adr/` that names the old layout.
- YAML anchors hold what services share: `x-app` (hardening, restart, logs, readiness
  healthcheck, zones), `x-infra-deps` (PostgreSQL, Kafka, Keycloak and OpenBao healthy,
  `kafka-init` done), `x-jvm-env`, `x-jvm-build-args`, `x-init`.
- Profiles: none = the platform (always starts); `minimal` = the P2P and cash path (orchestrator,
  core mock, app gateway, simulators, notify, audit-dw, ussd, admin-api); `core` = every service
  and the admin UI; `seed` = the seeder one-shot (ticket 57).
- Images are `mfs-mw/<svc>:local`, built only by `make build` (`bootJar` per module, `JOBS` at a
  time, then `docker compose build`). `make up [PROFILE=] [TIMERS=fast] [M=]` is
  `docker compose --profile … up -d --wait`: no Gradle, no `--build`.
- The low-resource limits, JVM build arg and sizes (ticket 55) are the file's only settings; the
  10 GB budget for long-running limits stays (`make compose-check`).
- Test timers: `${VAR:-<production value>}` placeholders for the orchestrator's saga, inquiry and
  reversal timers, every service's retry-topic delays, the portal bulk rate and the checkout IPN
  schedule; `deploy/test-timers.env` holds short values, loaded by `TIMERS=fast`. No
  `application.yml` changes.
- Generated dev credentials move to the gitignored `deploy/.env` (still `scripts/gen-dev-env.sh`);
  Compose reads them only to interpolate the file, they are not passed into the containers.

## Consequences

- A warm `make up` starts containers only; a code change needs `make build [M=]` first (or
  `make e2e-changed`, which builds and recreates the changed modules).
- A module is no longer runnable on its own from its folder with Compose; `make up M=mw-x` starts
  it with its dependencies from the one file. `make new-service` prints the block to paste.
- Module isolation (D13, D14) is unchanged for code and builds: the stack file is deployment, not a
  build input.
- Network and volume names change with the project name (`mfs-mw_*`); old `mw_*` dev volumes are
  not reused (`make reset` starts fresh).

## Where in the repo

- `deploy/docker-compose.yml`, `deploy/test-timers.env`
- `scripts/up.sh`, `scripts/build.sh`, `scripts/seed.sh`, `scripts/down.sh`, `scripts/compose-check.sh`
- `scripts/check-isolation.sh`, `scripts/test/test_platform_compose.sh`
- spec sections 4.2, 4.3 and 20.2
