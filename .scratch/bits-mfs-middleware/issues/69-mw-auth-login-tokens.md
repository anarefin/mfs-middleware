# 69: mw-auth login and tokens (MSISDN + PIN + device), JWKS at the edge

**What to build:** Wallet login and token issuance by `mw-auth` (spec 18.7, D21).

- `POST /auth/v1/sessions`: MSISDN + `encryptedPin` + device signature headers.
  - Check order: wallet status (projection) → lock → device bound to this wallet → PIN via `mw-crypto` `/pin/verify` (count a failure; the lock escalation itself comes in ticket 70, here a simple counter is fine).
  - Issue the access JWT: 5 min, ES256 through `mw-crypto` `/jws/sign` with purpose `JWT_SIGN`; claims `iss`, `sub` walletId, `msisdn`, `role` (from the wallet type: customer, agent, merchant, distributor, dso), `deviceId`, `amr` [pin, device], `sid`.
  - Issue the refresh token: opaque, 256 bit, stored as a SHA-256 hash, 30 days, device-bound.
- `POST /auth/v1/tokens/refresh`: rotation, and reuse detection (revoke the family, `MW-AUTH-048`).
- `POST /auth/v1/sessions/logout`.
- `GET /auth/v1/.well-known/jwks.json` (proxied from `mw-crypto` `/jwks/JWT_SIGN`, cached).
- **First PIN:** when the wallet has `hasPin=false`, OTP purpose `LOGIN` + `POST /auth/v1/pin` (set) are needed before the token gets `amr` pin. The policy check comes in ticket 70; here, any 5 digits.
- **Edge:**
  - The APISIX `/auth/v1/*` routes: no auth plugin, with `limit-count` per MSISDN and per address.
  - `app-v1` switches from `openid-connect` (Keycloak `customers`) to `jwt` validation against the mw-auth JWKS (the `openid-connect` plugin with a discovery document served by mw-auth, or APISIX `jwt-auth` with a JWKS: pick the one APISIX supports cleanly and note it in `deploy/apisix/README.md`).
  - `lua/consumer-from-jwt.lua` keeps its rate limit on `sub`.
- **mw-app-gateway:** `config/SecurityConfig` and `security/RealmIssuerValidator` accept the `mw-auth` issuer (JWKS URI) instead of the `customers` realm; `security/AppUserResolver` maps the `role` claim.
- **Seeder:** the app personas log in through mw-auth (needs PINs: the seeder sets them with the dev-only internal `POST /internal/v1/seed/pins` behind a profile, owned by mw-auth). Update `scripts/creds.sh` (the app PINs replace the customers password).
- **Tests:** unit tests for the token service; ITs for login (each refusal path), refresh rotation and reuse, the JWKS; app-gateway slice tests with the new issuer; an edge test in `scripts/edge-test.sh` (401 without a token, 200 with an mw-auth token).

**Blocked by:** 68

**Status:** open

- [ ] `./gradlew :mw-auth:build :mw-app-gateway:build :mw-seeder:build` green with Docker
- [ ] `scripts/edge-test.sh` and `scripts/api-test.sh` app folders green on a fresh seeded stack
