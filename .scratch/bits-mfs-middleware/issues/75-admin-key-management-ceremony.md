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

**Status:** done

- [x] `./gradlew :mw-admin-api:build :mw-crypto:build` green with Docker (also `:mw-seeder:build`,
  `:checkIsolation`, `:checkFieldInjection`, `:contractsLint`, `:composeCheck`, `:scriptTests`)
- [x] `pnpm test` in `ui/mw-admin-ui` green
- [ ] `scripts/ui-e2e.sh @keys` green (deferred to final integration test; spec `e2e/60-keys.spec.ts` written)

**Decisions (recorded in `services/mw-crypto/CLAUDE.md` "Key ceremony" and `services/mw-admin-api/CLAUDE.md`):**

- mw-admin-api calls mw-crypto synchronously over mTLS (key-admin client), idempotent on the change
  request id: `PUT /internal/v1/key-changes/{id}` (generate / rotate / retire, no bootstrap flag) and
  `/internal/v1/ceremonies/{id}/components|commit|abort`; not an outbox command, because the ceremony is
  interactive and returns the KCV. Approval is claim-first (PENDING -> APPROVING, then the call, then APPROVED;
  a refusal goes back to PENDING; reject / expiry refuse APPROVING; `KeyApprovalRaceIT`).
  `mw.crypto.bootstrap-keys` stays false outside dev.
- HSM-side combine: SoftHSM 2.6.1 has no `CKM_XOR_BASE_AND_DATA` and the p11-kit RPC refuses
  `CKM_AES_KEY_WRAP`, so the two components (P-256 scalars, check value = SHA-256 of the compressed
  `d·G`, 3 bytes) are combined by `CKM_ECDH1_DERIVE` into an HMAC-SHA256 key (`x(d1·d2·G)`), KCV =
  HMAC over 16 zero bytes, 3 bytes. A ceremony makes a new key; it cannot import an existing key value.
- Ceremony purposes: HMAC keys (`PIN_HMAC`, `TOKENISE_*`, `HMAC_*`). **Follow-ups not built:** KEK (AES)
  ceremonies (needs a direct `C_DeriveKey` template) and the payShield LMK import into the simulator.
- Fresh MFA = `auth_time` at most 5 min old (`mw.admin-api.key-mfa-max-age`), `403 MW-AUTH-031`, on file,
  component entry, approve and reject. Personas `seed-ops-secadmin1`, `seed-ops-secadmin2` (security-admin,
  realm TOTP secret; `scripts/totp.sh`).
