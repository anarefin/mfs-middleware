# 80: Spring Kafka in the channels (mw-app-gateway, mw-checkout, mw-ussd)

**What to build:** Port the three channels from camel-kafka to Spring Kafka (template from ticket 78) and remove Camel from them.

**Their consumers today:**
- mw-app-gateway / mw-checkout: `StatusReplyRoute` (plain) and `StatusProjectionRoute` (with retry).
- mw-ussd: `StatusReplyRoute` and `ConfigChangedRoute` (with retry).

**What to do:**
- **`StatusReplyRoute` is different on purpose:** a per-instance group, `auto-offset-reset=latest`, auto-commit, no retry and no DLQ. It wakes
  the waiting HTTP/USSD request when the async reply arrives. It becomes a `@KafkaListener` on its **own container factory**
  (`statusReplyContainerFactory`) with:
  - `groupId = ${spring.application.name}-${random instance id}`;
  - `latest`;
  - `enable-auto-commit=true`;
  - no retry topic;
  - an error handler that logs and skips.
  Look at the current `StatusReplyRoute.java:26-30` options and keep each one.
- **The waiting side does not change:** `TransactionService` / `DeviceOtpService`, `CheckoutPayService` and `KafkaTransactionFlow` still publish through
  `EventPublisher` and wait for the reply asynchronously. **Never block the listener thread.**
- **`StatusProjectionRoute` and `ConfigChangedRoute`:** retry listeners as in the template.
- **The rest, as in 79:** `EventPublisher`, `OutboxRelay` scheduler, `application.yml`, delete `route/` and the Camel deps, `CLAUDE.md`.

**Blocked by:** 78

**Status:** open

- [x] `grep -rl org.apache.camel services/{mw-app-gateway,mw-checkout,mw-ussd}` is empty
- [x] `./gradlew :mw-app-gateway:build :mw-checkout:build :mw-ussd:build` is green with Docker
- [x] There is a test showing that two instances of the same channel each get the status reply (per-instance group), and that a reply sent before startup is not replayed (`latest`)
- [ ] With the stack up: `scripts/api-test.sh` P2P, checkout and USSD folders, plus `scripts/tester-e2e.sh @app @ussd @checkout`
