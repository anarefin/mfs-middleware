# 68: Move device binding, OTP, sessions and nonces from mw-app-gateway to mw-auth

**What to build:** Move the device and session code built in tickets 18 and 29 into `mw-auth` (spec 9.1, 18.7, S8), keeping its behaviour, error codes (`MW-AUTH-003..007`, `010..012`) and tests. Then slim `mw-app-gateway` down to a verifier.

- **Move** (with their tests, renamed to `mfs.mw.auth.*`):
  - `service/DeviceBindingService`, `service/DeviceOtpService`, `service/RequestSignatures`, `repository/DeviceSessionStore`, `util/OtpCodes`, `controller/DeviceController`, and their DTOs and exceptions;
  - the OTP Kafka publish to `mw.notify.request.v1` (mw-auth becomes a producer in `contracts.yml`; mw-notify's contract already allows any producer);
  - the device binding data moves from Valkey/PG in mw-app-gateway to `auth.device`. Dev data is reseeded; there is no migration.
- **New public API:** `contracts/api/mw-auth/auth.v1.openapi.yaml` (owned by mw-auth) with `/auth/v1/otp`, `/auth/v1/devices`, `/auth/v1/sessions`. They behave as in ticket 29 for now, still accepting the Keycloak token until ticket 69.
- **mw-app-gateway keeps:**
  - `security/DeviceBindingInterceptor` as a thin verifier: the ECDSA signature check with the device public key fetched from `mw-auth` `GET /internal/v1/devices/{deviceId}` and cached in Valkey (TTL 5 min; dropped on a `device.unbound` notice, or simply by a short TTL);
  - the session check via `mw-auth` `GET /internal/v1/sessions/{walletId}/{deviceId}` (cached briefly) or a session claim in the token after ticket 69. Choose one and note it in both services' `CLAUDE.md`.
  - Remove its device controller and OTP code; `/app/v1/devices*` routes move to `/auth/v1/*` in APISIX (`deploy/apisix/routes`).
- **Update** the app-gateway OpenAPI (owned by mw-app-gateway), the test console app flow, the Newman folders that bind devices, and `docs/testing-guide.html`.

**Blocked by:** 67

**Status:** done

- [x] `./gradlew :mw-auth:build :mw-app-gateway:build` green with Docker
- [ ] `scripts/api-test.sh` folders covering device binding and P2P green on a fresh seeded stack (deferred to final integration test)
- [ ] `scripts/tester-e2e.sh @app` green (deferred to final integration test)
