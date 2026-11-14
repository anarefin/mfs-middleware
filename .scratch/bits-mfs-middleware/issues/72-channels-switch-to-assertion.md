# 72: Channels switch to the assertion; core PIN scheme removed

**What to build:** Every channel obtains an assertion before submitting (spec 9.1–9.3, D22, S11, S15), then everything left of M11 is removed.

- **mw-app-gateway:** for every financial `txnType` (the role map of spec 9.1), `POST /app/v1/transactions` takes `encryptedPin` (now under the mw-crypto `PIN_TRANSPORT` key), calls mw-auth `/internal/v1/txn-authorisations` over mTLS, and publishes the request with `assertion` only.
  - A wrong PIN or a lock returns the mw-auth error at once (HTTP 401/423 with the mw-auth code).
  - mw-auth unreachable: 503 `MW-SYS-*`, retryable.
  - `GET /app/v1/pin-key` returns the transport public key (proxied from mw-crypto, cached) for the apps.
- **mw-ussd:**
  - `util/PinCipher` encrypts with the mw-crypto transport key (fetched by keyId at start, refreshed on an unknown keyId).
  - `service/UssdEngine` calls mw-auth before `KafkaTransactionFlow` publishes; a wrong PIN re-prompts within the session (max tries = the mw-auth lock rule), and a lock ends the session with a localized message (bn/en templates).
- **mw-checkout:**
  - OTP through mw-auth (purpose `CHECKOUT`, bound to the checkout sessionId instead of a device);
  - the page encrypts the PIN with the transport key (WebCrypto RSA-OAEP SHA-256);
  - `CheckoutPayService` gets the assertion and submits.
  - Update the hosted page and the test console checkout flow.
- **Remove:**
  - `encryptedPin` from `mw.txn.request.v1` and the core commands (mw-orchestrator contracts);
  - the `txn.transaction.encrypted_pin` column (Flyway migration) and its wipe code and tests;
  - mw-core-mock's `security/PinCipher`, `security/PinKeyProvider`, `controller/PinKeyController`, `wallet.pin_hash`, and its PIN lockout;
  - the core PIN key pair in `deploy/provision/services.sh` (`pin_keypair`) and its copies to mw-ussd and mw-checkout.
- `scripts/log-scan.sh`: add patterns for the transport ciphertext and the assertion (an assertion in a log is a finding; only its `jti` may appear).
- `./gradlew :composeCheck`: with mw-auth, mw-crypto and softhsm added, the core profile must stay ≤ 8 GB. Trim the limits (`deploy/docker-compose.yml` knobs) and record the new numbers in `README.md`.

**Blocked by:** 71, 70

**Status:** done

- [x] `./gradlew :mw-app-gateway:build :mw-ussd:build :mw-checkout:build :mw-orchestrator:build :mw-core-mock:build` green with Docker (plus `:mw-auth:build`)
- [x] `./gradlew :contractsLint :composeCheck :scriptTests` green
- [ ] Fresh stack (`deploy/reset.sh`, up with seed): `scripts/api-test.sh` all folders, `scripts/tester-e2e.sh`, `scripts/log-scan.sh` green (deferred to final integration test)

Decisions (ticket 72):
- Ops unlock: core `UnlockWallet`, status LOCKED and `WalletRepository.unlock` stay, for core-side account locks only (the core has no PIN lockout any more; mw-auth's is the only one). Recorded in mw-core-mock, mw-admin-api and mw-auth CLAUDE.md.
- Checkout OTP: mw-auth `POST /internal/v1/otp` + `/internal/v1/otp/verifications` (purpose CHECKOUT, bound to the sessionId, client mw-checkout only). Security review: its own slot (`channel_otp`, per wallet, purpose and session) and its own per-wallet send quota (`mw.auth.channel-otp.max-sends`, `MW_AUTH_CHANNEL_OTP_MAX_SENDS`), so it never replaces or uses up a device OTP. The OTP is spent before the PIN check (a wrong PIN needs a new OTP).
- Transport key: the channels read it from mw-auth `GET /internal/v1/pin-key` over mTLS with their services token and refuse a key whose `purpose` is not `PIN_TRANSPORT` (security review); the public `GET /auth/v1/pin-key` stays for the apps only. mw-app-gateway serves it as `GET /app/v1/pin-key` (cached 5 min, last key kept while mw-auth is down).
- USSD PIN is 5 digits (mw-crypto's rule); the wrong-PIN re-prompt and the lock texts are menu messages with built-in fallbacks.
- `$defs.EncryptedPin` stays in common/defs (re-described: channel to mw-auth only); migrations: orchestrator V11, core-mock V9, mw-auth V8, mw-checkout V7.
- Compose budget unchanged at 8176/8192 MiB: the ticket adds no container or limit; nothing measured, nothing trimmed (README).

