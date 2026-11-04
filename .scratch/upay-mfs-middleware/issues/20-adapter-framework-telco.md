# 20: Adapter framework inside telco (prefactor)

**What to build:** Partners of a known kind are added by configuration, not code (§12). The adapter framework is built inside `mw-adapter-telco` as its own package; it is not a library. It contains the `PartnerAdapter` and `AuthScheme` SPI, the route templates `rest-json-request-reply` and `rest-json-async-callback` instantiated by `TemplatedRouteBuilder`, JSONata request/response mappings, error-code maps as config, and auth schemes `api-key` and `hmac-sha256`. Partner config arrives as `mw.config.changed.v1` snapshots, held in memory and swapped atomically. The thin hard-coded OCS route from ticket 16 is replaced by a templated instance. Behaviour is unchanged. Spec WP11.

**Blocked by:**

- 18: Unknown outcomes: UNKNOWN, TSQ, MANUAL_REVIEW

**Status:** done

- [x] A test instantiates a route template purely from a config record and calls WireMock
- [x] A test changes a mapping or timeout via a config-changed event, and the next call uses it without restart
- [x] Both auth schemes have apply/verify tests
- [x] e2e `@topup` (incl. `@tsq`) stays green unchanged
- [x] Masked raw request/response audit and the §21.2 partner metrics are emitted by the framework wrapper
  (audit on `mw.audit.event.v1` via the outbox: code-review fix S6, commit aece63d)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
