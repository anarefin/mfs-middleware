# 56: One hand-written compose file with profiles; build, up, seed and reset targets

**What to build:** Replace the included compose layout with one hand-written `deploy/docker-compose.yml`, modelled on BitsPay's `~/Desktop/BitsPay/deploy/docker-compose.yml`. The current layout is `deploy/compose/mvp` plus every `services/*/compose.yml`, `compose.lowmem.yml`, and the generated root `compose.yml`. Images are reused unless you ask for a build. `make up` no longer runs Gradle. The user decided this on 2026-11-06 (plan `check-the-project-deployment-ancient-possum`, Phase A steps 1–3). It supersedes the spec rule that every module has its own `compose.yml`.

- **`deploy/docker-compose.yml`:**
  - `name: mfs-mw`;
  - anchors `x-app` (restart, `env_file: [.env]`, network) and `x-infra-deps` (postgres, kafka and keycloak healthy, `kafka-init` and the OpenBao init completed);
  - **Profiles:**
    - no profile: platform services and one-shots;
    - `minimal` (`[minimal, core]`): the P2P and cash path — mw-orchestrator, mw-core-mock, mw-app-gateway, mw-simulators, mw-notify, mw-audit-dw (check against `tests/e2e` `@p2p`/`@cash` dependencies);
    - `core`: every mw-* service and mw-admin-ui;
    - `seed`: mw-seeder (ticket 57; leave a commented stub until it exists).
  - **App services:** `build: ../services/mw-x` and `image: mfs-mw/mw-x:local`. Each `<svc>-init` comes from the old per-module `compose.yml`. Carry over every env var, secret mount, port, label and memory limit from the old `mvp` and lowmem files. `docker compose config` of the new and old stacks must be equal in services, env, ports and limits; diff them in the PR, apart from the intended changes.
  - **Test timers:** env placeholders whose defaults are the current production values:
    - orchestrator: `posting/holding timeout`, `partner-timeout`, the inquiry schedule, NOT_FOUND grace, `reversing-timeout`;
    - retry topic delays (already configurable since ticket 53);
    - portal-api bulk `rate-per-second`;
    - checkout IPN retry delays.

    Find the property names in each `application.yml`; Spring relaxed env binding is fine. New file `deploy/test-timers.env` holds the short values (about 2 s inquiry, 5 s reversal, 1 s retry, small bulk rate cap). Nothing in `application.yml` changes.
- **Remove:**
  - `deploy/compose/mvp/` and `compose.lowmem.yml`;
  - the root `compose.yml` and `scripts/compose-flat.sh`;
  - every `services/*/compose.yml` and `ui/mw-admin-ui/compose.yml`, plus the template's `compose.yml` and its `scaffold.sh` step. `make new-service` instead prints the block to paste into `deploy/docker-compose.yml`.

  Move `deploy/compose/platform/.env` handling to `deploy/.env`; `gen-dev-env.sh` writes it, still gitignored. Fix `.gitleaks.toml` and `.trivyignore.yaml` paths.
- **Makefile and scripts:**
  - `make build [M=] [JOBS=3]`: `bootJar` per selected module, `JOBS` at a time (one Gradle build each), then `docker compose build [svc]`;
  - `make up [PROFILE=core] [TIMERS=fast] [M=]`: `gen-dev-env.sh`, then `docker compose --profile $PROFILE [--env-file deploy/.env --env-file deploy/test-timers.env] up -d --wait [mw-x]`. No Gradle, no `--build`; `scripts/up.sh`'s poll loop goes away;
  - `make seed`: `docker compose --profile core --profile seed up -d mw-seeder`, then `docker compose wait mw-seeder`, which must exit 0;
  - `make down`;
  - `make reset`: `down -v --remove-orphans` across every profile (BitsPay `deploy/reset.sh`);
  - `make compose-check`: memory budget check on `deploy/docker-compose.yml` with `--profile core` (no staleness check any more);
  - update `smoke`, `edge-test`, `e2e`, `e2e-changed` (build plus `up M=` for changed modules, then the suite), `chaos`, `security`, `log-scan` and `perf` to the new file and project name. The `tests/e2e` and `tests/perf` `Stack` helpers that call `docker compose -f deploy/compose/mvp/compose.yml` move to `deploy/docker-compose.yml`.
  - `check-isolation.sh`: a module may no longer have `compose.yml`, and nothing references `deploy/compose/`.
- **Docs:**
  - new ADR `docs/adr/D16-single-compose-file.md` (or the next free number), recording that it supersedes the per-module `compose.yml` rule of spec 4.2/4.3, and the matching spec row;
  - the root `CLAUDE.md` (layout bullet, test table), `README.md` (quick start = `make build && make up && make seed`), `docs/runbook.md`, `docs/demo.md`, `deploy/apisix/README.md`, `MAP.md`, and every service `CLAUDE.md` "Commands" table. `scripts/test/test_repo_docs.sh` must stay green.

**Blocked by:** 55

**Status:** todo

- [ ] `make reset && make build && make up` brings up the whole stack. `make up` a second time without `make build` starts in under 3 min, and no image is rebuilt
- [ ] `make up PROFILE=minimal` starts only the platform and the minimal services, and `make e2e TAGS=@p2p` passes on it
- [ ] `make up TIMERS=fast` changes the inquiry delay; check it through the orchestrator's actuator `env` or a log line, not by reading code
- [ ] `make compose-check`, `make smoke`, `make edge-test`, the full `make e2e` (on a fresh stack), `make test-scripts` and `make contracts-lint` pass; `git grep deploy/compose/` finds nothing outside the ADR
