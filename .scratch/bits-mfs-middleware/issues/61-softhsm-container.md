# 61: softhsm container for dev and test

**What to build:** A SoftHSMv2 container that `mw-crypto` (ticket 62) and the payShield simulator (ticket 64) use as their PKCS#11 token in dev and test (spec 18.8, 22.2, D23).

- `deploy/softhsm/`: a `Dockerfile` (pinned SoftHSMv2 and OpenSC versions on a minimal base, non-root user), and an init script that creates the tokens on a fresh volume, idempotently: `mw-crypto` (wallet PINs, signing, tokenisation, envelopes) and `paysim` (payShield simulator LMK store). Token labels and fake, documented SO and user PINs come from `deploy/dev.env.defaults` (`SOFTHSM_*` knobs). Keys are not generated here; `mw-crypto` generates them through its key API.
- Access: pick the simplest shape that keeps keys out of every other container and works in Testcontainers too. Option A: a PKCS#11-over-socket bridge (e.g. the p11-kit server). Option B: SoftHSM's token directory on a named volume mounted only into `mw-crypto` and `mw-simulators`, with the SoftHSM library baked into those images. Record the choice and the reason in `deploy/softhsm/README.md`.
- The compose block goes under profiles `core` and `minimal` with a memory limit and a healthcheck (`pkcs11-tool --list-slots` sees both tokens). The dev token PIN is written into OpenBao KV `secret/mw-crypto` by `deploy/provision/services.sh`.
- `scripts/creds.sh` prints the dev token labels and PINs; `docs/testing-guide.html` section 1 lists them.

**Blocked by:** 60

**Status:** done

Access shape: Option A (p11-kit RPC over TCP, one port per token: 5657 mw-crypto, 5658 paysim); reason and client recipes in `deploy/softhsm/README.md`. The mw-crypto user PIN goes to `secret/mw-crypto` key `mw.crypto.pkcs11.user-pin` via `SECRETS_AHEAD` in `deploy/provision/services.sh` (ticket 62 moves it into `provision_mw_crypto`).

- [x] `docker compose --profile core up -d --build --wait softhsm` is healthy; a second `up` on the same volume keeps the tokens (verified on an isolated compose project: healthy, `--force-recreate` logged `token ... kept`, a generated key survived; the full stack run is deferred to final integration test)
- [x] `./gradlew :composeCheck` green (budget ≤ 8 GB with the new limit)
- [x] `./gradlew :scriptTests` green (README, creds and docs mention the new knobs)
