# D23: mw-crypto is the only HSM client, with PKCS#11 and payShield providers

Status: Accepted, 2026-11-14 (spec revision 6). Production HSM vendor still open (spec open point 22).

## Context

PIN custody (D20), token and assertion signing (D21, D22), the signed audit anchor, tokenisation for the data warehouse, column encryption, XML signatures for NPSB and SOAP partners and PIN blocks for the ATM gateway all need keys that RFP SEC-04 puts in an HSM controlled by the client. Letting each service talk to the HSM would spread HSM credentials and vendor code across the repo. The production HSM is not chosen; Thales payShield 10K is the most common payment HSM at Bangladeshi banks and switches.

## Decision

- One service, `mw-crypto`, holds the HSM sessions; everyone else calls its internal API by `keyId` (mTLS only, D24) and never holds key material. Only key metadata is stored (schema `crypto`).
- A provider SPI with two providers: PKCS#11 (JDK SunPKCS11; SoftHSMv2 container in dev and test) for wallet PIN HMAC, JWS, HMAC, tokenisation, envelope encryption and XML-DSig; Thales payShield 10K host commands (`A0`, `CA`, `CC`, `DG`, `DC`, `EC`, `M0`, `M2`, `NC`) for ISO 9564 PIN translation and PVV, against a simulator in `mw-simulators` until the vendor is chosen.
- Key versions and rotation by `keyId`; key ceremony with two `security-admin` custodians and maker-checker in the admin console.

## Consequences

- OpenBao transit is not used for these keys; OpenBao keeps KV (including the PKCS#11 token PIN), PKI and database credentials.
- If `mw-crypto` is down, PIN checks, logins, assertions and signing fail closed (`MW-SYS-*`, retryable): it is on the critical path, single instance (D11), and gets its own alert.
- Two new dev containers (softhsm and the simulator inside `mw-simulators`) count against the laptop memory budget.
- A different production vendor means a new provider behind the same SPI, no caller change.

## Where in the repo

- spec section 18.8, 22.2 and decision D23
- tickets 61–65, 75
