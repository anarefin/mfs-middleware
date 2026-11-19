# 94: mw-core-mock data model to FRS 7.1/7.3 (accounts, holds, tariff, limits, system wallets, statements, seed)

**What to build:** Extend the core-mock ledger so reversal, fee and reconciliation logic is tested against realistic money
movement: the missing account types and statuses, KYC level, hold expiry, tariff slabs with VAT and commission split,
KYC-based limits, per-partner settlement wallets and statements, seeded from versioned files.

**Scope (today -> change):**
- Accounts (`entity/Wallet`, Flyway `coremock`): types CUSTOMER/AGENT/MERCHANT/ORG + FEE-POOL/SETTLEMENT -> add `DISTRIBUTOR`,
  rename/alias ORG = `ORGANISATION`, `SYSTEM`; statuses ACTIVE/LOCKED/BLOCKED/HOLD -> add `DORMANT`, `CLOSED`; add `kyc_level`
  (`BASIC`, `FULL`). Wallet-state events carry the new fields (mw-auth projection reads type and status).
- Holds (`entity/Hold`): add `expires_at` (default 30 min, `mw.coremock.hold-ttl`) and status `EXPIRED`; a ShedLock
  `scheduler/HoldExpiryJob` releases expired holds and publishes `HoldExpired` (new payload in `core.event.v1`, contract + samples).
- Tariff: table `tariff(txn_type, channel, customer_type, slab_min, slab_max, fee, vat_pct, commission_split jsonb)` replaces the
  flat fee in `CoreMockProperties`; WAL-04 returns fee, VAT and commission; WAL-07 commit posts to the credit party, `FEE_INCOME`,
  `VAT_PAYABLE`, `COMMISSION_PAYABLE` (agent/distributor shares) with double entries.
- Limits: table `limit_rule(txn_type, customer_type, kyc_level, per_txn_min/max, daily_count/amount, monthly_count/amount)`;
  WAL-05 returns pass/fail with remaining daily and monthly amounts.
- System wallets: `FEE_INCOME`, `VAT_PAYABLE`, `COMMISSION_PAYABLE`, settlement per partner (`SETTLEMENT_OCS_GP`..`_BL`,
  billers/aggregators, banks, `NPSB`, `CYBERSOURCE`, `UCB_IMT`, `UCB_ATM`); WAL-10 credits name their settlement wallet.
- Statements: WAL-12 `GET /statements?walletId&from&to&page` over `entry`.
- Seed: versioned files `src/main/resources/seed/v1/{wallets.csv,tariff.csv,limits.csv,system-wallets.csv}` loaded by `WalletSeeder`
  (1,000 customers, 50 agents, 10 distributors, 20 merchants, 3 organisations); `mw-seeder` personas updated
  (`build/seed/manifest.json`) for distributors and the third org.
- Docs: `services/mw-core-mock/CLAUDE.md` (rules, trigger table), contract samples.
- Split if over budget: 94a (types, KYC, hold expiry, statements, seed) / 94b (tariff, limits, system wallets).

**FRS IDs covered:** 7.1 (all entities), WAL-04, WAL-05, WAL-07, WAL-10, WAL-12, 7.3 (hold expiry event, seed counts, realistic tariff and limit sheet)

**Blocked by:** 93

**Status:** open

- [ ] Unit tests first: tariff slab selection and VAT/commission arithmetic in poisha (rounding rule documented); limit evaluation by KYC level, daily and monthly
- [ ] `HoldExpiryIT`: an expired hold is released, `HoldExpired` published once, available balance restored
- [ ] `LedgerInvariantIT`: after a mixed run, the sum of entries per txnId is zero and system wallets hold fee + VAT + commission
- [ ] Seed IT: counts 1000/50/10/20/3 and all system wallets exist; reseed is idempotent
- [ ] `./gradlew :mw-core-mock:build :mw-seeder:build :mw-auth:build :contractsLint` green with Docker
