# 107: App non-financial APIs: profile, limits, saved billers, favourites, notifications inbox, statement (FRS CH-APP-08)

**What to build:** Today the app has `/app/v1/balance` (D26), wallet history `GET /app/v1/transactions` (D25) and the
biller list. Missing: profile, limits, saved billers, favourites, notifications inbox and a date-range statement.

**Principles:**
- Money data comes from the core (system of record): limits via WAL-05 remaining amounts, statement via WAL-12.
- User preferences (saved billers, favourites) are middleware data in mw-app-gateway's own schema, never in the core.
- The notifications inbox reads mw-notify through an internal API; mw-app-gateway never reads `notify` tables.

**Scope:**
- `contracts/api/mw-app-gateway/app.v1.openapi.yaml`: `GET /app/v1/profile`, `GET /app/v1/limits`,
  `GET|POST|DELETE /app/v1/saved-billers`, `GET|POST|DELETE /app/v1/favourites`, `GET /app/v1/notifications`
  (paged, mark read), `GET /app/v1/statement?from&to` (CSV/JSON, max 90 days).
- mw-app-gateway schema (Flyway): `saved_biller` (walletId, billerCode, accountNo encrypted via mw-crypto envelope,
  nickname), `favourite` (walletId, counterparty MSISDN encrypted, label); masked in logs (`@Sensitive`).
- Profile: WAL-01 (name masked per `MaskingPolicy` except the owner's own view rules, KYC level, status) + language.
- mw-notify: internal `GET /internal/v1/notifications?msisdn&from&to` (inbox view of sent TXN/INFO/PROMO messages; OTP
  bodies never returned) with internal JWT like other internal APIs; contract
  `contracts/api/mw-notify/notify-internal.v1.openapi.yaml` (new, owner mw-notify, add to `contracts/OWNERS.yml`).
- Statement: core `GET /statements` (ticket 94) through the existing history path of D25.
- Test console app pages for each view.

**FRS IDs covered:** CH-APP-08, UC-03 (app part), WAL-12 (consumer side)

**Blocked by:** 94, 105

**Status:** open

- [ ] `SavedBillerRepositoryIT`, `FavouriteRepositoryIT`: CRUD scoped to the token's wallet; account numbers stored encrypted
- [ ] `NonFinancialControllerTest` (`@WebMvcTest`): each endpoint, other wallet's data never returned (403/404)
- [ ] mw-notify `InboxControllerIT`: OTP messages excluded; paging; masking
- [ ] `StatementIT`: date range > 90 days refused; entries match the core ledger
- [ ] `./gradlew :mw-app-gateway:build :mw-notify:build :contractsLint` green with Docker
- [ ] With the stack up: requests added to folders `28-txn-history` and `29-balance` (profile, limits, statement,
      inbox, saved billers, favourites); both folders green
