# HANDOFF — FRS run 89–138 (state for another coding agent)

Updated: 2026-11-20 ~19:30 (114, 115b, 119a, 120a merged on upay-main. No M9 gate. 117e and later are not started).

## Where things are
- Repo: `/Users/ahmadnaqibularefin/Projects/mfs-middleware`, integration branch **`upay-main`** (not pushed, no PR).
- Plan of this run: `~/.claude/plans/check-from-ticket-88-purrfect-dusk.md`. FRS plan: `~/.claude/plans/we-have-to-implement-harmonic-puzzle.md`.
- Spec: `spec/FRS-Upay-Middleware.md` (+ `spec/bits-mfs-middleware-spec.md`). Repo rules: root `CLAUDE.md`, each `services/*/CLAUDE.md`.
- Tickets: `.scratch/bits-mfs-middleware/issues/NN-*.md`; **status + order: `issues/DEPENDENCIES.md`** (authoritative).
- Split decisions (user-approved): `issues/95-split-decisions.md`, `100-split-decisions.md`, `131-split-decisions.md`, `96-split-decisions.md`, `107-split-decisions.md`, `109-split-decisions.md`, `112-split-decisions.md`.
- Implementer brief (give to every implementer): `.scratch/bits-mfs-middleware/notes/implementer-brief.md`; code map: `notes/frs-run-notes.md`.
- FRS id status per requirement: `docs/frs-traceability.md`. ADRs for this run: `docs/adr/D29..D33`.

## Done and merged on upay-main
89 (ADRs/traceability), 90 (Redis 8.10.3 AGPL — written OWN-03 approval still open), 91 (HashiCorp Vault 2.2.2), 92 (contracts),
93 (core-mock /core/v1 REST), 94 (core-mock model), 95a (orchestrator -> core over REST),
95b (mw-auth and mw-admin-api on /core/v1; `core.command.v1` retired; DROP_REPLY and NO_CALLBACK lose the REST answer),
95c (hold expiry: a live saga whose partner has not succeeded confirms the core's hold release; once the partner has succeeded the saga stops for operations; wallet-state events are consumed and move no money),
96a (inquiry schedule + ops alert),
96b (partner REVERSE after a refused commit; :mw-orchestrator:build passed; the live suite was not run),
96c (completed TOPUP or BILL_PAY: partner REVERSE first, core reversal only after the partner accepts; a partner refusal ends MANUAL_REVIEW and leaves the posting),
97 (adapter REVERSE + health),
98 (routing in mw-orchestrator, including top-up operator resolution and maintenance),
99 (duplicates/replay), 100a (client registry + APISIX sync), 100b (OAUTH2_CC bearer check; inbound mTLS stays 100c),
101 (springdoc + drift tests), 102 (mock-control engine, a+b),
103 (core mock-control + global reset),
104 (P2P FRS harness, Newman folder 41-p2p-frs because 40 was already taken; live Newman and the M7 gate were not run),
105 (app preview/confirm, Newman folder 46; live suite and tester-e2e not run),
106 (distributor, DSO and partner apps and B2B float, Newman folder 63; session length, device cap and the first PIN lock follow the app type; B2B float posts like P2P from the caller's own wallet),
107a (saved billers, favourites and the customer inbox; saved billers and favourites are not encrypted, user decision),
107b (profile, limits and statement; app-gateway is a core client for profile, limits, and statement; the live suite was not re-run after this merge),
108 (app SOAP facade on mw-app-gateway, Newman folder 48; the same services as REST),
109b (USSD screen timeout, back stack, short codes, language), 109a (four MNO SMPP binds, same 3GPP TLV tags),
109c (USSD menus; fee from core charges/calculate; change-PIN stays on the maintenance text),
110 (push notifications), 110b (binding epoch from mw-auth; notify migration V17),
111 (notify campaigns, promotional opt-out, masked log; folder 47),
112a (four operator OCS profiles, no amount rule),
112b (postpaid amount from the stored minimum due through the outstanding bill; preview returns both, confirm does not fetch again, out of range refused before a hold),
123 (AML/trustbank/DW file mocks),
126 (recon matching and classification, Newman folder 60; statements and settlement lines are classified and no money moves),
128 (mw-data-export stream, daily SFTP, PII policy, archive),
130 (audit FRS; PayloadScrub after 4 review rounds: markup containing a secret word is stored as `****` whole, user decision),
131a (partner credentials backend), 131b (maintenance window + MOCK/REAL endpoint profile; maker-checker actions that leave the route untouched),
131c (admin UI partner tabs; live Playwright not run).
Review fix on the earlier salvage: 2308d22e (19 files +342 −57). 88 is superseded by 126/127.
M8 review fixes merged at 680ad323 (8 files +356 −26): SOAP confirm and balance use the device gate, the USSD mini statement returns the latest movements, and XSD boolean 1 is a confirmed duplicate.
112a merged at eba33d4b (implementer dc7c74e0): four operator OCS profiles.
Partner disbursement merged at d2407234 (ccdcb713, 1bb02984, 9dd54f10): a partner disbursement posts as an organisation wallet, wallet 01412000001 is seeded ORG, and a seeder-marked organisation wallet logs in as a partner. An unmarked organisation wallet gets no app token. No PARTNER core wallet type.
112b merged at c5dd4395 (implementer b79ccbd9, 46 files +1372 −78): postpaid confirm accepts any amount from the stored minimum due through the outstanding bill. Preview returns both, confirm does not fetch again, and an amount outside that range is refused before a hold.

## In flight when this file was written
`ticket/m8-partner-disburse` is merged. Its worktree stays at `/Users/ahmadnaqibularefin/Projects/mfs-worktrees/ticket-m8-partner-disburse`. Leave that worktree alone. `ticket/112b-postpaid-amount` is merged. Its worktree stays at `/Users/ahmadnaqibularefin/Projects/mfs-worktrees/ticket-112b`. Leave that worktree alone. 112c is next and not started.
126 is merged. The `ticket/126-recon-matching` worktree is already removed. The wave worktrees for 98, 100b, 111, 128 and 131c are removed. The 112a worktree stays. Other `.claude/worktrees/agent-*` trees are historical; leave them.

## Next (frontier, in priority order)
1. **M8 gate (2026-11-20) was this run** (base d84fce30, code tip e9381fed). Passed: clean build, stack up, smoke 60/60, edge 66/66, new folders 216/0, log-scan clean. Whole suite once: 1483 assertions, 3 failed, not re-run. Folder 25 (dev PIN lock) and folder 28 (statement pages) were fixed and rechecked with folder 36 (179/0). Folder 36's replay 429 `MW-SYS-RATE_LIMITED` stays (the 60s window is not waited out). Folder 13 and folder 44 passed. ui-e2e: 21 passed and 4 failed, then those 4 passed. tester-e2e: 36 passed and 4 failed; the three USSD specs then passed. Leftover: tester top-up REJECTED `MW-SYS-PARTNER_CIRCUIT_OPEN`.
2. **126** is merged and its worktree is removed. **106, 107, 108, and 109** are merged.
3. **112c** is merged (31 files, +1005 −27, implementer 266d341a). T5 is skipped because it only passes by waiting out the inquiry schedule. T11 is ticket 125 and T12 is ticket 127. The session stopped after this merge. 113, 115, 117, 130b, 135, and 118–121 are not started. 100c stays until M13.

## Milestone gate procedure (after the last ticket of a milestone)
1. Code review of the milestone range; one fix pass.
2. `./gradlew clean build` (no `-PwithSlow` until M13; user: ignore slow-wait tests; no perf/chaos).
3. Fresh stack from `deploy/`: `deploy/reset.sh`, re-copy `dev.env.defaults` to `.env` (Redis/Vault/APISIX_VAULT_TOKEN/KC_MW_ORCHESTRATOR_SECRET/PUSH_API_KEY knobs are new),
   `docker compose --profile core --profile seed --profile tester up -d --build --wait`, then `scripts/smoke.sh`, `scripts/edge-test.sh`,
   `scripts/api-test.sh` (whole suite; new folders 36, 38, 39, 40, 42, 43 never run live yet), `scripts/log-scan.sh`; UI suites at gates touching UIs.
4. Fix failures per root cause; record the gate line in DEPENDENCIES.md.

## Working rules (user decisions)
- Max 5 implementer agents at once; one merger at a time; implementers run only `./gradlew :mw-x:build` for touched modules.
- Latest stable versions for anything added/touched. Constructor injection only, masking rules, never change another service's contract.
- Oversized tickets: split yourself (record `NN-split-decisions.md`), ask the user only for money-movement, security or foreign-contract choices.
- Stop and ask on: ticket > ~2 h without split option, gate fix > 45 min, a test run > 30 min.
- Security review findings: fix test-first before merge (or as a follow-up row if already merged).

## Known flaky under parallel load (pass alone)
RetryTopicIT (admin-api/notify/telco), AuthClientIT (app-gateway/ussd), CryptoClientIT (mw-auth), mw-crypto HSM ITs
(SignaturesIT, CryptoApplicationIT, PayShieldProviderTest), checkout JsonLoggingIT/StatusReplyConsumerIT, SimulatorsApplicationIT
(an Ollama on localhost), UssdHttpFlowIT GroupIdNotFound, test_chaos.sh timing.

## Open items to carry
- Redis AGPL: written approval by the Bits architecture owner (ADR D29).
- Partner BULK_DISBURSE posts as an organisation wallet (merged d2407234). The core wallet contract has no PARTNER type. An unmarked organisation wallet gets no app token.
- 130: CHANNEL_IN / CORE_CALL / NOTIFICATION audit hops -> 130b; admin timeline UI -> 135.
- 100a: `$secret://vault` lookup only IT-tested; live edge tests + folder 41 -> 100c.
- 97 / 131b / 98: maintenance, the endpoint profile, and routing-as-MAINTENANCE are on upay-main.
- 101: the app SOAP WSDL is published with 108; adapters serve no SOAP.
- 105 / 98: top-up operator resolution is in the routing module. Folders 35, 41, 46, 47 and 62 ran in the M7 suite (the two failures were folders 13 and 44). ui-e2e and tester-e2e passed.

- 123: AML file carries clear MSISDNs (watch-list rules); ticket 125 asks for masked/tokenised parties -> ask the user (security) when 125 starts. DW file layout is 123's draft; 128 must match it.

## Session 2026-11-20 afternoon (plan ~/.claude/plans/all-pending-tickets-dont-scalable-honey.md)
User rules: 1 h cap per task then report + ask; at gates e2e first, then API; lean gates M9-M12 (new + affected Newman folders), full suite only at 138.
Merged on upay-main: 117a (fa5ac85c), 127a, 113a, 116a (897a406b), 115a (3ff04653; admin-api migration renumbered V17).
Splits: 113, 115, 116, 117, 127 (`issues/NN-split-decisions.md`). New: 139 (dry-run OAuth2 isolation, security follow-up, all adapter copies).
User decision: 127c adds `GET /core/v1/reports/emoney-total` to the core wallet contract (open item for Upay's real core).
In flight at 15:45: 113c, 116b, 117b, 117d, 127b (worktrees under .claude/worktrees/agent-*).

## STOPPED 2026-11-20 ~19:30 (window ended before 19:45; no M9 gate)
upay-main `06d5d599` (not pushed, no PR). Merged this window, in order: 120a (`372dceb2`), 119a (`bfa47ddf`), 115b (`8e457021`), 114 (`06d5d599`).
Module builds were green. Newman was not run (gate work): folder 51 bill aggregators, 52 merchant SOAP. 119b owns 53, 120b owns 54. 117e must not take 51.
120a: inbound SOAP is stored in the adapter; `mw.partner.inbound.v1` cannot carry an ATM debit, so publishing waits for 120b. Remit listens on 8101 because ATM owns 8100.
115b worktree `.claude/worktrees/agent-a450dce93dadacbb8` is still locked by another session. 120a and 119a worktrees were removed after merge.
Next: M9 gate (lean: e2e first, then Newman 06 09 19 47-50 51 52 61), then 117e, 118b, 119b, 120b, 121+.
No code review of the whole run: the spec is not finished.

## STOPPED 2026-11-20 ~16:55 (usage limit)
Merged on upay-main since 15:45: 113b, 113c (113 done), 116b, 116c (116 done), 127b, 127c (127 done), 117b, 117c, 117d, 118a, 139,
plus fix 93d0ad33 (BILLER in every ConfigType copy). The post-merge rebuild of orchestrator + app-gateway after 117c was stopped (not run).
User decisions: 127c e-money total endpoint (done); USSD W2B stays app-only ("use the app" message), recorded for 117e/gate.
Stopped mid-work (worktrees under .claude/worktrees/agent-*; branches kept, uncommitted work may exist):
- 114 bill aggregators: branch ticket/114-bill-aggregators (was building).
- 115b merchant pay flow: branch ticket/115b-merchant-pay-flow (was merging upay-main; renumber orchestrator migration past V18).
- 119a remit adapter: branch ticket/119a-remit-adapter. 120a ATM adapter: branch ticket/120a-atm-adapter-inbound.
Next: finish/merge those four, then M9 gate (lean: e2e first, then Newman folders 06 09 19 47-50 61 + new), then 117e, 118b, 119b, 120b, 121+.
Gate checks to remember: test-console app-users.test.ts fails 1; USSD bill month = current Dhaka month (assumption);
e-money total counts all wallet types but SYSTEM (kept unless user objects).

## STOPPED 2026-11-20 night (usage limit; plan ~/.claude/plans/implement-pending-tickets-after-hidden-nautilus.md)
upay-main 50d44dc8 (not pushed). Merged this session: 133a, 118b1, 117e, 121a, M9 fix A, M9 fix recon, 124a, 118b2, 121b1, 120b1, 119b, 129a, M9 fix B. Rows/splits in DEPENDENCIES.md. Review: notes/m9-review.md.
User decisions: lean gates M9-M12 then full e2e from reset; AML tokenised (HMAC); bank link name match + rate limit (done); recon dispute/accept-as-explained always maker-checker (todo); DOB-only eKYC -> MANUAL_REVIEW (124b); core PUT /core/v1/accounts/{walletId}/kyc-level (124b).
Stopped mid-work (branches/worktrees under .claude/worktrees/agent-*; may hold uncommitted work):
- 119c ticket/119c-remit-lookup-suite: FIRST fix HIGH REMIT_IN assertion bypass in mw-core-mock TransferPayload (REMIT_IN may skip assertion only when debit = remittance settlement wallet).
- 120b2 ticket/120b2-atm-token-issue: also ATM nonce purge (own expiry, DB clock), canonical Base64 nonce; NEW security: mw-auth allowed-clients gives mw-adapter-atm broad trust (restrict by path to /internal/v1/atm-tokens/verifications; InternalDeviceController app-gateway only); AtmTokenService step-up assertion not consumed on issue (compare-and-delete or idempotencyKey unique).
- 121b2 ticket/121b2-npsb-seed-inbound-mock: incl. pacs.008 freshness window + permanent NPSB dedup.
- 129b ticket/129b-bulk-preview. 130b ticket/130b-audit-hops.
Then: 120b3, 121c, 122, 124b, 125, 129c-e, 133b-d, 132, 135, 100c, 134 (+recon maker-checker), 136, 137, 138; M9 gate (lean) still owed; final full e2e from deploy/reset.sh.
