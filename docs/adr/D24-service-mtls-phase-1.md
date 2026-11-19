# D24: Service mTLS, phase 1: mw-crypto and the mw-auth internal API

Status: Accepted, 2026-11-14 (spec revision 6).
Amended 2026-11-19 by [D30](D30-hashicorp-vault.md): HashiCorp Vault replaces OpenBao (ticket 91); the
OpenBao names below describe the stack before it.

## Context

Spec 18.2 plans mTLS with Vault-issued certificates of at most 72 h plus a client-credentials JWT for service-to-service calls; only the JWT part is built, and OpenBao's PKI engine is enabled by `deploy/vault/dev-unseal.sh` but unused. Turning on mTLS for all services, Kafka and PostgreSQL at once is a large change across the whole stack and the laptop footprint.

## Decision

- OpenBao PKI issues service certificates (an intermediate CA, one role per service, 72 h, renewed at two thirds of their life).
- mTLS is mandatory on `mw-crypto` (it fronts the HSM) and on the internal API of `mw-auth` (PIN and assertions); callers present their certificate and their client-credentials JWT.
- Every other internal call keeps the JWT alone until a follow-up ticket.
- Each service exposes its certificate expiry as a metric; an alert rule fires at a third of the life left (ENV-06).

## Consequences

- The services that call `mw-crypto` or `mw-auth` (`mw-auth`, `mw-app-gateway`, `mw-ussd`, `mw-checkout`, `mw-audit-dw`, later the adapters) get a certificate and a renewal job.
- Full mTLS (all services, Kafka SASL_SSL, PostgreSQL `verify-full`) stays an open follow-up.

## Where in the repo

- spec sections 18.2, 18.4 and decision D24
- `deploy/vault`, `deploy/provision/services.sh`
- ticket 66
