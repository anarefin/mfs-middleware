# 117 split (2026-11-20, implementer option 1, auto-split rule)

- **117a-bank-adapter-avl-w2b** (done on `ticket/117-bank-avl-w2b` 692ab069): bank adapter AVL (holder name masked to first
  initial) + W2B credit, canonical account number + direction, 20 bank profiles (12 REST, 8 SOAP) generated from
  `deploy/partner-routes/bank/bank-profiles.csv` by `generate.mjs` (adapter dev seeds + admin-api seeds), Vault entries,
  `BankProfileRouteIT`, `scripts/test/test_bank_profiles.sh`.
- **117b-avl-api-linked-accounts** (117a): synchronous account-validation API on mw-adapter-bank (contract owned by the bank
  adapter) + linked bank accounts in mw-app-gateway (table, encrypted account number, `/app/v1/bank-accounts`, `LinkBankAccountIT`).
- **117c-w2b-flow-channels** (117b): orchestrator `W2bFlow`, add-money from a linked account, app preview/confirm, USSD menu.
- **117d-bank-gateway-simulator** (117a): simulated bank gateway in mw-simulators generated from the same CSV, trigger table.
- **117e-bank-suite-regression** (117b,117c,117d): Newman folder `51-bank-avl-w2b`, T1–T12, log-scan account-number pattern.
