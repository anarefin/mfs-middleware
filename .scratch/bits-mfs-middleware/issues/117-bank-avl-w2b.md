# 117: mw-adapter-bank: AVL, linked accounts, W2B, about 20 bank profiles (FRS UC-14, UC-15, EXT-12, FL-08, FL-09)

**What to build:** Extend the bank adapter from one C2W partner (`c2wbank`) to a bank gateway of about 20 bank
profiles over REST or SOAP, by configuration. It supports:
- AVL (account validation, returning the holder name);
- linked bank accounts (FL-08);
- C2W add money, kept;
- a new W2B (transfer to bank) flow, with the wallet hold kept until the bank confirms the credit (FL-09);
- STATUS and REVERSE.

**Principles:**
- AVL runs before W2B and before linking. The holder name is shown masked (first initial, per `MaskingPolicy`) for
  confirmation.
- Account numbers are masked to their last 4 digits everywhere (logs, audit, admin views).

**Scope:**
- Route records per bank in `deploy/partner-routes/` and the admin-api dev seeds: about 20 entries (`bank-<code>`)
  generated from one versioned `bank-profiles.csv` (code, name, protocol REST/SOAP, auth scheme). Ops are INQUIRY
  (AVL), PAY (C2W debit request / W2B credit), STATUS_QUERY, REVERSE and the inbound CALLBACK. Mix REST and SOAP
  (`mw-soap-call`) profiles.
- Linked accounts: owned by `mw-app-gateway` (or mw-auth if wallet-profile data lives there; choose and record it in
  the CLAUDE.md). Table `linked_bank_account` (walletId, bank code, account number encrypted through the mw-crypto
  envelope plus a last-4 projection, holder name masked, verified at).
  - Endpoints: `POST /app/v1/bank-accounts` (runs AVL), `GET`, `DELETE`.
- Orchestrator: a `W2bFlow` (`txnType=W2B`): validate, hold, partner PAY (credit), then commit on bank confirmation,
  or release on a definite failure, or UNKNOWN → STATUS. `ADD_MONEY_BANK` accepts a linked account id.
- App API `/preview` and `/confirm` for transfer-to-bank (105). The USSD Transfer to Bank menu (109) is wired.
- `services/mw-simulators`: a `BankGatewayMock` with bank profiles and seeded accounts from
  `seeds/bank-accounts.csv`. Account ending 99 means insufficient funds, and the other FRS MCK-03 triggers apply.
  It is stateful (credits, debits, reversals) and runs on `/mock-control/bank-<code>`.
- Docs: `services/mw-adapter-bank/CLAUDE.md`, simulator trigger table, README.

**FRS IDs covered:** UC-14, UC-15, EXT-12, FL-08, FL-09, MCK-03

**Blocked by:** 97, 98, 102

**Status:** open

- [ ] Camel route tests: one REST and one SOAP bank profile (AVL, credit, status, reverse, error mapping)
- [ ] `LinkBankAccountIT`: AVL success links the account (encrypted at rest, last 4 shown); AVL failure links nothing
- [ ] T1–T12 for W2B (T3: timeout then STATUS success with no double debit; T4: release; T7: duplicate bank callback)
- [ ] C2W with a linked account still passes T1–T12 (regression on `08-add-money-bank`)
- [ ] No full account number in logs (`scripts/log-scan.sh` extended with an account pattern if missing)
- [ ] `./gradlew :mw-adapter-bank:build :mw-orchestrator:build :mw-app-gateway:build :mw-simulators:build :mw-admin-api:build :contractsLint` green with Docker
- [ ] API suite folder `51-bank-avl-w2b` passes
- Split note: if it exceeds 1,500 lines, split into 117a (AVL + linked accounts + profiles) and 117b (W2B flow).
