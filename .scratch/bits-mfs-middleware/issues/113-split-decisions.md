# 113 split (2026-11-20, implementer option A, auto-split rule)

- **113a-biller-catalogue-owner** (done, 0d0f506d..a79d4435): mw-admin-api `biller` (V16), list + maker-checker change
  requests, `mw.config.changed.v1` BILLER (BillerValue), 57-biller dev seed loaded by admin-api; strict field-regex
  grammar (no groups/alternation/backrefs/lookarounds, ≤8 quantifiers, one variable-width, bounds ≤64, input ≤64 chars);
  billpay projection, billMonth, 503 MW-SYS-BILLER_DISABLED; BILLER config type in orchestrator, ussd, notify, telco.
- **113b-biller-channel-projections** (113a): mw-app-gateway + mw-ussd biller lists/prompts from the catalogue, USSD Pay Bill
  DYNAMIC node.
- **113c-biller-amount-rule** (113a): orchestrator BILLER projection + FIXED/MINIMUM/OPEN amount rule in `BillPayFlow`,
  mw-adapter-bank BILLER config type (one line), Newman folder `47-biller-catalogue` + `06-billpay` re-run kept for the gate.
