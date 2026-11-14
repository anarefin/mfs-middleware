# 70: PIN policy, change, reset, lockout and wallet-lock revocation

**What to build:** The PIN lifecycle of spec 18.7 (D20, S4, S10) and the `auth.wallet-lock.v1` command topic.

- **Policy:** 5 digits. Weak-PIN rules are evaluated in `mw-crypto` (ticket 63 `policyInput`): all one digit, ascending or descending runs, 5 consecutive digits of the MSISDN, the birth year when known, and the last N PINs (N=3, by HMAC). Refused with `MW-AUTH-044`. Config `mw.auth.pin.*`.
- **Lockout:** atomic counters in `auth.attempt`. After 3 failures, lock level 1 (1 h); after 3 more, level 2 (24 h); after 3 more, level 3 (permanent). A successful verify resets the count, not the level history (config: decay after 30 days). Codes `MW-AUTH-040` (wrong, tries left in `details`), `041` (locked, `retryAfter`), `042` (permanent). Audit event on every lock.
- **Change:** `PUT /auth/v1/pin` (logged in, step-up: old PIN within the request, `amr` refreshed).
- **Self-service reset:**
  1. `POST /auth/v1/pin-resets` (OTP purpose `RESET` on the bound device + NID last 4 + date of birth) → `PENDING_KYC`.
  2. mw-auth sends `VerifyCustomerFactors` on `core.wallet.command.v1` (new payload in `contracts/events/mw-orchestrator/core.command.v1/`; mw-orchestrator owns the file, so this ticket changes it as its owner's proposal, reviewed by a human). The factors go hashed (HMAC by mw-crypto under `TOKENISE_NID`), never in clear.
  3. mw-core-mock answers `CustomerFactorsVerified` / `CustomerFactorsRejected` on `core.wallet.event.v1`.
  4. `GET /auth/v1/pin-resets/{id}` → `VERIFIED` → `PUT /auth/v1/pin-resets/{id}/pin` sets the new PIN and clears the lock.
- **Care reset:**
  - `POST /internal/v1/pin-resets` (caller mw-admin-api).
  - In mw-admin-api: a maker-checker change request type `PIN_RESET` (an existing engine from ticket 37) with role `ops-maker`/`ops-checker`. On approval, mw-auth marks the reset `APPROVED`; the customer gets an SMS to request an OTP and set a PIN (purpose `RESET`, no KYC step).
  - The admin UI shows the request in approvals (one screen in `ui/mw-admin-ui`).
- **Wallet lock topic:** `contracts/events/mw-auth/auth.wallet-lock.v1.schema.json` (owned by mw-auth, the consumer), actions `LOCK`, `UNLOCK`, `REVOKE_SESSIONS`, with reason and actor.
  - mw-auth consumes it with inbox dedup and cuts sessions and refresh families.
  - mw-admin-api produces it: the ops unlock of ticket 52 now unlocks the PIN lock here instead of the core `UnlockWallet`. Keep `UnlockWallet` for core-side account locks if the core still has them, and note it in both CLAUDE.md files.
- **Tests:**
  - policy unit tests (every rule);
  - lockout IT (escalation, retry-after, permanent);
  - a reset IT with the core-mock event;
  - a maker-checker IT in mw-admin-api;
  - a topic consumer IT;
  - contract fixtures for every new message.

**Blocked by:** 69

**Status:** open

- [ ] `./gradlew :mw-auth:build :mw-admin-api:build :mw-core-mock:build :mw-orchestrator:build` green with Docker (orchestrator for the contract change)
- [ ] `./gradlew :contractsLint` green
- [ ] `pnpm test` in `ui/mw-admin-ui` green; `scripts/ui-e2e.sh @approvals` green
