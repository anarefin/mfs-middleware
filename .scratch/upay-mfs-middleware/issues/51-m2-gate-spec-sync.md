# 51: M2 gate spec sync

**What to build:** Update `spec/upay-mfs-middleware-spec.md` so it matches the M2 gate decisions (2026-11-04) and what tickets 07–23 built. This ticket changes the spec only. Every point below is a decision already taken, so record it as a decision and do not reopen it.

1. **§7.2 state table, new rows:**
   - CommitFailed / ReleaseFailed in COMMITTING / RELEASING go to MANUAL_REVIEW with `MW-CORE-<reason>` and a Sev-1 alert.
   - CreditRejected in COMMITTING for ADD_MONEY_BANK goes to MANUAL_REVIEW.
   - TransactionReversed arriving in MANUAL_REVIEW, for a transaction that came from REVERSING, goes to REVERSED.
   - A REVERSING timeout is resolved by a core QueryTransaction before MANUAL_REVIEW.
   - Add the ADD_MONEY_BANK path: VALIDATING → PARTNER_PENDING → COMMITTING (CreditWallet) → COMPLETED.
2. **Late conflicting partner results:**
   - A late callback or status answer that contradicts a terminal state keeps the terminal state.
   - It raises `txn.outcome.conflict` at ERROR with an audit entry for operations and reconciliation.
3. **§7.3:** repeats are answered from the live transaction row, and the idempotency table is not partitioned. The Valkey lock is kept (ticket 50).
4. **§7.4 inquiry schedule:**
   - The defaults as built: 30 s, 1 min, 2 min, 5 min, 15 min, 1 h, then every 6 h, with per-partner overrides.
   - A 24 h cut-off, standing in for the partner's settlement cut-off, then MANUAL_REVIEW.
   - "Not found" releases only once the original request can no longer arrive.
5. **Reversal:** it is core-only in the MVP, with no partner-side reversal; a reversed TOPUP keeps the airtime. Only the `SYSTEM` channel may request one; the admin path is ticket 37. Add a row to the "in the MVP vs deferred" table.
6. **§6.3 core topics:** HoldFunds, CommitHold and ReleaseHold go on `core.payment.*`; every other command goes on `core.wallet.*` (built in ticket 50).
7. **§9.1 roles:** CASH_IN is agent-initiated and CASH_OUT is customer-initiated, and the role map is deny-by-default. PIN lockout after 5 failures is owned by core. Locked wallets are unlocked by ops through the admin console with maker-checker (ticket 52).
8. **Bill fetch:** the biller list and fetch-bill go from the gateway to the bill-pay adapter over internal HTTP. Record this as the one read-only exception to "no service-to-service calls on the money path".
9. **Partner callbacks:** the adapter verifies the signature in the partner's own format, with the secret in OpenBao. APISIX does the ip allow-list, the body cap and the rate limit. Update the APISIX section to match.
10. **APISIX:** configured as JSON through the Admin API by an idempotent apply script, not ADC. The rate limit is 120 requests per 60 s per token subject, fails open when Valkey is down, and has an alert (ticket 50).
11. **§17 audit:**
    - Partner calls are audited on `mw.audit.event.v1` as `partner.call.completed`.
    - The writer role is INSERT-only.
    - The chain is verified by an `audit_reader` job (ticket 50).
    - Partitions follow `recorded_at`.
12. **§18.3 masking and PIN:**
    - PIN ciphertext may be held on the transaction row until a terminal state, then wiped.
    - Name masking covers customers, not staff: an operator's username stays in full in audit `actor.id`.
    - Party ids are masked by their type.
13. **Error codes:** add the MW codes introduced in tickets 07–23 to §5.3's lists. Take each module's `ErrorCodes` and `CLAUDE.md` as the source.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Every numbered point above is reflected in the spec, with a "Decision 2026-11-04" note where it overrides earlier text
- [ ] No spec text still says APISIX ADC, APISIX hmac-auth for callbacks, PIN "never stored" without the ciphertext exception, or agent-initiated CASH_OUT
- [ ] `make contracts-lint` still passes (no contract changes expected)
