# 28: USSD menus for all MVP flows

**What to build:** USSD users can reach every MVP flow that suits the channel. Menus are added for customer CASH_OUT (to agent), TOPUP (own and other number), and BILL_PAY with a DYNAMIC biller list and bill fetch. Spec WP18 (rest).

**Blocked by:**

- 26: USSD engine over HTTP transport
- 11: Agent CASH_IN / CASH_OUT end to end
- 17: TOPUP end to end (hold → partner → commit / release)
- 21: BILL_PAY sync path

**Status:** ready-for-agent

- [ ] A menu-walk test for every new path, in bn and en
- [ ] e2e `@ussd`: top-up and bill pay complete over USSD
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR
