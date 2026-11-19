# 93: mw-core-mock REST API (FRS 7.2 WAL-01, WAL-03..13, ADR D31)

**What to build:** A REST interface on `mw-core-mock` matching FRS section 7.2, beside the Kafka command handlers that
ticket 95 retires. Money operations are idempotent by `txnId`, authorised by the mw-auth assertion (D22), and keep
publishing the events other services project from.

**Principles:**
- Same business code for REST and Kafka during the switch: `service/CoreCommandService`, `HoldOperations`,
  `PostingOperations`, `StatusOperations`, `AssertionCheck` are reused, not duplicated.
- The core never sees PIN material: WAL-02 `/pin/verify` is not built (documented deviation, D31).

**Scope:**
- Contract `contracts/api/mw-core-mock/core-wallet.v1.openapi.yaml` (owner mw-core-mock, `contracts/OWNERS.yml`), base `/core/v1`:
  WAL-01 `GET /accounts/{msisdn}`, WAL-03 `GET /balance/{walletId}`, WAL-04 `POST /charges/calculate`, WAL-05 `POST /limits/check`,
  WAL-06 `POST /holds`, WAL-07 `POST /holds/{id}/commit`, WAL-08 `POST /holds/{id}/release`, WAL-09 `POST /transfers`,
  WAL-10 `POST /credits`, WAL-11 `POST /reversals` (full; `amount` optional for partial, used by 116), WAL-12 `GET /statements`
  (stub until 94 adds the query), WAL-13 `GET /transactions/{txnId}`.
- Header `X-Txn-Assertion` (compact JWS) required on hold / transfer / credit when the flow needs it; checked by `AssertionCheck`
  (hash, `aud`, `exp`, `jti` unused) -> `MW-AUTH-046`. Service JWT / mTLS as other internal APIs.
- Idempotency: `txnId` + operation key; a repeat returns the stored response (409 on a different body).
- Rejections in the core reason set (`INSUFFICIENT_BALANCE`, `LIMIT_EXCEEDED`, ...) as `MW-CORE-<reason>` envelopes.
- Events: each REST money operation still writes the existing `core.*.event.v1` messages through the outbox
  (wallet state, ledger posted), so mw-auth's projection and ticket 126's ledger view keep working.
- Controllers in `controller/CoreWalletController` (mapping and `@Valid` only); APISIX test-only route `/test/core-mock` unchanged.
- `services/mw-core-mock/CLAUDE.md`, `contracts.yml`.

**FRS IDs covered:** WAL-01, WAL-03..WAL-13 (WAL-12 completed in 94), 7.3 idempotent rule, EXT-18

**Blocked by:** 92

**Status:** open

- [ ] `@WebMvcTest` per endpoint first (validation, envelope, assertion missing -> 401/`MW-AUTH-046`)
- [ ] `CoreWalletRestIT` (Testcontainers): hold -> commit moves money with zero-sum entries; hold -> release restores available; repeat with same txnId = same response, no second posting; reversal twice -> `ALREADY_REVERSED`
- [ ] Event IT: a REST commit emits the same ledger/wallet events as the Kafka command path
- [ ] Contract test: responses validate against `core-wallet.v1.openapi.yaml`
- [ ] `./gradlew :mw-core-mock:build :contractsLint` green with Docker
