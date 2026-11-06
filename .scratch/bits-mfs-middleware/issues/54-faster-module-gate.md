# 54: Faster module gate: Testcontainers reuse, Gradle settings, parallel verify

**What to build:** `make verify` checks 14 modules one at a time, which takes about 1,600 s (27 min) by the recorded per-module timings. Each module also starts Postgres and Kafka Testcontainers twice, once for `integrationTest` and once for `conformance`, because they are separate test JVMs. This ticket copies the BitsPay idea of cheap, reused test infrastructure into the per-module gate, without changing what the gate checks (decided by the user on 2026-11-06, plan `check-the-project-deployment-ancient-possum`).

Do it in `templates/mw-service-template/` first, then copy it into every `services/mw-*` (duplicated by design, D13/D14).

- **Testcontainers reuse:**
  - In `TestInfrastructure`, the JVM-scoped PostgreSQL and Kafka containers get `.withReuse(true)`. Give each module its own label, e.g. `withLabel("mw.module", "<module>")`, so modules never share a container.
  - `scripts/verify.sh` and `scripts/verify-changed.sh` export `TESTCONTAINERS_REUSE_ENABLE=true`.
  - **Isolation between tasks:** `integrationTest` and `conformance` use separate database names, e.g. `<svc>_it` and `<svc>_conf`. Create them through an init script or JDBC URL per task: the test task sets a system property that `TestInfrastructure` reads.
  - **Kafka:** topics are created per test, as today. Check that no test depends on an empty broker; if one does, fix it with unique topic or group names.
  - **Clean-up:** `make verify` (no `M=`) removes the reused containers at the end with `docker rm -f $(docker ps -aq --filter label=mw.module)` unless `KEEP_TC=1`.
- **Gradle settings** in each module's `gradle.properties`:
  - `org.gradle.configuration-cache=true` (today only `mw-admin-api` and the template); fix any task that breaks it;
  - `org.gradle.parallel=true`;
  - `org.gradle.jvmargs=-Xmx1g -XX:+UseParallelGC`.
  - `org.gradle.caching=true` stays.
- **Parallel verify:** `scripts/verify.sh` runs `./gradlew check` for modules `JOBS` at a time (default 3, like `verify-changed.sh:71`). It keeps the per-module log under `build/verify/<module>.log` and prints the pass/fail and duration summary. `make verify JOBS=1` keeps the old serial behaviour.
- **Selection:** `scripts/changed-modules.sh` no longer selects ALL for changes under `deploy/**/*.md` or `docs/`. Update `scripts/test/test_changed_modules.sh` to cover it.
- **Docs:** the CLAUDE.md test table (`make verify [M=] [JOBS=]`, `KEEP_TC`), `README.md`, and the template `CLAUDE.md`. `scripts/test/test_repo_docs.sh` must stay green.

**Blocked by:**

**Status:** todo

- [ ] `make verify-changed` over all 14 modules (touch `scripts/`) finishes at least 30% faster than the last recorded 9 min, with the same tests run (compare the counts in the JUnit XML)
- [ ] In one module's `./gradlew check`, `docker ps` shows one Postgres and one Kafka container, and both `integrationTest` and `conformance` used them
- [ ] Every module passes `./gradlew check --configuration-cache` twice in a row (the second run reuses the configuration cache)
- [ ] `make test-scripts` passes, and `make verify JOBS=1 M=mw-notify` still works
