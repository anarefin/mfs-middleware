# HANDOFF — FRS run 89–138 (state for another coding agent)

Updated: 2026-11-20 (after merging ticket 96b). Kept current after every merge/split; trust DEPENDENCIES.md for row status.

## Where things are
- Repo: `/Users/ahmadnaqibularefin/Projects/mfs-middleware`, integration branch **`upay-main`** (not pushed, no PR).
- Plan of this run: `~/.claude/plans/check-from-ticket-88-purrfect-dusk.md`. FRS plan: `~/.claude/plans/we-have-to-implement-harmonic-puzzle.md`.
- Spec: `spec/FRS-Upay-Middleware.md` (+ `spec/bits-mfs-middleware-spec.md`). Repo rules: root `CLAUDE.md`, each `services/*/CLAUDE.md`.
- Tickets: `.scratch/bits-mfs-middleware/issues/NN-*.md`; **status + order: `issues/DEPENDENCIES.md`** (authoritative).
- Split decisions (user-approved): `issues/95-split-decisions.md`, `100-split-decisions.md`, `131-split-decisions.md`, `96-split-decisions.md`.
- Implementer brief (give to every implementer): `.scratch/bits-mfs-middleware/notes/implementer-brief.md`; code map: `notes/frs-run-notes.md`.
- FRS id status per requirement: `docs/frs-traceability.md`. ADRs for this run: `docs/adr/D29..D33`.

## Done and merged on upay-main
89 (ADRs/traceability), 90 (Redis 8.10.3 AGPL — written OWN-03 approval still open), 91 (HashiCorp Vault 2.2.2), 92 (contracts),
93 (core-mock /core/v1 REST), 94 (core-mock model), 95a (orchestrator -> core over REST),
95b (mw-auth and mw-admin-api on /core/v1; `core.command.v1` retired; DROP_REPLY and NO_CALLBACK lose the REST answer),
96a (inquiry schedule + ops alert),
96b (partner REVERSE after a refused commit; :mw-orchestrator:build passed; the live suite was not run),
97 (adapter REVERSE + health),
98 (routing in mw-orchestrator, including top-up operator resolution and maintenance),
99 (duplicates/replay), 100a (client registry + APISIX sync), 100b (OAUTH2_CC bearer check; inbound mTLS stays 100c),
101 (springdoc + drift tests), 102 (mock-control engine, a+b),
103 (core mock-control + global reset),
104 (P2P FRS harness, Newman folder 41-p2p-frs because 40 was already taken; live Newman and the M7 gate were not run),
105 (app preview/confirm, Newman folder 46; live suite and tester-e2e not run),
110 (push notifications), 110b (binding epoch from mw-auth; notify migration V17),
111 (notify campaigns, promotional opt-out, masked log; folder 47),
123 (AML/trustbank/DW file mocks), 128 (mw-data-export stream, daily SFTP, PII policy, archive),
130 (audit FRS; PayloadScrub after 4 review rounds: markup containing a secret word is stored as `****` whole, user decision),
131a (partner credentials backend), 131b (maintenance window + MOCK/REAL endpoint profile; maker-checker actions that leave the route untouched),
131c (admin UI partner tabs; live Playwright not run).
Review fix on the earlier salvage: 2308d22e (19 files +342 −57). 88 is superseded by 126/127.

## In flight when this file was written
None. The wave worktrees for 98, 100b, 111, 128 and 131c are removed. Other `.claude/worktrees/agent-*` trees are historical; leave them.

## Next (frontier, in priority order)
1. **96b is merged.** 96c and 95c are the next orchestrator tickets and are not started.
2. **The M7 live gate is unblocked** (104 and 96b are both on upay-main) and was not run. 104's done check runs `scripts/api-test.sh` on a fresh stack. Folders 35, 41, 46, 47 and 62 are written and have not been run live.
3. Unblocked and not started: 95c, 96c, 106/107/108/109, 115, 117, 130b, 135. Then **M8 gate** after the app and USSD tickets.
4. 118–121 still name parent ticket 95, and 95c is open. 100c stays until M13. 132 can use the routing API from 98.

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
- 130: CHANNEL_IN / CORE_CALL / NOTIFICATION audit hops -> 130b; admin timeline UI -> 135.
- 100a: `$secret://vault` lookup only IT-tested; live edge tests + folder 41 -> 100c.
- 97 / 131b / 98: maintenance, the endpoint profile, and routing-as-MAINTENANCE are on upay-main.
- 101: WSDL only after 108 (adapters serve no SOAP).
- 105 / 98: top-up operator resolution is in the routing module. Live Newman folders 35, 46, 47 and 62, plus tester-e2e and ui-e2e, wait for the M7 gate.

- 123: AML file carries clear MSISDNs (watch-list rules); ticket 125 asks for masked/tokenised parties -> ask the user (security) when 125 starts. DW file layout is 123's draft; 128 must match it.
