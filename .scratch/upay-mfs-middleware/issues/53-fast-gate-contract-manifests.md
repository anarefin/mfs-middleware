# 53: Fast gate: contract manifests, test tiers, verify-changed

**What to build:** A full `make verify` takes 2–3 h, although the recorded test time across all 12 modules is only about 21 min. The rest is 12 cold Gradle builds run one after another, plus Docker contention. `./gradlew test` also runs every `*IT`. This ticket makes the per-ticket check fast and Docker-free, makes each service's produced and consumed contracts explicit, and adds a verify that runs only what a change touches. Decided by the user on 2026-11-05, before tickets 36–52.

- **Contract manifest per service:** `services/mw-<x>/contracts.yml` (inside the module; isolation holds). Paths are relative to `contracts/`, and `#<Msg>` names a message type of a multi-type topic.
  ```yaml
  produces:
    events: [events/mw-orchestrator/mw.txn.status.v1.schema.json, events/mw-orchestrator/core.command.v1.schema.json#HoldFunds]
    apis:   [api/mw-orchestrator/txn-query.v1.openapi.yaml]
  consumes:
    events: [events/mw-core-mock/core.event.v1.schema.json#FundsHeld]
    apis:   []
  ```
  Fill it in from each service's `CLAUDE.md` "Contracts owned / used" and from the code (publishers, listeners, routes, controllers, HTTP clients). Add it to `templates/mw-service-template/` (and `scaffold.sh` if needed).
- **Lint:** `scripts/contracts-lint.sh` checks that every manifest entry points to an existing schema file (and an existing payload file for `#<Msg>`). It also checks that every event schema has at least one producer and one consumer manifest, or is listed in `contracts/EXTERNAL.yml` (side played by a system outside the repo). Add `contracts/EXTERNAL.yml` only if it is needed; `contracts/` holds files only, owner mw-orchestrator in `OWNERS.yml`.
- **Fast contract tests per module** in the test package `mfs.mw.<svc>.contracts`, with no Spring context and no Docker, built on the `ContractSchemas` helper (copy from `services/mw-orchestrator/src/test/java/mfs/mw/orchestrator/contracts/ContractSchemas.java` where a module lacks one):
  - `ProducedContractsTest`: for each `produces.events` entry, build the message through the module's real mapper/DTO from a fixture, serialise it the way the publisher does, and validate it against the schema. A manifest entry without a fixture fails the test.
  - `ConsumedContractsTest`: for each `consumes.events` entry, every `contracts/.../samples/<topic>/[<Msg>/]valid*.json` deserialises into the module's own DTO. Invalid samples are rejected where the consumer validates.
  - APIs: the response DTOs of an API the module serves validate against the OpenAPI component schemas. Requests of an API it calls validate too. Existing `@WebMvcTest` slices stay.
- **Test tiers** in every module's `build.gradle.kts` and in the template:
  - `test`: excludes `**/*IT*` classes and the `conformance` tag, so it needs no Docker.
  - New `integrationTest` (verification group): runs the `*IT` classes minus conformance, after `test`.
  - `check` depends on `test`, `integrationTest` and `conformance`. The JaCoCo report and the 80% gate read execution data from all three, so coverage stays the same.
- **`make verify-changed [BASE=main] [JOBS=3]`** (`scripts/verify-changed.sh`):
  - Modules from `scripts/changed-modules.sh BASE`. A changed file under `contracts/` selects the modules whose manifests list it (producers and consumers) plus its owner from `OWNERS.yml`; a change in `common/` or `OWNERS.yml` selects all. `deploy/`, `scripts/` or root build changes still select all. Keep `e2e-changed.sh` behaviour intact: `changed-modules.sh` keeps its current output unless a new flag (e.g. `--contracts-precise`) asks for the precise mapping.
  - Runs isolation, field-injection, contracts-lint and compose-check, then `./gradlew check` for the selected modules, `JOBS` at a time (`xargs -P`). Each module writes its log to `build/verify-changed/<module>.log`, and a summary (pass/fail and duration per module) comes at the end. Exits non-zero on any failure. If the MVP compose project is running, it warns that the stack competes for Docker memory.
  - Shell self-test `scripts/test/test_verify_changed.sh`, wired into `scripts/test/run.sh`.
- **Speed fix:** the retry-topic delays (10 s / 1 min / 10 min, spec 19.2) become configurable properties with the same defaults. The test config of every module that copies `RetryTopicRouteIT` sets them to short values (e.g. 200 ms), so those ITs stop waiting for real delays.
- **Docs:** update the root `CLAUDE.md` test-commands table (`verify-changed`, the `test`/`integrationTest` split) and its definition of done. The new definition of done: `./gradlew test integrationTest` for the touched modules per ticket, `make verify-changed` + e2e at milestone gates, a full `make verify` once at the end of a milestone run. Also the template `CLAUDE.md` and each service `CLAUDE.md` "Commands" table and "Contracts owned / used" (point to `contracts.yml`).

**Blocked by:**

**Status:** ready-for-agent

- [ ] Every module has `contracts.yml`, and `make contracts-lint` validates them (the self-test covers a missing file, a missing `#Msg` and an orphan schema)
- [ ] `./gradlew test` passes in every module with Docker stopped
- [ ] `ProducedContractsTest` and `ConsumedContractsTest` exist in every module that produces or consumes events. Breaking a mapped field on purpose makes the producer test fail (show it once in the PR notes, then revert)
- [ ] `./gradlew check` still runs `test`, `integrationTest` and `conformance`, and the JaCoCo gate still passes
- [ ] `make verify-changed` with a change to a file in `contracts/events/mw-notify/` selects only the producers and consumers of that topic; `make test-scripts` passes
- [ ] `RetryTopicRouteIT` takes under 10 s in each module that has it
