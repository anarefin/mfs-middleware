# 95: mw-orchestrator talks to the core over REST (ADR D31)

**What to build:** Replace the orchestrator's Kafka core commands (`core.wallet.command.v1`, `core.payment.command.v1` and their
events) with a REST client to `/core/v1` (ticket 93). The saga keeps its states; an ambiguous core call goes to `UNKNOWN`
and is resolved by WAL-13. Other core command producers move to REST in the same change, then the core command topics retire.

**Principles:**
- Never block a Kafka listener thread: saga steps that call the core run on a virtual-thread executor; the step result
  re-enters `SagaEngine` as a `SagaEvent` in its own transaction (step progress persisted, PLT-021).
- No retry of a money call without its `txnId` idempotency (PLT-031); reads and WAL-13 are retried.

**Scope:**
- `client/CoreWalletClient` (RestClient + Resilience4j: connect/read timeouts, breaker, bulkhead) in mw-orchestrator; config in
  `config/OrchestratorProperties`; service JWT/mTLS.
- `service/SagaEngine`, `service/saga/*Flow` effects: `Effect` core commands -> REST calls; `CoreEventService`/`messaging/CoreEventListener`
  for replies removed; listener kept only for `HoldExpired` and wallet-state events (a hold-expired on a live saga -> `RELEASING`
  path / `MANUAL_REVIEW` when the partner already succeeded).
- `service/InquiryService`: core inquiry via WAL-13 (`CoreTxnStatus` mapping unchanged).
- Assertion: `mw.txn.request.v1` assertion forwarded as `X-Txn-Assertion` on hold / transfer / credit.
- Other producers: mw-auth `VerifyCustomerFactors` -> REST (`POST /core/v1/customers/{walletId}/verify-factors`, added to the 93 contract
  here); mw-admin-api compliance actions / wallet unlock that used `core.command.v1` -> REST.
- Retire `core.command.v1` / command topics from `deploy/kafka/topics.txt`, `contracts/events/mw-orchestrator/core.command.v1*`,
  every `contracts.yml` and `OWNERS.yml`; mw-core-mock `messaging/CoreCommandListener` removed; `core.event.v1` stays.
- Docs: orchestrator, core-mock, mw-auth CLAUDE.md; spec 6.3 (already amended in 89) cross-checked.
- Split if over budget: 95a orchestrator client + flows; 95b mw-auth/admin-api producers + topic retirement.

**FRS IDs covered:** EXT-18, PLT-022/023 on core calls, PLT-030/033 (core connector resilience), WAL-13 as inquiry

**Blocked by:** 93

**Status:** open

- [ ] `SagaMachine` unit tests unchanged and green (state machine untouched); new unit tests for REST outcome -> `SagaEvent` mapping (2xx, 4xx core reject, 5xx, timeout -> UNKNOWN)
- [ ] `CoreRestFlowIT` (Testcontainers + core-mock container or WireMock-free stub in test): P2P and TOPUP complete; core timeout -> UNKNOWN -> WAL-13 -> COMPLETED with one posting
- [ ] Hold-expired IT: a saga waiting on a partner whose hold expires ends in the documented state
- [ ] No listener thread calls the core (test asserts calls run off `KafkaListener` threads)
- [ ] `./gradlew :mw-orchestrator:build :mw-core-mock:build :mw-auth:build :mw-admin-api:build :contractsLint :checkIsolation` green with Docker
- [ ] Fresh stack: `scripts/api-test.sh` folders 01–12, 16, 25, 26, 29 pass
