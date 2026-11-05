# 41: Admin UI approvals inbox + reversal / TSQ

**What to build:** Operators can request and approve manual actions in the console: an approvals inbox with a diff view, and buttons on the transaction detail page to request a reversal, manual TSQ or force commit/release. Spec WP25 (part).

**Blocked by:**

- 40: Admin UI shell + txn search / detail
- 37: Maker-checker engine + manual TSQ / reversal

**Status:** done

- [x] Playwright: maker requests a reversal, checker (another user) approves it, and the txn shows REVERSED (`ui/mw-admin-ui/e2e/approvals.spec.ts`)
- [x] Playwright: a maker cannot see an approve button on their own request (also a user holding ops-maker and ops-checker; the ticket 52 wallet-unlock screen is covered in the same spec)
