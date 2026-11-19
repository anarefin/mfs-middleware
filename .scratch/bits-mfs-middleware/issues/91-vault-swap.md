# 91: Replace OpenBao with HashiCorp Vault (FRS 1.4, ADR D30)

**What to build:** Swap the `openbao` container for a pinned HashiCorp Vault image. The KV v2, PKI and auth paths keep their
names, so services change only host names and config keys; the dev unseal and provisioning keep working on a clean host.

**Scope:**
- `deploy/openbao/` -> `deploy/vault/` (`config.hcl`, `dev-unseal.sh` using the `vault` CLI); `deploy/docker-compose.yml` service
  `vault`, healthcheck, volume, `MW_PORT_VAULT` (8200); `deploy/dev.env.defaults`.
- `deploy/provision/` (`provision.sh`, `lib.sh`, `services.sh`, `Dockerfile`): Vault CLI/API; policies, AppRole/token per
  service, PKI mounts for service mTLS (ticket 66, ADR D24), the secret paths `secret/mw-adapter-*/...`.
- Services: Spring Cloud Vault / config binding host and property names in every `application.yml` that reads secrets;
  mw-crypto and softhsm paths (`deploy/softhsm/README.md`); adapter `CredentialResolver` defaults.
- Scripts and docs: `scripts/creds.sh` (root token from the Vault volume), `scripts/smoke.sh`, `deploy/reset.sh`, `README.md`,
  `docs/runbook.md`, `docs/concepts/` (OpenBao page -> Vault), `docs/testing-guide.html`, root `CLAUDE.md`, `deploy/RUNNING.html`.
- Leave ADR D9/D24 text, mark them "amended by D30".

**FRS IDs covered:** 1.4 (secrets: HashiCorp Vault), ADM-02 prerequisite (credentials stored in vault)

**Blocked by:** 89

**Status:** open

- [ ] A repo grep test fails while `openbao`/`bao ` appears outside ADRs/history, then passes
- [ ] Provision shell test (`scripts/test/`) runs `provision.sh` against a Vault dev container: policies, PKI role and every service secret path exist
- [ ] `./gradlew build` green with Docker for every module whose config changed; `./gradlew :composeCheck :scriptTests` green
- [ ] Fresh stack: `scripts/smoke.sh`, `scripts/api-test.sh "08, 24, 26, 27"` (bank secret, auth, assertion, payShield) and `scripts/log-scan.sh` pass
