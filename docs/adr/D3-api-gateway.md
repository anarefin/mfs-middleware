# D3: Apache APISIX at the edge

Status: Accepted, 2026-11-03 (spec revision 1). Configuration format revised at the M2 gate: Admin API JSON applied by a one-shot, not ADC YAML.

## Context

Channels and partners reach the middleware over the internet. Authentication, quotas, size caps, TLS and a uniform error envelope should be enforced once, before any service.

## Decision

Apache APISIX is the only published entry point (443). It verifies Keycloak JWTs (`openid-connect`), merchant HMAC, MNO basic auth, applies per-consumer quotas (counters in Valkey), body caps, strips internal `X-Mw-*` headers, sets the correlation ID and turns every edge error into the 5.3 envelope. Its configuration lives in Git as Admin API JSON and is applied by `apisix-init` on every `make up`. Body schemas are checked in the services, never at the edge (M3 gate decision).

## Consequences

- One place to audit edge policy; `make edge-test` proves it on the running stack.
- Partner callback signatures are verified by the adapters (partner-specific formats), APISIX keeps allow-lists, caps and quotas.
- The rate limiter fails open when Valkey is down and logs an alert event.

## Where in the repo

- `deploy/apisix/README.md` (routes, policies, error codes, format decision)
- `scripts/edge-test.sh`
- spec section 8
