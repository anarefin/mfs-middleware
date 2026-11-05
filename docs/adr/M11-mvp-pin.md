# M11: PIN: encrypted with the core's key from day one

Status: Accepted, 2026-11-03. M2 gate: the ciphertext may sit on the transaction row until a terminal state, then it is wiped.

## Context

A PIN in clear anywhere in the middleware would put it in PCI-like scope and in logs.

## Decision

Apps encrypt the PIN with the core's public key (RSA-OAEP, SHA-256); `mw-ussd` encrypts on receipt; the middleware forwards only ciphertext. `mw-core-mock` holds the private key (in OpenBao) and checks a bcrypt hash.

## Consequences

- No PIN, plain or encrypted, may appear in a log: `make log-scan` checks every container log.
- Every terminal path clears the stored ciphertext (tested in the orchestrator).

## Where in the repo

- `services/mw-ussd/src/main/java/mfs/mw/ussd/util/PinCipher.java`
- `scripts/log-scan.sh`
- spec section 18.3
