# 81: Spring Kafka in mw-orchestrator and mw-notify

**What to build:** Port the saga and notification services to Spring Kafka (template from ticket 78) and remove Camel from them.

- **mw-orchestrator:** six retry consumers: `TxnRequestRoute`, `TxnActionRoute`, `CoreWalletEventRoute`,
  `CorePaymentEventRoute`, `PartnerReplyRoute`, `PartnerInboundRoute`. They become `@KafkaListener` methods on the five
  existing `messaging/*Listener` beans, which today take `Exchange` and read the body and headers from it (`TxnRequestListener.java:25-35`).
  - Keep the switch `mw.orchestrator.consumers.enabled`.
  - `client/DlqClient` reads `spring.kafka.bootstrap-servers`.
  - The state machine stays plain Java; this ticket does not change saga logic.
- **mw-notify:** `TxnStatusRoute`, `NotifyRequestRoute` and `ConfigChangedRoute` become retry listeners. SMPP (jSMPP) and mail
  (Spring Mail) are unchanged; they never used Camel. Remove the stale "notify rules evaluated by a Camel route" wording from its `CLAUDE.md`.
- **The rest, as in 79:** publisher, outbox scheduler, `application.yml`, delete `route/` and the Camel deps, tests, `CLAUDE.md`.
- **New isolation rule:** `scripts/check-isolation.sh` gets a rule that fails when a module that is not `mw-adapter-*` (and
  not the template) imports `org.apache.camel` or declares a camel dependency. Add a shell test in `scripts/test/`.

**Blocked by:** 78 (and 79/80 for the isolation rule to pass, so land the rule last)

**Status:** open

- [ ] `grep -rl org.apache.camel services/{mw-orchestrator,mw-notify}` is empty
- [ ] `./gradlew :mw-orchestrator:build :mw-notify:build` is green with Docker, conformance ITs included
- [ ] `./gradlew :checkIsolation :scriptTests` pass; the new rule's shell test shows it fails on a Camel import in a non-adapter module
- [ ] With the stack up: the full `scripts/api-test.sh` (folders 00–28) passes on a fresh stack (`deploy/reset.sh`, then the `up` with seed)
