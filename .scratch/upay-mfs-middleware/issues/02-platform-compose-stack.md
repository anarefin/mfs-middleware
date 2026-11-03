# 02: Platform Compose stack

**What to build:** `make up` brings up the whole platform as single-instance containers under Docker Compose (D11, D12): Kafka in KRaft mode, a one-shot `kafka-init` that creates the topics from §6.2 as code, PostgreSQL 18, Valkey, Keycloak with the `ops`, `portals`, `customers` and `partners` realms imported, OpenBao with a dev-unseal script, APISIX + etcd, and GreenMail. The zone networks are `dmz`, `app`, `data` and `ops`. An MVP Compose project `include`s the platform file, and module compose files will be added to it later. Spec WP02.

**Blocked by:**

- 01: Repo skeleton and local quality gate

**Status:** done

- [x] `docker compose up` on the MVP project reaches healthy on every platform container
- [x] Every MVP topic in §6.2 (incl. `.retry.<n>` and `.dlq` variants) exists after `kafka-init`, with RF 1 and the catalogue's partition counts
- [x] Keycloak realms import with test users for each role used later (customer, agent, merchant, org maker/checker, ops roles)
- [ ] Only APISIX (443) and the SMPP/USSD port are published on the host
  - Not ticked: smoke checks that only APISIX is published (`only apisix:9443 is published`), but no SMPP/USSD port exists yet. It will be published later by mw-ussd / mw-simulators (see the platform compose.yml header).
- [x] A smoke script checks Kafka produce/consume, a PG connection, a Valkey ping, a Keycloak token, an OpenBao KV read and an APISIX health route, and it passes
