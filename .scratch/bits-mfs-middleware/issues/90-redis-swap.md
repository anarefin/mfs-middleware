# 90: Replace Valkey with Redis (FRS 1.4, ADR D29)

**What to build:** Swap the `valkey` container for a pinned Redis image (tag from D29) everywhere, with no behaviour change:
sessions, idempotency locks, nonces, OTP quotas and APISIX `limit-count` keep working on the same keys.

**Scope:**
- `deploy/docker-compose.yml`: service `valkey` -> `redis` (image from D29, same memory knob renamed `REDIS_MEM_LIMIT`, healthcheck
  `redis-cli ping`, volume); `MW_PORT_VALKEY` -> `MW_PORT_REDIS` (6379); `deploy/dev.env.defaults` knobs and password.
- Every service config that names Valkey: `services/mw-app-gateway`, `mw-auth`, `mw-orchestrator`, `mw-ussd`
  (`src/main/resources/application.yml`, host `redis`); rename classes for clarity where they carry the name
  (`mw-orchestrator` `config/ValkeyConfig`, `client/ValkeyIdempotencyLock` -> `Redis*`), tests and Testcontainers images.
- APISIX: `deploy/apisix/config.yaml` / route `limit-count` `redis_host`; `deploy/apisix/README.md`.
- Scripts and docs: `scripts/smoke.sh` (Redis check), `scripts/creds.sh`, `README.md`, `docs/runbook.md`,
  `docs/testing-guide.html`, `docs/concepts/` (rename the Valkey page), `deploy/RUNNING.html`, root `CLAUDE.md` dev ports line.
- `deploy/trivyignore-third-party.yaml`: drop Valkey entries; scan the Redis image.

**FRS IDs covered:** 1.4 (cache / sessions / locks: Redis), PLT-004 (Redis token store)

**Blocked by:** 89

**Status:** open

- [ ] A repo grep test in `scripts/test/` fails while `valkey` appears outside ADRs/history, then passes
- [ ] Each touched module's Testcontainers ITs run on the Redis image (`IdempotencyGate`, session, nonce ITs) — `./gradlew :mw-app-gateway:build :mw-auth:build :mw-orchestrator:build :mw-ussd:build` green with Docker
- [ ] `./gradlew :composeCheck :scriptTests` green
- [ ] Fresh stack (`deploy/reset.sh`, `up` core+seed): `scripts/smoke.sh`, `scripts/edge-test.sh` (429 path) and `scripts/api-test.sh "01, 02, 18, 24"` pass
