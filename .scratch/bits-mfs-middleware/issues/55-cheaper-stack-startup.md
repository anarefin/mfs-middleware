# 55: Cheaper stack startup: AOT flags, Kafka init, Keycloak, init containers

**What to build:** Cheaper stack startup, in the **current** compose layout (ticket 56 moves the layout afterwards). Today:
- the AOT cache baked into each image is probably rejected at runtime;
- `kafka-init` starts 92 JVMs on every `up`, and there are 3,072 partitions;
- Keycloak re-augments on every start;
- the init containers poll with `sleep 2`.

This is plan `check-the-project-deployment-ancient-possum`, Phase A step 4 (user, 2026-11-06).

- **AOT cache:**
  - The Dockerfile trains with `-XX:+UseZGC -XX:MaxRAMPercentage=75` (`services/*/Dockerfile:24-34`), but `deploy/compose/mvp/compose.lowmem.yml:35` replaces `JAVA_TOOL_OPTIONS` with SerialGC/C1 flags. First confirm with `-Xlog:aot` (or `-Xlog:cds`) in one running container whether the cache is rejected; put the log line in the PR.
  - Then make training and runtime use the same flags. Put the runtime flags in the Dockerfile as `ARG`/`ENV`, used by both the training run and the entrypoint; lowmem then only sets memory limits and `-Xms/-Xmx` that the cache accepts.
  - BitsPay pattern: fixed heap (`-Xms=-Xmx`, 192–256m), `-XX:+ExitOnOutOfMemoryError`, normal tiered JIT. Drop `TieredStopAtLevel=1` and `CICompilerCount=1` only if the 10 GB budget (`make compose-check`) still holds.
  - Template first, then the 13 services.
- **Kafka topics** (`deploy/kafka/apply-topics.sh`, `deploy/kafka/topics.txt`):
  - one JVM: either a single `kafka-topics.sh --bootstrap-server … --create --if-not-exists` call per topic run inside one `kafka-run-class` script, or (preferred) one small batch that first lists existing topics and configs once (`kafka-topics --describe` / `kafka-configs --describe --all`), then creates only the missing topics and alters only the configs that differ;
  - after a successful pass, write a done marker with a hash of `topics.txt` to the kafka-init volume; when the hash matches, exit 0 at once;
  - partition count from env: `MW_KAFKA_PARTITIONS`, default 3 for the dev stack, keeping `topics.txt` values as the production reference. Scripts self-test: `scripts/test/test_apply_topics.sh` (dry-run mode).
- **Keycloak:**
  - New `deploy/keycloak/Dockerfile`, `FROM` the pinned Keycloak image: copy the providers and config, run `kc.sh build` with the same build-time options, then `ENTRYPOINT kc.sh start --optimized --import-realm`;
  - compose uses `build:` with the image tag `mfs-mw/keycloak:local`;
  - add the image to the trivy image list of `make security`.
- **Init containers** (`services/*/init/init.sh`):
  - replace the `pg_isready` / `bao status` `sleep 2` loops with compose `depends_on` (`postgres: service_healthy`, `openbao: service_healthy`, and the OpenBao init one-shot `service_completed_successfully`);
  - keep a single short retry only for the "raced another init" case;
  - each init exits 0 quickly when its schema, roles and secrets already exist.
- **Startup order:** every mw-* app `depends_on: kafka-init: service_completed_successfully`.
- **Healthchecks:** interval 5s; Kafka uses a TCP check where the JVM CLI isn't needed (BitsPay uses bash `/dev/tcp`).

**Blocked by:** 54

**Status:** todo

- [ ] The PR shows the AOT log before (rejected) and after (cache used) for one service
- [ ] A second `make up` with nothing changed (`MW_UP_NO_BUILD=1`) is ready at least 50% faster than before; record both times. `kafka-init` exits in under 5 s on the second run
- [ ] `make compose-check` passes (≤10 GB long-running limits)
- [ ] `make smoke`, `make edge-test` and the full `make e2e` pass on a fresh stack (`make down DOWN_ARGS=-v && make up`)
- [ ] `make test-scripts` passes
