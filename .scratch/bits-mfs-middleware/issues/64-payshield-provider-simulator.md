# 64: payShield 10K simulator and mw-crypto payShield provider

**What to build:** Thales payShield 10K host-command support (spec 18.8, D23, open point 22): a simulator in `mw-simulators` and a `PayShieldProvider` in `mw-crypto`.

- **Simulator** (`mw-simulators`, `client/` package next to the simulated SMPP servers):
  - TCP server, a 2-byte big-endian length prefix, the configurable header (default 4 chars), commands and responses in payShield ASCII format.
  - Commands:
    - `NC` diagnostics;
    - `A0` generate key (ZPK, PVK, TPK under LMK, plus under ZMK when asked);
    - `CA` translate PIN TPK→ZPK;
    - `CC` translate ZPK→ZPK;
    - `DG` generate Visa PVV;
    - `DC` verify PIN with PVV (TPK);
    - `EC` verify PIN with PVV (ZPK);
    - `M0`/`M2` encrypt/decrypt data.
  - Error codes `00`, `01` (verification failure), `10`/`11` (key parity), `15` (invalid input), `68` (command disabled).
  - The LMK and keys are kept in the SoftHSM `paysim` token (ticket 61). PIN block formats ISO 9564 0, 1, 3 and 4 (format 4 is AES).
  - Scenario control as the other simulators (`POST /sim/payshield/scenario`: latency, error code, outage).
- **Provider:** `PayShieldProvider` in mw-crypto. A connection pool, request/response correlation by header, timeouts, and a Resilience4j circuit breaker. Key metadata stores the LMK-encrypted key blob reference, not the key.
- **API:** `POST /internal/v1/pinblock/translate` and `POST /internal/v1/pvv/generate`, `/verify`, for the later ATM and interop adapters.
- **Tests:**
  - simulator unit tests against published payShield test vectors (where public) and self-consistent round trips;
  - provider IT against the simulator in a Testcontainer;
  - contract fixtures.

**Blocked by:** 63

**Status:** open

- [ ] `./gradlew :mw-simulators:build :mw-crypto:build` green with Docker
- [ ] On the running stack, a PVV generated through `mw-crypto` verifies, and a wrong PIN returns error `01` mapped to `{match: false}`
