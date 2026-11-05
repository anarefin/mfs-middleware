# 42: Admin UI partner config, USSD menu editor, template editor

**What to build:** Operators can manage partners, USSD menus and SMS templates from the console. The screens are: partner enable/disable and timeouts; a USSD menu tree editor with a preview walk; and a template editor with Bangla input and live preview. All changes go through maker-checker. Spec WP25 (rest).

**Blocked by:**

- 41: Admin UI approvals inbox + reversal / TSQ
- 38: Partner config via maker-checker
- 39: USSD menu + SMS template management

**Status:** done

- [x] Playwright test per screen covering edit → approve → effect visible
- [x] Template preview renders bn text with sample params, masked
