# 120 split (2026-11-20, pre-split like 115/118/119, auto-split rule)

- **120a-atm-adapter-inbound** (95,97,102): new `services/mw-adapter-atm` (framework copy as in 118a/119a, family `atm`),
  inbound SOAP (CXF) for VerifyTokenAndDebit, ConfirmDispense, ReversalAdvice, CashIn with a published WSDL and
  WS-Security, route tests with fault mapping; the inbound side hands the call to the orchestrator over the existing
  partner inbound/request contracts (or records the contract gap as an open item); `UcbAtmMock` in mw-simulators; topic,
  compose, provision, admin-api family (next free Flyway), engine-copies test, module CLAUDE.md.
- **120b-atm-token-flows** (120a): token issue `POST /app/v1/atm/tokens` (step-up PIN assertion, HMAC through mw-crypto,
  10 min, single use, bound to wallet + amount, lockout), orchestrator `ATM_CASH_OUT` / `ATM_CASH_IN`, test-console mock
  ATM page, `AtmCashOutIT`, Newman folder `54-atm-cash` (or next free), README / testing-guide.

# 120b split (2026-11-20, auto-split rule: 1 h cap; security fixes first)

120b touches mw-app-gateway, mw-auth/mw-crypto (token HMAC), the orchestrator contract
`mw.partner.inbound.v1` (new family `atm` branch with a debit party), two orchestrator sagas, the
test console and Newman 54: far over one sitting. Split:

- **120b1-atm-inbound-hardening** (120a; done in this run): the M9 review's ATM findings.
  - High: VerifyTokenAndDebit and CashIn fail closed (`MW-SYS-ATM_CASH_DISABLED`, flag
    `mw.atm.inbound.cash-enabled`, off). The XSD Status gains DECLINED and PENDING.
  - Medium: a per-cash-out state machine (VERIFIED -> CONFIRMED | REVERSED) with the atmId and a
    keyed fingerprint (V9). Conflicts are `MW-IDEM-001` / `MW-IDEM-ATM_STATE`.
  - Medium: APISIX route `partner-ucbatm-ws`; PasswordDigest replaces PasswordText.
  - Medium: a once-only nonce and Created within ±300 s (PLT-008).
- **120b2-atm-token-issue** (120b1): `POST /app/v1/atm/tokens` in mw-app-gateway (step-up PIN
  assertion from mw-auth, HMAC through mw-crypto, 10 min, single use, bound to wallet + amount,
  lockout like OTPs). The CLAUDE.md decision on where the token lives (mw-auth `channel_otp` or a
  new table). The adapter verifies the token (or hands it on) and answers DECLINED on a bad token.
- **120b3-atm-cash-flows** (120b2): extend `mw.partner.inbound.v1` with a family `atm` branch,
  orchestrator `ATM_CASH_OUT` (hold on verify, commit on confirm, release on reversal or timeout
  with a STATUS_QUERY inquiry) and `ATM_CASH_IN` (credit from the UCB settlement wallet), next
  orchestrator Flyway V. Turn on `cash-enabled`. Add the PENDING answer, `AtmCashOutIT`, T1-T12,
  the test-console mock ATM page (UcbAtmMock sends PasswordDigest SOAP), Newman folder
  `54-atm-cash`, and the README / testing-guide.
