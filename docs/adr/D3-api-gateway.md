# D3: Apache APISIX at the edge

Status: Accepted, 2026-11-03 (spec revision 1). Configuration format revised at the M2 gate: Admin API JSON applied by a one-shot, not ADC YAML.
Amendment (2026-11-13, BitsPay deploy alignment): the dev stack also publishes host ports on
127.0.0.1 only: every `mw-*` service (8081-8093), the admin UI (3000), the test console (3001) and
the platform (PostgreSQL 5432, Valkey 6379, Kafka 9094, Keycloak 8180, OpenBao 8200, GreenMail
8025/3025), each a `MW_PORT_<NAME>` knob in `deploy/docker-compose.yml`, for local debugging. A
direct port skips every edge policy. APISIX 443 stays the edge and the only port reachable beyond
loopback; etcd and the APISIX Admin API stay unpublished. Production stays edge-only.

## Context

Channels and partners reach the middleware over the internet. Authentication, quotas, size caps, TLS and a uniform error envelope should be enforced once, before any service.

## Decision

Apache APISIX is the only published entry point (443; the dev stack adds loopback-only ports, amendment above). It verifies Keycloak JWTs (`openid-connect`), merchant HMAC, MNO basic auth, applies per-consumer quotas (counters in Valkey), body caps, strips internal `X-Mw-*` headers, sets the correlation ID and turns every edge error into the 5.3 envelope. Its configuration lives in Git as Admin API JSON and is applied by `apisix-init` on every `make up`. Body schemas are checked in the services, never at the edge (M3 gate decision).

## Consequences

- One place to audit edge policy; `scripts/edge-test.sh` (originally `make edge-test`) proves it on the running stack.
- Partner callback signatures are verified by the adapters (partner-specific formats), APISIX keeps allow-lists, caps and quotas.
- The rate limiter fails open when Valkey is down and logs an alert event.

## Where in the repo

- `deploy/apisix/README.md` (routes, policies, error codes, format decision)
- `scripts/edge-test.sh`
- spec section 8
