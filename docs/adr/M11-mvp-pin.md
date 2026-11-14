# M11: PIN: encrypted with the core's key from day one

Status: Accepted, 2026-11-03; superseded 2026-11-14 by D20 and D22, built by tickets 71–72 (2026-11-15): removed. M2 gate: the ciphertext may sit on the transaction row until a terminal state, then it is wiped.

## Context

A PIN in clear anywhere in the middleware would put it in PCI-like scope and in logs.

## Decision

Apps encrypt the PIN with the core's public key (RSA-OAEP, SHA-256); `mw-ussd` encrypts on receipt; the middleware forwards only ciphertext. `mw-core-mock` holds the private key (in OpenBao) and checks a bcrypt hash.

## Consequences

- No PIN, plain or encrypted, may appear in a log: `scripts/log-scan.sh` (originally `make log-scan`) checks every container log.
- Every terminal path clears the stored ciphertext (tested in the orchestrator).

## Superseded (ticket 72)

The channels encrypt the PIN under the mw-crypto `PIN_TRANSPORT` key and send it once to mw-auth
(`POST /internal/v1/txn-authorisations`), which publishes nothing but answers a signed assertion the
request carries (D22). Removed: `encryptedPin` from `mw.txn.request.v1` and the core commands, the
column `txn.transaction.encrypted_pin` and its wipe, mw-core-mock's PIN key, bcrypt hash and PIN
lockout, the core key pair in provisioning (`pin_keypair`) and its copies to mw-ussd and
mw-checkout. `scripts/log-scan.sh` keeps the PIN rules and adds the assertion. The paths below are
history.

## Where in the repo (until ticket 72)

- `services/mw-ussd/src/main/java/mfs/mw/ussd/util/PinCipher.java`
- `scripts/log-scan.sh`
- spec section 18.3
