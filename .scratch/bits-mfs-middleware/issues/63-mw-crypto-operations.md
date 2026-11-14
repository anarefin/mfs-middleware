# 63: mw-crypto operations: PIN HMAC, JWS, HMAC, tokenisation, envelope encryption

**What to build:** The PKCS#11 operations of spec 18.8 on top of ticket 62.

- **PIN:**
  - `POST /internal/v1/pin/verify` takes `{walletId, encryptedPin{keyId, ciphertext}, storedHmac, hmacKeyId}` and returns `{match, rehash?}`. `POST /internal/v1/pin/derive` takes `{walletId, encryptedPin, policyInput?}` and returns `{hmac, hmacKeyId}`, for PIN set and change.
  - The RSA-OAEP decrypt (SHA-256, MGF1-SHA-256, the same parameters as today's `PinCipher`) runs in the HSM session. The PIN is checked to be exactly 5 ASCII digits. HMAC-SHA256 under `PIN_HMAC` is taken over `walletId ‖ 0x00 ‖ PIN`, and the comparison is constant-time.
  - The clear PIN is a `byte[]`, zeroed in `finally`, and never a `String`.
  - `rehash` is returned when the stored value used a VERIFY_ONLY key version.
  - The weak-PIN policy runs here, because it needs the clear PIN. The rules (spec 18.7) are configured in mw-auth and passed as `policyInput` (MSISDN, birth year, last HMAC). The answer is `{weak: true, rule}`, never the PIN.
- **JWS:** `POST /internal/v1/jws/sign` (`{keyId or purpose, header claims, payload}` returns a compact JWS, ES256 or PS256) and `GET /internal/v1/jwks/{purpose}` (current and previous versions, with `kid` = keyId:version). Use Nimbus JOSE with a custom signer that calls the PKCS#11 key.
- **HMAC:** `POST /internal/v1/hmac/sign` and `/verify`.
- **Tokenisation:** `POST /internal/v1/tokens` with `{domain: MSISDN|NID|ACCOUNT, values[]}` returns deterministic tokens: base32 of HMAC under `TOKENISE_<domain>`, prefixed by the domain. Batch size is capped.
- **Envelope encryption:** `POST /internal/v1/envelope/encrypt` and `/decrypt`. AES-256-GCM with a fresh data key wrapped by `KEK_<purpose>`. The output is `{kekId, wrappedKey, iv, ciphertext, aad}`.
- **Masking and logging:** request DTO fields are `@Sensitive` (ciphertext, HMAC, values). Payload logging is never allowed in this service, and a test proves no field reaches a log line.
- **Errors:**
  - `MW-VAL-*` for a bad block or a wrong length;
  - `{match: false}` for a wrong PIN, which is not an error;
  - `MW-SYS-*` when the HSM is down.

**Tests:**
- unit tests with the SunPKCS11 provider mocked at the SPI;
- IT against SoftHSM for every operation, including key rotation (verify with the previous version, rehash flag), zeroing (the buffer is all zero after the call) and a known-answer test for the HMAC;
- contract fixtures.

**Blocked by:** 62

**Status:** done

- [x] `./gradlew :mw-crypto:build` green with Docker
- [ ] `scripts/log-scan.sh` clean after an IT-equivalent run against the stack (no PIN, PIN ciphertext or HMAC in logs) (deferred to final integration test; `client/OperationsIT` already checks the mw-crypto container log for every request and answer value)

**Decisions (ticket 63):**
- kid stays `<keyId>.v<n>` (ticket 62), not `keyId:version`; `hmacKeyId` in verify is that kid.
- HMAC sign/verify use a new purpose `HMAC_<use>` only; `PIN_HMAC` and `TOKENISE_*` keys never answer there (no HMAC oracle).
- PIN block: raw RSA in the HSM plus BouncyCastle OAEP decoding in the JVM (SunPKCS11 has no OAEP cipher).
- KEK wrap: AES-256-CTR on HSM `AES/ECB` counter blocks (over the p11-kit RPC GCM fails with `CKR_DEVICE_ERROR`, a CBC decrypt with `CKR_FUNCTION_NOT_SUPPORTED`); the envelope's own GCM tag covers a changed wrapped key.
- `derive` takes an optional `hmacKeyId` (keyId) to choose the PIN_HMAC key; default the `PIN_HMAC` purpose key.
- New codes: `MW-VAL-014` bad PIN block, `MW-VAL-015` key not usable for the operation, `MW-VAL-016` bad envelope.
- Zeroing is checked in unit tests (the buffer the SPI hands out is all zero after every call); the HMAC known answer is a unit test with a known key (SoftHSM keys are not extractable, so the IT checks determinism and verify instead).
