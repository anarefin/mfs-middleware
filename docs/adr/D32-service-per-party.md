# D32: One service per party family, a 12 GB stack budget

Status: Accepted, 2026-11-19 (user decision, FRS planning, ticket 89). Amends spec 4.1 (services),
M8 and the `:composeCheck` budget (8 GB, ticket 62); keeps D1/D28 (Camel only in `mw-adapter-*`).

## Context

The FRS (`spec/FRS-Upay-Middleware.md` 2.1, 6.2) lists 18 external parties and five back-office
modules. The repo has three partner adapters (telco, billpay, bank) and no recon, compliance or
data-export service. Spec 4.1 already planned `mw-adapter-interop`, `mw-compliance` and `mw-recon`
and put the card, remittance and ATM parties into `mw-adapter-bank`, which would make one adapter
carry REST, SOAP, card tokens, inbound remittance and ATM traffic in a single bulkhead. Each new
service costs memory on the single host, and the laptop budget of 8 GB was already used to 8176 MiB.

## Decision

- **New partner adapters**, each a copy of the adapter framework (`partner/`, Camel YAML routes
  from the `mw-*` Kamelets, D28): `mw-adapter-card` (CyberSource), `mw-adapter-remit` (UCB IMT),
  `mw-adapter-atm` (UCB ATM gateway, SOAP), `mw-adapter-interop` (NPSB and BanglaQR, ISO 20022),
  `mw-adapter-merchant` (the merchant system, SOAP). The three existing adapters keep their
  families (OCS; utility and bill aggregators; banks for AVL, C2W and W2B).
- **New back-office services, without Camel**: `mw-compliance` (EC eKYC, the AML feed and its
  HOLD/BLOCK actions), `mw-recon` (MT940/942 and settlement matching, reports, exceptions; it
  supersedes ticket 88 with tickets 126 and 127 and the FRS class names) and `mw-data-export` (DW
  topics, the daily SFTP batch, PII tokenisation, retention). They reach SFTP and HTTP partners
  through `client/` code (sshj, Spring `RestClient`, Resilience4j), never `org.apache.camel`.
- Each new service is created with `scripts/new-service.sh` and follows every repo rule (own
  schema, own `contracts.yml`, memory limit in its compose block).
- **Budget:** `scripts/compose-check.sh` defaults `MW_RAM_BUDGET_MIB` to 12288 (12 GB) instead of
  8192: the `core` profile is the full FRS stack. The `minimal` profile stays the laptop demo (P2P
  and the cash path, well under 8 GB); a laptop runs `--profile minimal`, the full stack wants a
  16 GB+ host.

### Also decided with this record

- **New error namespace `MW-CMP-*`** for compliance refusals (FRS 3.7 `CMP-001` AML hold,
  `CMP-002` KYC incomplete), built by ticket 125 (and 124 for KYC). It joins the namespaces of spec
  5.3 and the root `CLAUDE.md` error model when the first code ships.
- **The AML real-time pre-check fails open with an alert** (FRS CMP-03, ticket 125): when
  mw-compliance does not answer in its budget, the orchestrator lets the transaction continue,
  raises an alert and audits the skipped check; the hourly AML feed (CMP-01) still screens it. A
  rule hit (a clear "stop") refuses the transaction with `MW-CMP-001`. Payments are never blocked
  by a compliance outage (FRS 8 preamble).
- **Ticket 88 is superseded** by tickets 126 (matching) and 127 (reports).

## Consequences

- 24 modules instead of 16; build time and the gate grow with them (one more JVM per module in
  `./gradlew clean build`).
- Each party family can be restarted or redeployed alone (NFR-07), with its own bulkhead (PLT-033).
- The `partner/` framework now has eight copies to keep identical (`diff -r` after the package
  rename, as for the three today).
- README, CLAUDE.md and the compose header state the 12 GB budget; the 8 GB laptop note applies to
  `--profile minimal`.

## Where in the repo

- `scripts/compose-check.sh` (the budget default)
- `services/` (the new modules, from tickets 115 and 118-128)
- spec decision table (D32), section 4.1
