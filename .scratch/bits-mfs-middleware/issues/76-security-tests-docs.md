# 76: Security group: API suite, test console, docs

**What to build:** The black-box coverage and operator docs for tickets 61–75.

- **Newman** (`tests/api/src`):
  - new folders for auth: OTP, device bind, login, refresh rotation and reuse, logout;
  - PIN: policy, change, self-service reset, care reset with maker-checker, lockout escalation with the short dev timers (add `MW_AUTH_LOCK_*` knobs to `deploy/dev.env.defaults`), wallet lock and unlock;
  - assertion: wrong PIN before the saga, reuse, expiry, USSD and checkout;
  - each request is tagged with requirement ids (SEC-04, SEC-09, FR-05, …) in `tests/api/TRACEABILITY.md`;
  - regenerate with `node tests/api/build_collection.mjs`.
- **Test console** (`ui/mw-test-console`): app login with PIN, bind a second device (cooling flag visible), PIN reset flows, USSD wrong-PIN re-prompt, checkout OTP + PIN; Playwright tags `@auth`.
- **Docs:**
  - `README.md` (new services, softhsm, ports `MW_PORT_AUTH`/`MW_PORT_CRYPTO`, memory knobs);
  - `docs/runbook.md` (HSM down, cert expiry, key rotation, unlock);
  - `docs/testing-guide.html` (app PINs instead of the customers password, TOTP for portals);
  - `docs/tutorial/` (auth and crypto flow pages), `docs/demo.md` (a security demo section);
  - root `CLAUDE.md` service list.
- **Security pass:** `scripts/security.sh` with the stack up (`SECURITY_LIVE=1`): gitleaks, trivy on the new images, ZAP baseline on `/auth/v1`. Findings are recorded in `docs/security-findings.md`.

**Blocked by:** 72, 73, 75

**Status:** done

- [ ] Milestone gate: `./gradlew clean build -PwithSlow`; `deploy/reset.sh`, then up with core and seed profiles; `scripts/smoke.sh`, `scripts/edge-test.sh`, `scripts/api-test.sh` (all folders), `scripts/ui-e2e.sh`, `scripts/tester-e2e.sh`, `scripts/log-scan.sh`, `scripts/security.sh` all green (no load or chaos tests) (deferred to final integration test)
- [x] `./gradlew :scriptTests :contractsLint` green

**Notes (implementation, 2026-11-15):** `MW_AUTH_LOCK_*` already in `deploy/dev.env.defaults` (ticket 70).
Newman folders 24-27 + two folder 90 scenarios; `TRACEABILITY.md` gained a Requirements column
(checked). Edge gaps found while writing the requests and fixed: `auth-v1` takes PUT (PIN change,
reset PIN), assertion JWKS added to `auth-v1-keys`, new route `auth-v1-pin-resets` (GET reset status).
Review queue: spec 18.8 key hierarchy amended; dev-only mw-crypto payShield test API (folder 27) with
the test client `mw-e2e-crypto` bound to TPK/PVK in `dev.env.defaults` only; mw-admin-api test menu
copy 5-digit PIN. The `newDevice` flag is only in the assertion to the core, so the test console
shows the window derived from `boundAt` (+24 h). Security pass: placeholder in
`docs/security-findings.md` for the gate.
