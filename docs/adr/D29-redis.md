# D29: Redis instead of Valkey

Status: Accepted, 2026-11-19 (user decision, FRS planning, ticket 89). Amends D12, M7 and spec 3
(the cache / locks row); built by ticket 90.

## Context

The MVP runs Valkey 8.1 (BSD-3), a Redis-compatible fork, for the USSD sessions, the APISIX
`limit-count` counters, the mw-auth sessions, nonces and assertion ids, and the short-lived
idempotency cache. The Upay FRS (`spec/FRS-Upay-Middleware.md` 1.4) names Redis for
"cache / sessions / locks" and PLT-004 asks for a token bucket in Redis. Every client in the repo
speaks the Redis protocol (Lettuce through Spring Data Redis, APISIX's `redis` policy, `redis-cli`
style health checks), so the swap is an image and naming change, not a code change.

Redis changed its licence twice: Redis 7.2.x and earlier are BSD-3; Redis 7.4 moved to RSALv2 /
SSPLv1; Redis 8.0 and later are offered under RSALv2, SSPLv1 or AGPLv3, at the user's choice. The
spec (3, OWN-03) allows no GPL/AGPL component without written approval.

## Decision

- **Redis replaces Valkey** everywhere the stack has a key-value store: one container named `redis`,
  the same role as `valkey` today (single instance, no cluster, D11), a password from
  `deploy/dev.env.defaults` in dev and from Vault (D30) otherwise.
- **Image:** `redis:8.10.3-alpine`, the latest stable Redis release on 2026-11-19 (8.12 is still a
  release candidate), taken under **AGPLv3**. The middleware runs Redis unmodified as a separate
  container and never links it, so the AGPL's source-offer duty for modified network services does
  not arise; this is recorded as the OWN-03 written approval request for the Bits architecture
  owner (open, see Consequences). Ticket 90 pins the tag by digest, as every platform image is.
- **Fallback, if the approval is refused:** `redis:7.2.17-alpine`, the latest release of the last
  BSD-3 line (7.2), with the same configuration. Nothing in the repo may use a command or data type
  that 7.2 lacks (no Redis 8 query engine, JSON, time series or hash-field expiry), so the fallback
  stays a tag change.
- Names follow the product: the `MW_PORT_*`, memory and password knobs, the Spring properties and
  the docs say Redis; ticket 90 renames them in one change and keeps the old knob names readable for
  one release where an operator may have set them in `deploy/.env`.

## Consequences

- Matches FRS 1.4 and PLT-004 word for word; the APISIX `limit-count` policy keeps its `redis`
  policy type.
- Open: OWN-03 written approval of AGPLv3 for Redis 8, or the 7.2.17 fallback. Until it is given,
  either tag is a one-line change in `deploy/docker-compose.yml`.
- Redis 7.2 reaches end of support before Redis 8 does; the fallback is a stop-gap, not a target.
- Valkey stays in the repo until ticket 90 lands; docs that name Valkey describe the stack as it runs.

## Where in the repo

- `deploy/docker-compose.yml` (the `valkey` service until ticket 90)
- `deploy/apisix/` (the `limit-count` policy)
- spec decision table (D29), section 3
