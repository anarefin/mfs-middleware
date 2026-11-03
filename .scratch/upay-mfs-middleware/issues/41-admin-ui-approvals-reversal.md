# 41: Admin UI approvals inbox + reversal / TSQ

**What to build:** Operators can request and approve manual actions in the console: an approvals inbox with a diff view, and buttons on the transaction detail page to request a reversal, manual TSQ or force commit/release. Spec WP25 (part).

**Blocked by:**

- 40: Admin UI shell + txn search / detail
- 37: Maker-checker engine + manual TSQ / reversal

**Status:** ready-for-agent

- [ ] Playwright: maker requests a reversal, checker (another user) approves it, and the txn shows REVERSED
- [ ] Playwright: a maker cannot see an approve button on their own request
