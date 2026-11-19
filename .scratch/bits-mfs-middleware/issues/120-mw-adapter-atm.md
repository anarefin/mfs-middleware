# 120: mw-adapter-atm: UCB ATM cardless cash-out and cash-in (FRS UC-17, EXT-16, FL-11)

**What to build:** A new adapter service `mw-adapter-atm` that serves the UCB ATM gateway over inbound SOAP:
- token verify + cash-out;
- cash-in;
- reversal advice (dispense failure).

The customer generates a one-time 6-digit token in the app, valid for 10 min (FL-11). The ATM sends token + amount;
the middleware verifies it and debits the wallet.

**Principles:**
- The token is a secret: stored only as an HMAC (mw-crypto `HMAC` purpose), single use, bound to wallet + amount,
  never logged. Wrong-token attempts are counted and locked like OTPs.
- A cash-out debit is a hold that commits on the ATM's dispense confirmation. A reversal advice or a missing
  confirmation (timeout) releases or reverses it. The ATM's reversal advice is idempotent (PLT-025).

**Scope:**
- Token issue: `POST /app/v1/atm/tokens` (amount) in mw-app-gateway. It needs a step-up PIN assertion (mw-auth) and
  stores the token HMAC with a 10 min expiry. Where it lives (mw-auth `channel_otp` pattern vs. a new table in
  mw-app-gateway) is decided and recorded in CLAUDE.md, reusing the mw-auth channel OTP if that fits.
- `scripts/new-service.sh mw-adapter-atm`, then copy the `partner/` engine (package `mfs.mw.adapter.atm.partner`).
  Family `atm`. Inbound SOAP via CXF (Camel is allowed in adapters), with a WSDL published (PLT-050) and
  WS-Security or mTLS at the edge (ticket 100).
- Operations: `VerifyTokenAndDebit`, `ConfirmDispense`, `ReversalAdvice`, `CashIn` (deposit credits the wallet from the
  UCB settlement wallet).
- Orchestrator flows `ATM_CASH_OUT` (hold on verify → commit on confirm / release on reversal or timeout) and
  `ATM_CASH_IN` (credit).
- `services/mw-simulators`: `UcbAtmMock`, a mock ATM web page in the test console (enter token + amount,
  dispense / fail / timeout buttons) that sends SOAP requests. It runs on `/mock-control/ucb-atm`.
- Compose block with a memory limit; 12 GB budget.
- Docs: service `CLAUDE.md`, `contracts.yml`, README, `docs/testing-guide.html` section.

**FRS IDs covered:** UC-17, EXT-16, FL-11, PLT-025, PLT-050 (WSDL)

**Blocked by:** 95, 97, 102

**Status:** open

- [ ] Unit: token HMAC, expiry, single use, attempt lockout; the token never appears in logs
- [ ] Camel/CXF route tests: the SOAP envelopes for verify, confirm, reversal and cash-in, and fault mapping
- [ ] `AtmCashOutIT`: verify → confirm commits; a reversal advice releases; a reused or expired token is refused with no hold
- [ ] T1–T12 for ATM_CASH_OUT (T3/T4: confirm timeout then inquiry; T7: duplicate reversal advice processed once) and T1/T6/T9 for ATM_CASH_IN
- [ ] `./gradlew :mw-adapter-atm:build :mw-orchestrator:build :mw-app-gateway:build :mw-simulators:build :contractsLint :composeCheck :checkIsolation` green with Docker
- [ ] API suite folder `54-atm-cash` passes
- Split note: if it exceeds 1,500 lines, split into 120a (token + cash-out) and 120b (cash-in + reversal advice).
