# 38: Partner config via maker-checker

**What to build:** Operators can disable a partner or change its timeouts without a restart. The admin API manages partner config (`partner` schema: partner, endpoint, mapping, error_map) through maker-checker. On approval it publishes a full snapshot on `mw.config.changed.v1` (compacted), and the adapters reload it. A disabled partner rejects new requests fast with a mapped error. Spec WP23 (part).

**Blocked by:**

- 37: Maker-checker engine + manual TSQ / reversal
- 20: Adapter framework inside telco (prefactor)

**Status:** done

- [x] e2e: disable the telco partner → top-up rejected; re-enable → works, with no adapter restart (written: `tests/e2e/.../features/partner-config.feature`, `@partner-config`; runs at the milestone gate) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
- [x] e2e: a lowered timeout takes effect on the next call (written: same feature, `@tsq`; runs at the milestone gate) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
- [x] An adapter that starts after the change picks up the latest snapshot from the compacted topic (`PartnerConfigStartupIT` in mw-adapter-telco)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate; `./gradlew test integrationTest` green for mw-admin-api and mw-adapter-telco) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
