# 49: M0 cleanup: spec sync, remove static analysis, dev gaps

**What to build:** This ticket carries out the M0 review decisions (2026-11-03) that don't concern contracts.

1. **Spec sync** (`spec/upay-mfs-middleware-spec.md`):
   - §3 versions: Spring Boot 4.1.2, Camel 4.22.2 LTS, Spring Cloud 2025.1.4, Flyway 12, ShedLock 7, JUnit 6.
   - The "In the MVP vs deferred" table gains Kafka ACLs/quotas, image digest pinning, and the OpenBao root token kept in its data volume (dev only).
   - §6 says "effectively-once (idempotent producer + outbox + inbox)" instead of Kafka transactions.
   - §21.1 is the minimum log field set; extra structured fields are allowed when masked.
   - §19.2 circuit breaker reads "60 s time-based window, minimum 50 calls".
   - The ground rule and §22 no longer list static analysis as part of `make verify`.
2. **Remove static analysis:** delete SpotBugs/FindSecBugs from `templates/mw-service-template` (`build.gradle.kts`, `gradle/libs.versions.toml`, `config/spotbugs/`). Remove every mention from both CLAUDE.md files, `scripts/verify.sh` and the PR template. Keep the code fixes SpotBugs prompted.
3. **`make up` waits for health:** after `docker compose up -d`, wait until every long-running service is healthy, skipping one-shot init containers (`kafka-init`, `etcd-init`). Use a timeout and a clear failure message naming the unhealthy services.
4. **Outbox/inbox purge** in the template:
   - A scheduled job, ShedLock-guarded so only one instance runs it, deletes sent outbox rows older than `mw.outbox.retention` and inbox rows older than `mw.inbox.retention`. Both default to 7 d and are configurable.
   - Deletes run in batches, so no long lock is held.
5. **Keycloak in production mode on PostgreSQL:**
   - Keycloak runs with `KC_DB=postgres` on its own `keycloak` database and role, created at Postgres init. Credentials come from `scripts/gen-dev-env.sh`.
   - Proxy/hostname settings fit running behind APISIX, and health is enabled.
   - Realm import (with the `${KC_*}` placeholders and ops MFA) still works.

**Blocked by:**

- 06: Template part 2b: outbox, inbox, conformance suite

**Status:** done

- [x] Spec text updated for every point in item 1. No "static analysis" remains in the `make verify` ground rule
- [x] No `spotbugs` task in the template (`./gradlew tasks --all`). No SpotBugs mention in CLAUDE.md files, scripts or the PR template
- [x] `make up` returns only once all long-running services are healthy, and fails with the names of unhealthy services on timeout. Script test covers both cases
- [x] Conformance test: sent outbox rows and inbox rows older than retention are purged; recent rows and unsent outbox rows remain. The purge runs on one instance only
- [x] Keycloak runs in production mode on PostgreSQL. `make up && make smoke` passes in full on a fresh stack, including the ops-realm OTP checks
- [x] `make verify` passes
