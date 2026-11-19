# 110: mw-notify: push channel and push-server mock (FRS 8.1, EXT-04, FL-14)

**What to build:** A push channel in `mw-notify` next to SMS and email. Push goes to an app-server push API: FCM/APNs
style, device token or topic. It runs against a stateful push mock in `mw-simulators` that stores every message.
Today PUSH is recorded FAILED `MW-VAL-101`.

**Principles:**
- A push never blocks or replaces a transactional SMS: FL-14 is SMS always, push optional.
- Device tokens are not PII, but they are masked to their last 6 characters in logs.

**Scope:**
- `services/mw-notify/client/PushClient` (RestClient + Resilience4j: timeout, retry with backoff, breaker), config
  `mw.notify.push.*` (base URL, auth through an API key from Vault/OpenBao per ticket 91).
- Device token registry: `notify.push_device` (Flyway): walletId, deviceId, token, platform, updatedAt. It is fed by a
  new app API `PUT /app/v1/notifications/push-token` (mw-app-gateway → `mw.notify.request.v1`
  `RegisterPushToken`, or an internal REST call; pick the one that keeps contract ownership, see
  `contracts/OWNERS.yml`). Revoked on mw-auth device re-bind (consume the existing device event, or
  `auth.wallet-lock.v1` REVOKE_SESSIONS).
- Push templates (bn/en, title + body) in `notify.template` with channel `PUSH`, plus rules in `notify.rule` for
  completed and failed financial txns (FL-14).
- Delivery status: push mock acknowledgement → `SUBMITTED` / `DELIVERED` / `FAILED` on `mw.notify.status.v1`; the
  contract gets `PUSH` as a channel value if it is missing.
- `services/mw-simulators`: `PushController` (`POST /push/v1/messages`, `GET /push/v1/messages?token=`), stored
  messages, scenarios (SUCCESS, HTTP_5XX, TIMEOUT, invalid token) on the ticket 102 engine as party `push`.
- Test console: a push inbox panel (optional, reuse the SMSC inbox component).
- Docs: `services/mw-notify/CLAUDE.md`, `services/mw-simulators/CLAUDE.md` trigger table, README.

**FRS IDs covered:** EXT-04, NOT-01 (push channel), NOT-02 (push templates), NOT-06 (push retry/DLQ), FL-14

**Blocked by:** 102

**Status:** open

- [ ] Unit: `PushClient` retry/backoff; template rendering for push bn/en; token masking
- [ ] `PushDispatchIT` (Testcontainers + mock): a completed txn status → push message stored in the mock with the right text
- [ ] A push mock 5XX is retried, then FAILED; the SMS for the same txn is still sent
- [ ] A re-bind removes the old token; no push goes to it afterwards
- [ ] `./gradlew :mw-notify:build :mw-simulators:build :mw-app-gateway:build :contractsLint` green with Docker
- [ ] API suite folder `44-notify-push` passes; `10-notify`, `11-notify-flows` still pass
