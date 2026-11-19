# 133: admin console: visual USSD menu editor with preview, notification rule table and test send (FRS ADM-06, ADM-07)

**What to build:** Today the console publishes the USSD menu as raw JSON per MNO (`ContentController`,
`UssdMenuChangeHandler`, `MenuValidator`, ticket 39; `e2e/40-ussd.spec.ts`). Notification templates can be edited,
but the rule table cannot. This ticket replaces the JSON editing with a visual tree editor and preview simulator, and
adds rule-table editing plus a "test send" for templates.

**Principles:**
- The menu model is unchanged: the same versioned `ussd.menu` JSON with node types menu, input, confirm, action, end and
  DYNAMIC, and bn/en text. The editor is only a new view of it, and `MenuValidator` stays the single check.
- A tree per short code and MNO (FRS ADM-06; ticket 109 adds short code → root menu). This resolves the earlier review
  note "one USSD menu tree with per-MNO go-live".
- A test send goes only to the requesting operator's own test MSISDN or email, which is allow-listed in config. It is
  OTP-class masked in the log and never goes to a customer.

**Scope:**
- **mw-admin-api**
  - `POST /admin/v1/ussd/menus/preview`: runs a draft menu tree through `MenuValidator`, then renders screen by
    screen for a scripted input sequence. Pagination at 182 GSM-7 / 70 UCS-2 characters, "0 Next" / "00 Back". Pure
    function, no session.
  - New `resourceType` `notify-rule`, with a handler: event type + outcome + customer type → channels and template,
    published on `mw.config.changed.v1`. mw-notify picks it up as it does template changes.
  - `POST /admin/v1/templates/{id}/test-send`: placeholders filled from a sample, sent through `mw.notify.request.v1`
    with priority INFO.
- **mw-notify:** applies `notify-rule` config changes to its `RuleEngine` without a restart.
- **mw-admin-ui**
  - `/ussd`: tree editor for adding, moving and deleting nodes, editing bn/en text side by side, and a per-node
    character counter.
  - Preview simulator pane: a handset mock that walks the draft.
  - Select by short code and MNO.
  - `/templates`: "Rules" tab (table editor) and a "Test send" button.
- **Roles:** `ops-maker` / `ops-checker`.
- **Docs:** `docs/testing-guide.html`, the mw-admin-ui `CLAUDE.md`.

**FRS IDs covered:** ADM-06, ADM-07, CH-USSD-04, CH-USSD-06 (preview), NOT-01 (rule editing)

**Blocked by:** 109, 111

**Status:** open

- [ ] Unit tests first: preview of a draft tree pages a long Bangla menu at the UCS-2 limit and shows "00 Back"; an invalid tree (orphan node, PIN stored) is refused with `MenuValidator`'s codes
- [ ] `NotifyRuleChangeIT`: an approved `notify-rule` change reaches mw-notify through `mw.config.changed.v1` and the next matching event uses the new channel set
- [ ] Test send to a non-allow-listed MSISDN is refused; the SMSC simulator inbox shows the allowed one; the notification log masks it
- [ ] Vitest tests for the tree editor (add, move, delete, bn/en counter); `pnpm test` green
- [ ] Playwright `e2e/41-ussd-editor.spec.ts` (`@ussd`) and `e2e/51-notify-rules.spec.ts` (`@templates`): edit, preview, propose, approve as a second persona, then a USSD walk over the test console shows the new text
- [ ] `./gradlew :mw-admin-api:build :mw-notify:build :contractsLint` green with Docker; `scripts/ui-e2e.sh "@ussd|@templates"` green
- [ ] API suite folder `67-admin-ussd-templates` passes (`scripts/api-test.sh 67`)
