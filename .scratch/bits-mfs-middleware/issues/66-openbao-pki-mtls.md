# 66: OpenBao PKI and mTLS on mw-crypto (phase 1)

**What to build:** Service certificates from OpenBao PKI and mandatory mTLS on `mw-crypto` (spec 18.2, 18.4, D24). The `mw-auth` internal API joins in ticket 67.

- **OpenBao** (`deploy/openbao/dev-unseal.sh`, `deploy/provision/`):
  - a root CA (dev only: generated in OpenBao; production: an offline root signs the intermediate, open point 24) and an intermediate at `pki_int/`;
  - a role per service (`mw-crypto`, `mw-auth`, and each caller: `mw-app-gateway`, `mw-ussd`, `mw-checkout`, `mw-audit-dw`, `mw-admin-api`), CN = service name, SAN = compose DNS name, max TTL 72 h;
  - a policy per service allowing `pki_int/issue/<own role>` only. Idempotent on every `up`.
- **Services:** a small copied `config/ServiceCertificateManager` (from the template once proven here; D14 copy rule) issues at start and renews at 2/3 of the TTL into an in-memory `KeyStore` (no key on disk), and hot-reloads the SSL bundle (Spring Boot SSL bundles with reload).
  - mw-crypto listens with `client-auth: need` and trusts the intermediate only, then checks the JWT as well (azp must match the cert CN).
  - Callers use an mTLS `RestClient`.
- **Metric and alert:** `tls.certificate.expiry.seconds{service}`; the alert fires below 1/3 of the life (rule in the alert rules file next to the existing ones).
- **Template:** add the manager to `templates/mw-service-template/` so new services get it.
- **Tests:**
  - IT with an OpenBao Testcontainer: issue, renew (short TTL), and a rejected call without a client cert or with a cert of a role not on the allow-list;
  - an existing `mw-audit-dw` client test proves the caller side.

**Blocked by:** 62

**Status:** open

- [ ] `./gradlew :mw-crypto:build :mw-audit-dw:build` green with Docker
- [ ] On the running stack, `curl` to mw-crypto without a client certificate fails the TLS handshake; with the service cert and JWT it answers
- [ ] `scripts/smoke.sh` checks the PKI role list and mw-crypto mTLS health
