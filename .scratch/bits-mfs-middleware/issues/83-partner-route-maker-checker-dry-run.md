# 83: Partner routes through maker-checker, a dry-run against the simulator, and the admin UI editor

**What to build:** The operator path for D26. A new biller's route is submitted in mw-admin-api, approved by a checker, and
dry-run by the adapter against mw-simulators. It goes live only if the dry-run passes, and is rolled back automatically if it fails.

**mw-admin-api:**
- Flyway `V<n>__partner_route.sql` in schema `partner`: columns `route_yaml`, `allowed_hosts`, `family`, `dry_run`, `route_status` (`PENDING_DRY_RUN`, `LIVE`, `REJECTED`) and the version.
- New change action `UPSERT_ROUTE` in `PartnerChangeHandler`. It carries `family`, `allowedHosts`, `timeoutMs`, `errorMap`, `ambiguousCodes`, `sensitiveFields`, `dryRun`, `route`.
- **Validation on submit:** a copy of the adapter loader's validator, kept identical under D14. It runs the Camel YAML DSL JSON Schema check first, then the model allowlist
  (82). Refuse the request on the maker's side with `MW-VAL-*` details that show the line and construct.
- **Kamelet usage:** the console lists the routes that use each Kamelet and version (from the adapters' status/usage report, 82). Ops can see who is still on
  `mw-http-call` before a `-v2` replaces it.
- **Approval:** writes `mw.config.changed.v1` (key `partner/<id>`) with the new fields through the outbox.
- **`family`:** each adapter only applies records of its own family. This fixes today's `security.partner_config_refused` noise in telco and bank for billpay partners.
- **Status:** consumes the new `mw.partner.route.status.v1` and updates `route_status`, shown to the ops console.

**Contracts** (each owner changes only its own file):
- `contracts/events/mw-admin-api/mw.config.changed.v1` PARTNER payload: add `route`, `allowedHosts`, `family`, `dryRun` (additive). Update the samples.
- New `contracts/events/mw-adapter-billpay/mw.partner.route.status.v1.schema.json`: `{partnerId, version, status, problems[]}`. Producer: the adapters; consumer: mw-admin-api.
- Admin API OpenAPI and the derived change-request schema for `UPSERT_ROUTE`.
- Update `contracts/OWNERS.yml` and each module's `contracts.yml`.

**The adapter (billpay, from ticket 82):**
- **Candidate route:** when a new version arrives, start it as a **candidate** route `direct:partner-<id>-candidate`. The host allowlist is
  the record's, plus the simulator host in dev/test.
- **Dry-run:** run `dryRun.operation` with `dryRun.sample` and check the output schema.
- **Pass:** promote the candidate (atomic swap) and publish `LIVE`.
- **Fail:** remove the candidate, keep the old route, and publish `REJECTED` with the problems.
- **Where the dry-run goes:** configured per environment, `mw.partners.dry-run.base-url`.
  - Production points at a partner sandbox URL from the record (`dryRun.baseUrl`, which must also be allowlisted).
  - Dev points at mw-simulators.

**mw-simulators:** a generic programmable REST stub at `/sim/partners/{partnerId}/**`. It returns a canned response per partner and
operation, set through its existing admin/test API. Use it for dry-runs and the tester console.

**mw-admin-ui:** on the partner page, a route YAML editor: a textarea with monospace (user decision 2026-11-18: no embedded visual editor). Add the
allowed-hosts, the dry-run sample and the error map. It shows the validation problems, the approval state and the `route_status`.
Add a Playwright spec under `@partners`: maker submits, then checker approves, then LIVE.

**Docs:** `docs/runbook.md` gets an "Onboard a biller" section:
1. Write the route from a Kamelet example (`deploy/partner-routes/examples/`) and test it locally with **Camel JBang**:
   `camel run newco.yaml --local-kamelet-dir=services/mw-adapter-billpay/src/main/resources/kamelets` against mw-simulators (check the exact flags in the current docs).
   **Kaoto** (the visual Camel YAML editor) can be used offline to draw and edit it. Paste the YAML into the console.
2. Submit `UPSERT_ROUTE`.
3. Have a checker approve it.
4. Watch it reach LIVE.
5. Add the `mw.billers.<CODE>` catalogue entry and the OpenBao secret, then restart mw-adapter-billpay. These two stay static by decision.
6. If the biller calls back asynchronously, add an APISIX route file and run `apply.sh`.

Also update `docs/testing-guide.html` and `docs/demo.md` with the walkthrough.

**Check each Camel and Kamelet feature named here against the current Camel 4.x docs (Context7) before use.**

**Blocked by:** 82

**Status:** open

- [ ] An end-to-end IT (admin-api plus billpay, Testcontainers): submit, approve, dry-run, LIVE, then a bill pay succeeds without a restart
- [ ] A route whose dry-run fails ends `REJECTED`; the previous route still serves traffic (an IT proves a call during and after it)
- [ ] A forbidden construct is refused at submit (`MW-VAL-*`), before any approval; a YAML DSL schema error is reported with its line
- [ ] The runbook's Camel JBang loop works as written against the dev stack's mw-simulators (done by hand once, output in the PR)
- [ ] `./gradlew :mw-admin-api:build :mw-adapter-billpay:build :mw-simulators:build :contractsLint :scriptTests` are green; `pnpm test` and `scripts/ui-e2e.sh @partners` pass on the running stack
