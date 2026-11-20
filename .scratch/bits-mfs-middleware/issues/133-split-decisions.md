# 133 split (implementer, 2026-11-20) — read with 133-admin-ussd-editor-templates.md

Ticket 133 spans mw-admin-api (two endpoints and a new resource type), mw-notify (hot rule apply),
a contract change (`mw.config.changed.v1` configType), the admin UI (two screens), two Playwright specs
and API suite folder 67. That is far past the one-hour box of this run, so it is split. Copy this file onto
the branch that implements a slice. No money, security or foreign-contract choice was needed to split it.

- **133a** (this branch, UI only, no API change): the `/ussd` editor grows into the visual tree editor.
  `lib/ussd-menu.ts`: `screenPages` (mw-ussd `ScreenFormatter` rules: GSM-7 182 / UCS-2 70, PAGE or TRUNCATE,
  menu `more` / `back` lines, default `0. Next` / `00. Back`), `textCounter` (per-node bn/en counter), tree ops
  `addChild`, `moveNode` (re-parent), `moveOption` (reorder), `deleteNode` (also drops the references to it).
  The browser preview pages the screen and answers `0` (next page) / `00` (previous page, else previous node),
  with the MNO picked (limits and overflow of mw-ussd's dev config, short code shown). Vitest tests; Playwright
  `e2e/41-ussd-editor.spec.ts` written, not run live. Docs: mw-admin-ui `CLAUDE.md`, `docs/testing-guide.html`.
  FRS: ADM-06 and CH-USSD-06 partial (editor and browser preview; the server preview is 133b).
- **133b** (after 133a): mw-admin-api `POST /admin/v1/ussd/menus/preview` (MenuValidator, then the same paging
  as a pure function; contract in admin-api's own OpenAPI), short code → root menu known to mw-admin-api
  (mw-ussd's `ussd.short_code` is not readable by mw-admin-api: config list in `mw.content`, or a config change
  type mw-ussd applies; decide there), the UI preview pane calls it for the validator verdict. API folder 67 starts.
- **133c** (after 133b): `notify-rule` resource type (contract `mw.config.changed.v1` configType NOTIFY_RULE,
  owned by mw-admin-api), mw-notify `RuleEngine` hot apply, `NotifyRuleChangeIT`, `/templates` Rules tab,
  `e2e/51-notify-rules.spec.ts`. NOT-01.
- **133d** (after 133c): `POST /admin/v1/templates/{id}/test-send` (operator's own allow-listed MSISDN / email,
  `mw.notify.request.v1` priority INFO, OTP-class masking), "Test send" button, API folder 67 complete. ADM-07.

## 133b decisions (implementer, 2026-11-20)

- **Short code -> root menu:** a config list in mw-admin-api, `mw.content.ussd.<mno>` (`short-code`,
  `root-menu-id` and the screen limits `max-length-gsm`, `max-length-ucs2`, `overflow`), a copy of mw-ussd's
  `ussd.short_code` V6 rows and `mw.ussd.mno.*` limits kept aligned by hand. Chosen over a new config change
  type mw-ussd applies: simplest, no contract change, mw-ussd's schema is never read, and a short code changes
  with an MNO contract, not by an operator. The preview answer names `shortCode` and `rootMenuId`.
- **"Orphan node"** in the acceptance criterion is read as a dangling reference (an option / next pointing to
  a node that does not exist), which `MenuValidator` already refuses; an unreachable node is not a problem
  for mw-ussd's validator, and the copy must stay aligned with it, so no new rule was added.
- **Roles:** ops-maker and ops-checker (`AdminRole.CONTENT_PREVIEW`): the checker previews what they approve.
- **Walk limits on the server:** no flow, lookup or core call runs; `pin` and `regex` inputs accept any
  non-blank answer, so no operator-written pattern is evaluated in mw-admin-api (ReDoS); answers at most 50,
  each at most 64 characters; a PIN answer is echoed `****`. The console calls the API for the validator
  verdict ("Check on server"); its own browser walk stays for the interactive paging.
- **API folder 67** (`67-admin-ussd-templates`) started: the paged robi preview and the refused tree; 133c / 133d
  add their requests to it.
