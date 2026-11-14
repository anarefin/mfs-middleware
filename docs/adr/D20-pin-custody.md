# D20: The middleware owns the wallet PIN

Status: Accepted, 2026-11-14 (spec revision 6). Supersedes M11 once tickets 71–72 are built; until then the code follows M11.

## Context

The RFP service catalogue (`spec/new-middleware-services.md`) puts PIN verification and HSM custody in the middleware and keeps the core to ledger, limits, fees and risk decisions. Under M11 the core decrypts the PIN and checks a bcrypt hash, so PIN custody, the lockout and the PIN lifecycle (set, change, reset) sit with the core vendor, and the middleware cannot offer login, device binding with PIN or a self-service reset without a synchronous core call (which D5 forbids). Wallet PINs have no card, so a card PVV is not required to verify them.

## Decision

- `mw-auth` owns the wallet PIN of customers, agents, merchants, distributors and DSOs: policy, verification, lockout, change and reset (spec 18.7).
- The PIN is stored as HMAC-SHA256 under a PIN key held in the HSM, over walletId and PIN; `mw-crypto` decrypts the RSA-OAEP transport block and computes the HMAC (spec 18.8). No reversible PIN value is stored anywhere.
- Policy: 5 digits; all-same digits, runs, part of the MSISDN, the birth year and the last PIN are refused. Lockout: 3 wrong tries, then locks of 1 h, 24 h and permanent until a reset; every number is configuration.
- PVV and ISO 9564 PIN blocks are used only where a card scheme or switch needs them (ATM, NPSB), on the payShield provider (D23).

## Consequences

- `services/mw-core-mock` loses its PIN key pair, `wallet.pin_hash` and its lockout; it checks the assertion of D22 instead.
- The ops unlock of ticket 52 moves from the core command `UnlockWallet` to the `mw-auth` command topic `auth.wallet-lock.v1`.
- With PKCS#11 (SoftHSM and most general-purpose HSMs) the clear PIN exists briefly as a zeroed `byte[]` inside `mw-crypto`; only a payment HSM keeps it inside the device (open point 22).
- The real core vendor must accept this split (open point 23).

## Where in the repo

- spec sections 18.7, 18.8 and decision D20
- tickets 63, 67, 70, 71 in `.scratch/bits-mfs-middleware/issues/`
- today's scheme, to be replaced: `services/mw-core-mock` (`security/PinCipher.java`, `security/PinKeyProvider.java`), `services/mw-ussd/src/main/java/mfs/mw/ussd/util/PinCipher.java`
