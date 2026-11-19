# 111: mw-notify: rule dimensions, campaigns, log search, opt-out (FRS NOT-01, 07, 08, 09)

**What to build:** Finish the notification FRS rows that the gap sweep found missing:
- the rule table gains a customer-type dimension and system events;
- scheduled and bulk campaigns to a segment, with a per-second throttle;
- a searchable notification log API;
- promotional opt-out.

**Principles:**
- OTP and transactional messages can never be opted out of (NOT-09), and a campaign never delays them: campaigns
  use the PROMO priority bind (NOT-05 kept).
- The log API returns masked data only. The OTP body is always masked (NOT-08).

**Scope:**
- `notify.rule` (Flyway): add `customer_type` (CUSTOMER, AGENT, MERCHANT, DISTRIBUTOR, DSO, ORG, `*`). `RuleEngine`
  matches on event type + outcome + customer type. Customer type comes from the txn status payload
  (`payer.type`/role; add it to `mw.txn.status.v1` through mw-orchestrator only if it is missing, as a separate
  contract change).
- System events: consume `auth.wallet-lock.v1` (lock or unlock notice) and the MANUAL_REVIEW alert of ticket 96
  (ops email). List them in `contracts.yml`.
- Campaigns: `notify.campaign` (id, template, segment query, schedule at, throttle per second, status DRAFT /
  APPROVED / RUNNING / DONE / CANCELLED) and `notify.campaign_recipient`.
  - A segment is a CSV of MSISDNs uploaded through admin-api, or a filter on customer type.
  - Approval goes through mw-admin-api maker-checker, resource `notify-campaign`, `mw.config.changed.v1`.
  - A `scheduler/CampaignRunner` (ShedLock) starts it.
- Opt-out: `notify.opt_out` (MSISDN hash, since, channel). It is set via `POST /app/v1/notifications/opt-out` and by an
  SMS keyword `STOP` from the SMSC deliver_sm. It is honoured for PROMO only.
- Log search: `GET /internal/v1/notifications?msisdn=&txnId=&channel=&status=&from=&to=` (internal JWT), paged, masked;
  for the admin view in ticket 134.
- Docs: `services/mw-notify/CLAUDE.md`, admin-api resource list, README knobs.

**FRS IDs covered:** NOT-01, NOT-07, NOT-08, NOT-09; part of ADM-07 (API side)

**Blocked by:** 110

**Status:** open

- [ ] Unit: rule matching by customer type; opt-out applies to PROMO only; OTP body masked in the log DTO
- [ ] `CampaignRunnerIT`: an approved campaign to 500 recipients runs at the configured throttle (rate asserted within tolerance), and an OTP sent mid-campaign is submitted first
- [ ] An opted-out MSISDN gets no PROMO but still gets the TXN SMS
- [ ] `NotificationLogControllerTest` (`@WebMvcTest`) filters and masking; a repository IT for the query
- [ ] `./gradlew :mw-notify:build :mw-admin-api:build :mw-app-gateway:build :contractsLint` green with Docker
- [ ] API suite folder `45-notify-campaign` passes
