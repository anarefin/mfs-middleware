# 71: Transaction authorisation assertion; core-mock checks it; PIN leaves the orchestrator

**What to build:** The assertion of spec 18.7 and D22, end to end through the core mock. The channels switch in ticket 72; until then they keep sending `encryptedPin`, so this ticket keeps both fields for one ticket's time (assertion preferred, `encryptedPin` accepted) and ticket 72 removes the old one.

- **mw-auth:** `POST /internal/v1/txn-authorisations` (mTLS + JWT; callers `mw-app-gateway`, `mw-ussd`, `mw-checkout`).
  - Input: `{walletId | msisdn, channel, encryptedPin, txn: {txnType, amount, currency, payer, payee, idempotencyKey}}`. mw-auth computes `txnHash` = SHA-256 over the canonical JSON (RFC 8785 JCS) of `txn` + channel.
  - Checks: wallet status, lock, PIN via mw-crypto, counting failures (ticket 70 rules).
  - Output: a compact JWS via mw-crypto purpose `ASSERTION_SIGN`, with claims `iss` mw-auth, `aud` core, `sub`, `jti` (UUIDv7), `iat`, `exp` = iat + 60 s, `txnHash`, `channel`, `authMethod` PIN, `deviceId?`, `newDevice` (bound < 24 h, config).
  - A repeat call with the same idempotency key inside the life returns the same assertion without a second PIN check (Valkey `assert:{walletId}:{idempotencyKey}`, TTL = life).
- **Contracts** (mw-orchestrator owns them):
  - `common/defs.v1.schema.json` gets `TxnAssertion` (a string, a JWS compact pattern, max length);
  - `mw.txn.request.v1` gets `assertion`;
  - `ValidateTransaction`, `HoldFunds` and `PostTransfer` get `assertion`, while `encryptedPin` is kept as deprecated, "one of" until ticket 72;
  - bulk children need neither.
- **mw-orchestrator:** copy the assertion from the request into the core commands. A request with an assertion stores nothing PIN-related. Drop `txn.transaction.encrypted_pin` in ticket 72, when no channel sends it any more.
- **mw-core-mock:**
  - Verify the assertion: JWKS of mw-auth (fetched over HTTP at start and refreshed; a config call, not money-path), `aud`, `exp` with 30 s skew, recomputed `txnHash` from the command, and `jti` unused (table `coremock.used_assertion` with a TTL purge).
  - A refused assertion gives `TransactionRejected` with reason `MW-AUTH-046`.
  - Remove the bcrypt PIN check from `service/TransferRules.java` and the lockout for assertion-carrying commands. `security/PinCipher`, `PinKeyProvider` and `controller/PinKeyController` stay until ticket 72.
- **Seeder:** no change beyond ticket 69's PIN seeding.
- **Tests:**
  - assertion service unit tests: the hash is canonical, so the same txn in a different field order gives the same hash;
  - ITs for wrong PIN, lock, reuse inside the life, and expiry;
  - core-mock verify tests (tampered amount, wrong aud, replayed jti, expired);
  - orchestrator tests that the assertion reaches every core command;
  - contract fixtures.

**Blocked by:** 69

**Status:** done

- [x] `./gradlew :mw-auth:build :mw-orchestrator:build :mw-core-mock:build` green with Docker
- [x] `./gradlew :contractsLint` green
- [ ] On the stack, a P2P submitted with an assertion completes; the same assertion on a second txn is rejected `MW-AUTH-046` (deferred to final integration test)
