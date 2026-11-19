# D28: Partner routes as config, built from shipped Kamelets

Status: Accepted, 2026-11-18 (user decisions, ticket 77). Amends spec 12 (the adapter framework's
route templates) and spec 13 (reconciliation ingest moves to the adapters).
Amended 2026-11-19 by [D30](D30-hashicorp-vault.md): HashiCorp Vault replaces OpenBao (ticket 91); the
OpenBao names below describe the stack before it.

## Context

Camel was chosen (D1) so that Bits can onboard a biller, a telco or a bank of a known kind without
writing code. The adapter framework of spec 12 fixed a set of Java route templates instantiated by
`TemplatedRouteBuilder`: any shape the templates did not foresee meant Java code and a release.
Camel 4 can load YAML DSL routes at runtime and compose them from Kamelets (reusable, parameterised
route snippets), which lets a partner's whole exchange be a reviewed piece of config while the
risky parts (HTTP, SOAP, SFTP clients, auth, error handling) stay in shipped code. Config that can
call any URL with any component also needs guardrails.

## Decision

- **One route per partner.** A partner is a Camel YAML DSL route, `from: direct:partner-<id>`. Its
  input is the `PartnerCall` JSON and its output is `{outcome, partnerRef, code, message, reason}`.
  The route lives in the adapter that owns the partner family (`mw-adapter-telco`,
  `mw-adapter-billpay`, `mw-adapter-bank`); only these modules use Camel (D1 amendment).
- **Kamelet library**, shipped as code and reviewed like code: `mw-auth-*`, `mw-http-call`,
  `mw-soap-call`, `mw-sftp-*`, `mw-classify`, `mw-audit`. Raw `http`, `cxf` and `sftp` endpoints
  are allowed only inside these Kamelets, never in a partner route.
- **The four guardrails** on a partner route:
  1. maker-checker in mw-admin-api (spec 15.3): a route goes LIVE only after a second person
     approves it;
  2. a component allowlist checked on the parsed route model (not on the YAML text): only
     `direct`, the `mw-*` Kamelets and the allowed EIPs;
  3. a dry-run against mw-simulators before approval, with rollback to the last good version when
     the new one fails to load or to answer;
  4. the host allowlist carried in the approved record: a route may call only the hosts its
     approved record names.
- **Static on purpose:** the biller catalogue `mw.billers` and the OpenBao secrets are not part of
  the route config. A new catalogue entry or secret is config plus a billpay restart.
- **New code only** for a new protocol or a new auth scheme, as a new Kamelet.
- **No automatic redelivery of a non-idempotent partner call (PAY).** Redelivery applies only to
  INQUIRY and STATUS. A PAY with an unknown outcome is resolved by the saga through STATUS (spec
  7.4), never by sending it again.
- **Kamelet versioning:** a breaking change ships under a new name (`mw-<name>-v2`). The old Kamelet
  stays until no LIVE route uses it.
- **Secrets:** read through Camel's HashiCorp Vault properties function (`camel-hashicorp-vault`,
  `{{hashicorp:secret:…}}`) pointed at OpenBao, loaded at startup.
- **Shared error handling:** one `routeConfiguration` owned by the route loader. A partner route may
  not declare its own `onException` or `errorHandler`; the allowlist rejects it.
- **Route authoring:** Camel JBang (`camel run`) with the `mw-*` Kamelets against mw-simulators,
  Kaoto optional; the admin UI holds the YAML text.
- **Partner aliases, no silent fallback.** The adapters' `default-partner` setting (a request for an
  unknown partner id silently went to `ocs`, `billagg` or `c2wbank`) is replaced by an explicit
  `partner-aliases` map in each adapter's config: telco maps the MNO ids the channels send (`gp`,
  `banglalink`, ...) to `ocs`, billpay to `billagg`, bank to `c2wbank`, matching what the channels
  and the seeds send today. An id that is neither a partner nor an alias is rejected with
  `MW-PTR-UNKNOWN`.
- **Reconciliation files.** The adapters own SFTP ingest of partner and bank settlement files (the
  `mw-sftp-*` Kamelets) and publish the parsed lines as `mw.partner.recon.v1` (key `partnerId`)
  through the outbox. mw-recon (spec 13) does matching only: it consumes `mw.partner.recon.v1`, its
  own projection of `mw.txn.status` and the core ledger events, raises exceptions and reports. It
  never posts money and never moves a transaction state. Until mw-recon is built (ticket 88),
  `contracts/EXTERNAL.yml` lists it as the consumer of `mw.partner.recon.v1`.

### Onboarding a partner of a known kind

1. Author the route in YAML from the `mw-*` Kamelets and run it locally with Camel JBang against
   mw-simulators.
2. Add the request/response mapping and the partner's error-code map (spec 12.1 layer 2).
3. If the partner needs a catalogue entry or a secret, add it to `mw.billers` / OpenBao and restart
   the adapter (static on purpose).
4. Submit the route in the admin console (maker); the loader checks the allowlist and runs the
   dry-run against mw-simulators.
5. A checker approves it, with the hosts it may call; the route goes LIVE. A failed load rolls back
   to the last good version.

## Consequences

- Onboarding a partner of a known kind is a config change with maker-checker, not a release.
- The Java route templates and `TemplatedRouteBuilder` of spec 12.1 go away; the `PartnerAdapter`
  SPI stays for kinds a Kamelet cannot express.
- The allowlist, the dry-run and the rollback need their own tests in each adapter (spec 22.1).
- A partner id that relied on the silent fallback now fails loudly with `MW-PTR-UNKNOWN` unless it
  is listed in `partner-aliases`.
- mw-recon stays small: no SFTP client, no file parsers, no money movement.
- Rolled out by tickets 82 (engine and Kamelets in billpay), 83 (maker-checker, dry-run, editor), 84
  (telco and bank), 85 (SOAP), 86 (SFTP and `mw.partner.recon.v1`), 87 (auth schemes) and 88
  (mw-recon matching).

## Amendments

- 2026-11-18 (ticket 84): when an adapter reports the partner's current version REJECTED on
  `mw.partner.route.status.v1`, mw-admin-api republishes the newest version that was reported LIVE
  before it as the next version on `mw.config.changed.v1` (its full published value without
  `dryRun`, changedBy/approvedBy `system/rollback`, in the same transaction as the status update),
  so the latest record an adapter replays on restart is always a good one. A repeated or stale
  REJECTED does nothing; with no LIVE predecessor nothing is published (the adapter keeps its seed).
  This closes the restart gap of ticket 83 (a REJECTED latest record left the partner on its seed).
- 2026-11-18 (ticket 84): the partner aliases as built. Telco maps every operator id the channels
  send (`gp`, `robi`, `banglalink`, `teletalk`: mw-app-gateway, mw-ussd's operator list and MNO
  operators, the test console) to `ocs`; the bank and billpay channels send `c2wbank` and `billagg`
  themselves, so their maps are empty. An unknown id is refused `MW-PTR-UNKNOWN-PARTNER` (the reply
  contract's partner codes have two segments). A partner record of its own wins over an alias.
- 2026-11-18 (ticket 84): the old partner shape (`template`, `baseUrl`, `operations`) is gone in the
  three adapters, mw-admin-api (action `UPSERT`) and `mw.config.changed.v1`; a record without a
  `route` is refused. Telco and bank run the same engine and Kamelets as billpay (`diff -r` of the
  `partner/` trees and the Kamelet folders is empty after the package rename) and publish
  `mw.partner.route.status.v1` for their own family.
- 2026-11-18 (review of tickets 84-87), rollback: the republished version is the newest LIVE
  version's proven set (route, mappings, allowedHosts, auth, sensitiveFields, headers, error map, as
  its dry-run passed them together), never the live route paired with the rejected version's hosts or
  auth; the partner is enabled only when both the live and the current version are (a rollback never
  re-enables a partner the operator disabled), and the current timeouts stay (operator state, not part
  of the proof). The merged value passes the route rules again before it is published. A rejected
  version that kept that proven set is not rolled back: the same set would be rejected again.
- 2026-11-18 (review of 84), dry-run gate: ENABLE, DISABLE and SET_TIMEOUTS drop the `dryRun` only
  when the partner's current route is proven (a seed or reported LIVE). On a version still
  PENDING_DRY_RUN or REJECTED they keep its `dryRun`, so an unproven route never reaches the adapter
  as one that loads at once. (Refusing the actions instead would block an emergency DISABLE while a
  dry-run is pending.)
- 2026-11-18 (review of 87), amends "no automatic redelivery of a PAY": `mw-http-call` sends a
  request once more after a partner 401 to a refreshable credential (`mw-auth-oauth2-cc`), a PAY
  too. A 401 is the partner refusing the credential before it applied anything, so the resend cannot
  apply a payment twice; it happens at most once per call (a second 401 is classified), with a fresh
  token, and does not count against `inquiryRetries`. Any other failure of a PAY is still never
  resent.
- 2026-11-18 (review of 86), `mw-sftp-source` runs `stepwise=false`, not the ticket's
  `stepwise=true`: with `streamDownload=true` camel-sftp 4.22 (`SftpOperations
  .retrieveFileToStreamInBody`) returns the open `ChannelSftp.get` stream and, when stepwise, then sends
  `cd` back to the start directory on that same channel while the stream is unread; JSch serves one
  request at a time per channel (camel-ftp refuses the pair for FTP outright). Without stepwise the
  file is fetched by its relative path (the loader refuses `.` and `..` segments). The stream download
  (a file never fully in memory) is the requirement kept. The source polls every `delay` ms (100 or
  more) or on a Spring `cron` (six fields; the loader sets `scheduler=spring`), never both; `mw-recon`
  refuses a file over `mw.partners.recon.max-lines` (`MW_PARTNER_RECON_MAX_LINES`, default 1000000)
  as a whole, since every line of a file is written in one transaction.
- 2026-11-18 (review of 86), `mw-sftp-sink` builds CSV or fixed-width text with a JSONata mapping of
  the record (`mapping=`, a string result, request routes only; there the put is the partner call, and a dry-run
  candidate makes the text but puts no file),
  not `marshal: bindy`: bindy needs a Java model class per file layout, which a route cannot ship
  and the loader's allowlist cannot check; JSONata runs in the same sandbox as every other mapping.
- 2026-11-18 (review of 85), SOAP: `mw-soap-call` takes HTTP Basic through `mw-auth-basic` and mTLS
  through a named client context (`sslContextRef`, `PartnerTlsContexts`), as `mw-http-call`, so a SOAP
  partner on either is onboarded by config. Next to XSLT, `request=json` / `response=json` map the
  body by a fixed JSON/XML convention (`partner/mapping/JsonXmlMapping`: keys are elements in the
  call's `namespace`, `@name` attributes, arrays repeat; back the same way), so JSONata maps both
  sides as the ticket asks. The DOM builds the XML (a value is never markup) and the answer is parsed
  by the policy's secure parser; a JSONata expression never concatenates XML text.

## Where in the repo

- `services/mw-adapter-billpay/src/main/java/mfs/mw/adapter/billpay/partner` (the adapter framework,
  copied into telco and bank)
- `services/mw-adapter-billpay/src/main/resources/application.yml` (partner config)
- `contracts/EXTERNAL.yml`
- `docs/runbook.md` ("Onboard a biller")
- spec decision table (D28), sections 12 and 13
