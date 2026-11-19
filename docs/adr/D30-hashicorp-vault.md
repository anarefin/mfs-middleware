# D30: HashiCorp Vault instead of OpenBao

Status: Accepted, 2026-11-19 (user decision, FRS planning, ticket 89). Amends D9 (OpenBao was
chosen over Vault for the MVP), D24 (the PKI issuer) and spec 3 (the secrets / PKI row); built by
ticket 91.

## Context

D9 picked OpenBao (MPL 2.0), the open fork of HashiCorp Vault, so that every runtime component
stays under a permissive licence (OWN-03). The Upay FRS 1.4 names "HashiCorp Vault dev mode" for
secrets. OpenBao 2.x kept Vault's HTTP API for the engines the repo uses: KV v2 (service secrets,
partner credentials), PKI (the D24 service certificates) and transit-style key paths, and the clients
are Vault clients already (Spring Cloud Vault, Camel's `camel-hashicorp-vault` in the adapters, the
`provision` one-shot's HTTP calls).

## Decision

- **HashiCorp Vault replaces OpenBao**: one container named `vault`, single instance (D11), with
  the same mount paths (`secret/` KV v2, `pki/` and `pki_int/`, the auth methods `provision`
  enables), so service configuration changes only in its host name and no client code changes.
- **Image:** `hashicorp/vault:2.2.2`, the latest stable Vault release on 2026-11-19. Ticket 91 pins
  it by digest. Dev keeps a file-backed server initialised and unsealed by `provision` (the root
  token in the data volume, dev only, as with OpenBao), not `-dev` in-memory mode, so restarts keep
  the PKI and secrets.
- **Licence:** Vault is under the Business Source Licence 1.1. Bits runs it internally for its own
  middleware and does not offer it as a hosted or competing product, which the BSL permits; this is
  accepted as the OWN-03 exception for Vault. OpenBao stays the drop-in fallback should that ever
  change, because the mount paths and clients are the same.
- Names follow the product: knobs, compose service, docs and `scripts/creds.sh` say Vault after
  ticket 91; the Spring `spring.cloud.vault.*` properties already do.

## Consequences

- Matches FRS 1.4; ADM-02 ("stored in vault, never shown again") reads literally.
- The secret, PKI and key paths in mw-crypto, mw-auth and the adapters do not move; ticket 91 is a
  container, provision and naming change, tested by `scripts/smoke.sh` and the mTLS ITs of ticket 66.
- A BSL licence change by HashiCorp would mean switching back to OpenBao, an image change.
- Built by ticket 91 (2026-11-19): the compose service `vault`, `deploy/vault/`, the `provision` one-shot on the
  `vault` CLI, every Testcontainers IT on `hashicorp/vault:2.2.2`; `scripts/test/test_vault.sh` runs the dev
  unseal and `provision` against a real Vault and fails when OpenBao is named outside the decision records.

## Where in the repo

- `deploy/docker-compose.yml` (the `vault` service and the `provision` one-shot), `deploy/vault/`, `deploy/provision/`
- `scripts/creds.sh`
- spec decision table (D30), section 3
