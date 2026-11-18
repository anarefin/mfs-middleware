# D26: Partner routes as config, built from shipped Kamelets

Status: Accepted, 2026-11-18 (user decisions, ticket 77). Amends spec 12 (the adapter framework's
route templates) and spec 13 (reconciliation ingest moves to the adapters).

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

## Where in the repo

- `services/mw-adapter-billpay/src/main/java/mfs/mw/adapter/billpay/partner` (the adapter framework,
  copied into telco and bank)
- `services/mw-adapter-billpay/src/main/resources/application.yml` (partner config)
- `contracts/EXTERNAL.yml`
- `docs/runbook.md` ("Onboard a biller")
- spec decision table (D26), sections 12 and 13
