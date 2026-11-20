# HANDOFF — FRS run 89–138 (state for another coding agent)

Updated: 2026-11-20 (after 2308d22e). Kept current after every merge/split; trust DEPENDENCIES.md for row status.

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
96a (inquiry schedule + ops alert), 97 (adapter REVERSE + health),
99 (duplicates/replay), 100a (client registry + APISIX sync), 101 (springdoc + drift tests), 102 (mock-control engine, a+b),
103 (core mock-control + global reset), 105 (app preview/confirm, Newman folder 46; live suite and tester-e2e not run),
110 (push notifications), 110b (binding epoch from mw-auth; notify migration V17),
123 (AML/trustbank/DW file mocks), 130 (audit FRS; PayloadScrub after 4 review rounds: markup containing a secret word is stored as `****` whole, user decision),
131a (partner credentials backend), 131b (maintenance window + MOCK/REAL endpoint profile; maker-checker actions that leave the route untouched).
Review fix on that range: 2308d22e (19 files +342 −57). 88 is superseded by 126/127.

## In flight when this file was written
None. The salvage worktrees for 95b, 105, 110b and 131b are removed. Other `.claude/worktrees/agent-*` trees are historical; leave them.

## Next (frontier, in priority order)
1. 98 (routing; unblocked, 96a is done) -> 104 -> **M7 gate**. Folder 46 and the test-console preview step are written; the live API suite and tester-e2e were not run. Top-up operator resolution stays in 98.
2. Unblocked and not started: 95c, 96b, 100b, 106/107/108/109, 111. Then **M8 gate**.
3. M9–M13 per DEPENDENCIES.md. 131b is done, so 115 and 118–121 no longer wait on the maintenance engine; their routing still waits on 98, and 100b still runs before 118–121. 128, 130b, 131c and 135 are unblocked. 96c waits on 96b; 100c before M13.

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
- 97 / 131b: maintenance and the endpoint profile republish with the live config. Routing that treats the partner as MAINTENANCE waits for 98.
- 101: WSDL only after 108 (adapters serve no SOAP).
- 105: top-up operator resolution waits for 98. Live Newman folder 46 and tester-e2e wait for the M7 gate.

- 123: AML file carries clear MSISDNs (watch-list rules); ticket 125 asks for masked/tokenised parties -> ask the user (security) when 125 starts. DW file layout is 123's draft; 128 must match it.
