# D16: One hand-written Compose file with profiles

Status: Accepted, 2026-11-06 (ticket 56, plan "BitsPay-style deployment and testing", Phase A).
Supersedes the per-module `compose.yml` rule of D12 and of spec 4.2 / 4.3.
Amended 2026-11-13 (D18): no Makefile and no wrapper scripts; images are built by
`docker compose … up -d --build` from the jars of `./gradlew clean build`.
Amendment (2026-11-13, BitsPay deploy alignment): no `--env-file` flags; Compose reads
`deploy/.env` by itself, a copy of the committed `deploy/dev.env.defaults`, which also carries the
short test timers (the former test-timers.env and `TIMERS=fast` are gone). The dev stack also
publishes loopback host ports (ADR D3 amendment).

## Context

Until ticket 56 the stack was assembled from many files: an environment project
(deploy/compose/mvp/compose.yml) that `include`d a platform file (deploy/compose/platform/compose.yml)
and one `compose.yml` per module, a low-resource override (compose.lowmem.yml) merged over it by
every Makefile target, and a repo-root compose.yml generated from all of them by
a compose-flat.sh script, whose staleness `make verify` checked. Every `make up` ran `bootJar` for
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
  `kafka-init` and `provision` done), `x-jvm-env`, `x-jvm-build-args`.
- Dev provisioning is one one-shot, `provision` (`deploy/provision`: `provision.sh`, the steps in
  `lib.sh`, the central manifest `services.sh`), not one init container per module (BitsPay keeps
  only platform one-shots too). For every service in manifest order it writes `secret/<svc>` in
  OpenBao (generated values once, `.env` values on every run, the core's PIN public key copied),
  the service's database role(s) and schema, and its AppRole, whose role-id/secret-id go to that
  service's own `<svc>-approle` volume. Least privilege is unchanged: each service mounts only its
  own volume, reads only its own secret and logs in as its own role; only `provision` holds the
  OpenBao root token and the PostgreSQL admin password, and it exits when done. Roles stay in
  `provision` (not `docker-entrypoint-initdb.d`), so database passwords never leave OpenBao and a
  new service needs no `make reset`. One image (`mfs-mw/provision:local`) replaces 13.
- The platform one-shots stay separate: `kafka-init` (Kafka CLI image), `etcd-init` (before
  `apisix` starts) and `apisix-init` (after `apisix` is healthy, re-run alone by `make api-test`)
  need different images at different points of the start order. `postgres` runs under tini
  (`init: true`), which reaps timed-out healthcheck processes.
- Profiles: none = the platform (always starts); `minimal` = the P2P and cash path (orchestrator,
  core mock, app gateway, simulators, notify, audit-dw, ussd, admin-api); `core` = every service
  and the admin UI; `seed` = the seeder one-shot (ticket 57).
- Images are `mfs-mw/<svc>:local`. Originally they were built only by `make build` (`bootJar` per
  module, `JOBS` at a time, then `docker compose build`), and `make up [PROFILE=] [TIMERS=fast] [M=]`
  was `docker compose --profile … up -d --wait`: no Gradle, no `--build`. Since 2026-11-13 (D18)
  there is no Makefile: `./gradlew clean build` writes one fixed-name jar per module
  (`build/libs/mw-x.jar`), and from `deploy/` the documented command
  `docker compose --profile core --profile seed up -d --build --wait` builds the images from those jars and starts the stack (`deploy/RUNNING.html`). Compose never
  runs Gradle.
- The low-resource limits, JVM build arg and sizes (ticket 55) are the file's only settings; the
  10 GB budget for long-running limits stays (`make compose-check`).
- Test timers: `${VAR:-<production value>}` placeholders for the orchestrator's saga, inquiry and
  reversal timers, every service's retry-topic delays, the portal bulk rate and the checkout IPN
  schedule. Originally a separate test-timers.env held short values, loaded by `TIMERS=fast`;
  since the 2026-11-13 amendment the short values are in `deploy/dev.env.defaults` (so in
  `deploy/.env`), and the file's defaults stay the production values. No `application.yml` changes.
- Memory limits are knobs with the laptop values as defaults (`APP_MEM_LIMIT`, `KAFKA_MEM`, ...;
  2026-11-13), listed in the file's header.
- Dev credentials move to the gitignored `deploy/.env` (originally generated by a script; since
  2026-11-13 a copy of the committed `deploy/dev.env.defaults`, all fixed, obviously fake values:
  ADR D9 amendments);
  Compose reads them only to interpolate the file, they are not passed into the containers.

## Consequences

- Originally a warm `make up` started containers only and a code change needed `make build [M=]`
  first (or the `e2e-changed` target of the former Cucumber suite). Since 2026-11-13 a code change is
  `./gradlew :mw-x:bootJar`, then `docker compose … up -d --build mw-x` from `deploy/`.
- A module is no longer runnable on its own from its folder with Compose; `docker compose up mw-x`
  (originally `make up M=mw-x`) starts it with its dependencies from the one file.
  `scripts/new-service.sh` (originally `make new-service`) prints the blocks to paste (the
  service, its approle volume, its `deploy/provision/services.sh` entry).
- A new service edits a deploy/ file for its provisioning (`services.sh`), as it already does for
  its compose block; the module itself carries no init step.
- Module isolation (D13, D14) is unchanged for code and builds: the stack file is deployment, not a
  build input.
- Network and volume names change with the project name (`mfs-mw_*`); old `mw_*` dev volumes are
  not reused (`deploy/reset.sh`, originally `make reset`, starts fresh).
- The `provision` one-shot runs on every `up` and is idempotent, so a new service's schema appears
  without wiping the volumes.

## Where in the repo

- `deploy/docker-compose.yml`, `deploy/dev.env.defaults`, `deploy/provision/`
- `deploy/reset.sh`, `deploy/RUNNING.html`, `scripts/compose-check.sh` (originally also the up, build,
  seed and down wrapper scripts, removed on 2026-11-13 with the Makefile: D18)
- `scripts/check-isolation.sh`, `scripts/test/test_platform_compose.sh`, `scripts/test/test_provision.sh`
- spec sections 4.2, 4.3 and 20.2
