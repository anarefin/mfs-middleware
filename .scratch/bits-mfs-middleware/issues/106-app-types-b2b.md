# 106: Distributor, DSO and Partner apps; B2B float transfer; per-app-type session policy (FRS CH-APP-02..07, UC-07)

**What to build:** Today `mw.app-gateway.txn-type-roles` lets only customer, agent and merchant roles transact;
Distributor and DSO tokens get 403 `MW-AUTH-002`, there is no Partner app and no B2B flow. mw-auth issues a 5 min access
token, one device per wallet, lockout 1h/24h/permanent — none of it configurable per app type.

**Scope:**
- **Roles:** mw-auth role claim from the wallet type for DISTRIBUTOR, DSO (agent-like staff wallet) and PARTNER (ticket
  94 adds the core types); `txn-type-roles` in mw-app-gateway: B2B_TRANSFER for distributor and DSO, disbursement for
  partner app only (CH-APP-06).
- **B2B float (UC-07):** txnType `B2B_TRANSFER` (added by ticket 92): distributor → agent (and agent → distributor
  return), DSO-initiated on behalf of the distributor; `mw-orchestrator` `B2bTransferFlow` (core-only, PostTransfer
  pattern of `P2pFlow`); preview/confirm group `b2b` (ticket 105); notify rules for both parties (mw-notify seed).
- **Per-app-type policy (mw-auth config):** access token TTL (default 15 min, CH-APP-02), devices per wallet (default 1),
  concurrent session limit, idle timeout, PIN failures before lock (default 5, lock 24 h; CH-APP-07) as
  `mw.auth.app-types.<type>.*`; keep today's values for customer where the FRS is silent.
- Contracts: `app.v1.openapi.yaml` (b2b group), `contracts/api/mw-auth/auth.v1.openapi.yaml` (claims doc only),
  `mw.txn.request.v1` samples for B2B_TRANSFER.
- Seeder: personas for one distributor, one DSO, one partner app user; test console persona switcher.
- Docs: mw-auth and mw-app-gateway `CLAUDE.md`; spec 18.7 amendment goes in ticket 89's traceability doc.

**FRS IDs covered:** CH-APP-02, CH-APP-03, CH-APP-04, CH-APP-06, CH-APP-07, UC-07

**Blocked by:** 94, 105

**Status:** open

- [ ] `AppTypePolicyTest` (mw-auth): TTL, device count, session limit and lock threshold read per app type
- [ ] `ConcurrentSessionIT` (mw-auth): the (n+1)th session ends the oldest for a type with limit n
- [ ] `B2bTransferFlowTest` + `B2bTransferIT` (orchestrator): distributor → agent completes; customer token refused
      403; insufficient float → FAILED before posting
- [ ] app-gateway `RoleScopingTest`: each app type can call only its allowed groups
- [ ] `./gradlew :mw-auth:build :mw-app-gateway:build :mw-orchestrator:build :mw-notify:build :mw-seeder:build :contractsLint` green with Docker
- [ ] With the stack up: new API folder `42-app-types-b2b`; folders 24, 25, 26 still green
