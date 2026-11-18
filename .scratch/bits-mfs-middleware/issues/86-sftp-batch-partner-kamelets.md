# 86: SFTP batch partners via the `mw-sftp-source` / `mw-sftp-sink` Kamelets (camel-sftp)

**What to build:** File-based exchanges with billers and banks, configured per partner: daily reconciliation and settlement files, and
bulk bill lists. They use Kamelets that wrap `camel-sftp` (Camel 4 `sftp` component) in all three adapters.

- **`mw-sftp-sink`:** puts a file built by the route (CSV/fixed-width through `marshal` with `bindy` or JSONata to text) on the partner
  host, under `directory`, `fileName` (expression), with `knownHostsUri` (strict host key checking on) and the private key or password by `secretRef`.
- **`mw-sftp-source`:** a partner route of kind `batch`, `from: kamelet:mw-sftp-source?...`.
  - Polls with `delay`/`cron`, `move=.done`, `moveFailed=.error`, `readLock=changed`, and an idempotent repository (JDBC, in the adapter's own schema) so a file is processed once.
  - The parsed lines are published as a new event `mw.partner.recon.v1` through the outbox, for a later reconciliation consumer.
- **SFTP consumer options** (Camel best practice; check each against the current Camel 4.x docs, Context7):
  - `idempotent=true` with `idempotentKey=${file:name}-${file:size}-${file:modified}` on a JDBC idempotent repository;
  - `readLock=changed`;
  - `maxMessagesPerPoll` bounded;
  - `stepwise=true`;
  - `move`/`moveFailed`, and never `delete=true`, so a file is kept until the outbox write commits;
  - strict host key checking through `knownHostsUri` (`useUserKnownHostsFile=false`);
  - `disconnect=true` for infrequent polls;
  - `streamDownload` with `split().streaming()` for large files, so a file is never fully in memory.
- **Kamelet rules from 82:** the `source`/`sink` type labels, documented properties, the private key or password by secret ref through the Camel
  vault function, and lint (`sftp` only inside these Kamelets). Tests are AdviceWith/MockEndpoint unit tests plus the Testcontainers IT.
- **The route loader:**
  - Accepts a second route shape, `from: kamelet:mw-sftp-source` (batch), next to `direct:partner-<id>` (request/reply).
  - Host allowlist and allowlist rules as in 82.
  - Batch routes are switched on and off with the partner's `enabled` flag.
- **Contract:** `contracts/events/<adapter>/mw.partner.recon.v1.schema.json`, with a producer and consumer in `contracts.yml`. If there is no consumer yet,
  add it to `contracts/EXTERNAL.yml` for now, and **ask the user** who consumes it (the orchestrator or a new recon service).
- **Compose:** an SFTP server (`atmoz/sftp` or similar, pinned) in the `tester`/dev profile only, with a memory limit. Check `./gradlew :composeCheck` against the 8 GB budget.
- **Example:** `deploy/partner-routes/examples/sftp-recon.yaml`. Add the SFTP case to the runbook.

**Blocked by:** 84

**Status:** done (1ef30ff6)

- [ ] `SftpReconRouteIT` (an SFTP Testcontainer): a file dropped on the partner host is read once, its records publish `mw.partner.recon.v1`, and the file moves to `.done`; a bad file moves to `.error`
- [ ] Strict host key checking is on; an unknown host key is refused
- [ ] `./gradlew :contractsLint :composeCheck` and the three adapters build green
