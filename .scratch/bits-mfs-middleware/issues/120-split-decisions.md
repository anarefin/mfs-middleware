# 120 split (2026-11-20, pre-split like 115/118/119, auto-split rule)

- **120a-atm-adapter-inbound** (95,97,102): new `services/mw-adapter-atm` (framework copy as in 118a/119a, family `atm`),
  inbound SOAP (CXF) for VerifyTokenAndDebit, ConfirmDispense, ReversalAdvice, CashIn with a published WSDL and
  WS-Security, route tests with fault mapping; the inbound side hands the call to the orchestrator over the existing
  partner inbound/request contracts (or records the contract gap as an open item); `UcbAtmMock` in mw-simulators; topic,
  compose, provision, admin-api family (next free Flyway), engine-copies test, module CLAUDE.md.
- **120b-atm-token-flows** (120a): token issue `POST /app/v1/atm/tokens` (step-up PIN assertion, HMAC through mw-crypto,
  10 min, single use, bound to wallet + amount, lockout), orchestrator `ATM_CASH_OUT` / `ATM_CASH_IN`, test-console mock
  ATM page, `AtmCashOutIT`, Newman folder `54-atm-cash` (or next free), README / testing-guide.
