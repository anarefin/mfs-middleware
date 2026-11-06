# 52: Ops unlock of PIN-locked wallets via the admin console

**What to build:** A wallet locked after 5 wrong PINs can be unlocked by operations through the admin console with maker-checker. This was decided at the M2 gate (2026-11-04).

- **Core contract:** a new core command `UnlockWallet` with events `WalletUnlocked` and `UnlockRejected`, in `core.command.v1` / `core.event.v1`. The owner per `contracts/OWNERS.yml` adds them as per-message payload files.
- **Core mock:** `mw-core-mock` handles the command. It clears the lock and the failure counter, deduplicates on `commandId`, and rejects the command when the wallet is not locked.
- **Admin API:** `mw-admin-api` offers "unlock wallet" as a change request. The maker and the checker must differ, and on approval it sends `UnlockWallet`. Both the request and the approval are audited.
- **Admin UI:** the screen lands with the approvals inbox (ticket 41).

**Blocked by:**

- 37: Maker-checker engine + manual TSQ / reversal

**Status:** done

- [x] Contract samples for the new command and events validate (`make contracts-lint`)
- [x] A core-mock test shows a locked wallet is unlocked once, and an unlock of a wallet that isn't locked is rejected
- [x] An admin API test shows maker ≠ checker is enforced, and approval sends `UnlockWallet` exactly once
- [x] e2e: 5 wrong PINs lock the wallet, an approved unlock lets the next correct-PIN P2P succeed (written: `tests/e2e/src/test/resources/features/wallet-unlock.feature`, `@wallet-unlock`; runs at the milestone gate) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
- [x] `make verify` passes for every module touched (deferred to milestone gate) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
