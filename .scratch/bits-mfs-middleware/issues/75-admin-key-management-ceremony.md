# 75: Admin console: keys, rotation and key ceremony

**What to build:** Key management screens and a dual-control ceremony (spec 18.8, D23, OWN-08).

- **mw-admin-api:**
  - `GET /admin/v1/keys` (from mw-crypto; role `security-admin` or `auditor`);
  - change request types `KEY_GENERATE`, `KEY_ROTATE`, `KEY_RETIRE` and `KEY_CEREMONY` in the maker-checker engine: maker and checker must be two different `security-admin` users, MFA fresh (≤ 5 min, `auth_time`);
  - on approval, it calls the mw-crypto key API.
- **Ceremony** (production top-level keys: `PIN_HMAC`, KEKs, payShield LMK import into the simulator):
  - two custodians each enter a component (hex, with check value) in their own session;
  - mw-crypto combines the components inside the HSM (PKCS#11 `C_DeriveKey` XOR or unwrap, or payShield `A0`/`FK` on the simulator) and returns the KCV only;
  - every step is audited (`custodian_share_log` holds who and when, never the component).
  - `mw.crypto.bootstrap-keys` stays false outside dev.
- **mw-admin-ui:** a Keys page (list, versions, state, rotate/retire requests) and a Ceremony wizard (custodian 1, custodian 2, KCV confirmation), in the approvals list.
- **Tests:**
  - admin-api ITs (same-user approval refused, stale MFA refused);
  - mw-crypto ceremony IT on SoftHSM (two components give the expected KCV);
  - vitest for the UI;
  - Playwright `@keys` spec.

**Blocked by:** 64, 70

**Status:** open

- [ ] `./gradlew :mw-admin-api:build :mw-crypto:build` green with Docker
- [ ] `pnpm test` in `ui/mw-admin-ui` and `scripts/ui-e2e.sh @keys` green
