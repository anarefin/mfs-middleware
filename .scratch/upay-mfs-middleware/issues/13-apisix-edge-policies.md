# 13: APISIX edge policies

**What to build:** The APISIX edge enforces technical policy before traffic reaches services (§8.3): `limit-count` per consumer (counters in Valkey), `request-validation` with JSON Schema derived from the app OpenAPI, `request-id` as `X-Correlation-Id` (generated if absent and forwarded upstream), `proxy-rewrite` stripping inbound `X-Mw-*` headers, and body logging off. Configuration is declarative YAML applied by ADC. Spec WP09.

**Blocked by:**

- 09: App gateway P2P + minimal APISIX route

**Status:** ready-for-agent

- [ ] Gateway test: no or expired JWT → 401
- [ ] Gateway test: exceeding the per-consumer quota → 429
- [ ] Gateway test: a body that breaks the schema → 400 and never reaches the upstream
- [ ] Gateway test: a client-sent `X-Mw-*` header is stripped, and `X-Correlation-Id` is generated when absent and appears in upstream logs
- [ ] Applying the config twice is a no-op (idempotent ADC sync)
