# softhsm: dev and test PKCS#11 tokens

Ticket 61 (spec 18.8 and 22.2, ADR D23). The `softhsm` container runs SoftHSMv2 and holds two tokens.
`mw-crypto` (ticket 62) and the payShield simulator in `mw-simulators` (ticket 64) use them as their HSM
in dev and test. It is never used outside dev and test.

| Token (label knob) | Default label | Port | Used by |
| --- | --- | --- | --- |
| `SOFTHSM_MWCRYPTO_LABEL` | `mw-crypto` | 5657 | `mw-crypto`: wallet PIN HMAC, JWS/HMAC signing, tokenisation, envelope KEKs, XML-DSig keys |
| `SOFTHSM_PAYSIM_LABEL` | `paysim` | 5658 | the payShield 10K simulator: its LMK and the keys it encrypts under the LMK |

The SO and user PINs are `SOFTHSM_MWCRYPTO_SO_PIN`, `SOFTHSM_MWCRYPTO_USER_PIN`, `SOFTHSM_PAYSIM_SO_PIN`
and `SOFTHSM_PAYSIM_USER_PIN`. They are fake, documented values in `deploy/dev.env.defaults`
(`dev-mwcrypto-user-pin` and so on). `scripts/creds.sh` prints them and `docs/testing-guide.html`
section 1 lists them. The `provision` one-shot copies the mw-crypto user PIN into Vault
`secret/mw-crypto` under the key `mw.crypto.pkcs11.user-pin`. That is the PIN mw-crypto logs in with
(spec 18.4). The SO PINs are only used to initialise the tokens.

## Access shape: Option A, PKCS#11 over a socket (p11-kit RPC)

**Decision.** SoftHSM runs only in this container. Each token is served on its own TCP port with
the p11-kit RPC protocol: `socat TCP-LISTEN:<port>,fork EXEC:"p11-kit remote --provider
libsofthsm2.so pkcs11:token=<label>"` (`serve.sh`, `remote-token.sh`). A client loads the p11-kit
client side, not SoftHSM. The token files sit on the named volume `softhsm-tokens`, and no other
service mounts that volume. `scripts/test/test_softhsm.sh` checks this.

**Why not Option B?** Option B mounts the token directory into `mw-crypto` and `mw-simulators` and bakes
libsofthsm2 into their images. It was rejected for three reasons:

- **Keys stay out of every other container.** With Option B, the token files (keys wrapped only by
  the user PIN) and the code that unwraps them would sit in two app containers. Option A behaves
  like a network HSM: callers get PKCS#11 sessions, never key files. This matches spec 22.2
  ("SoftHSMv2 with a PKCS#11 socket") and D23.
- **It works in Testcontainers.** A TCP port can be mapped to the host, so a test JVM reaches the
  token the same way a container does. A shared token directory (Option B) would need the host and
  the container to share files and the same SoftHSM build. A unix socket does not cross the Docker
  Desktop VM on macOS either, so the bridge is TCP, not `p11-kit server`'s unix socket.
- **One port per token isolates the tokens.** `pkcs11:token=<label>` exposes only that token. A
  client sees exactly one slot (`slotListIndex = 0` in SunPKCS11, no lookup by label), and the
  simulator cannot see mw-crypto's token.

**Cost.** Each client image needs p11-kit and socat, plus one small module file. The client's
p11-kit must speak the server's RPC version: Alpine `p11-kit=0.26.2-r0`, as in this image (ticket 62
found that Ubuntu noble's 0.25.3 logs in but fails every key generation with `CKR_DEVICE_MEMORY`, so
the mw-crypto image is Alpine; `scripts/test/test_softhsm.sh` checks the versions match). The port has no transport security. That
is acceptable for dev and test because a session still needs the token's user PIN. The port is
reachable on the `app` network and on 127.0.0.1 only. Production uses the real HSM's PKCS#11
library over its own secured channel (open point 22), behind the same mw-crypto provider SPI.

The healthcheck uses that same client path. `pkcs11-tool --list-slots` runs through the p11-kit proxy
against both ports (`healthcheck.sh`, `pkcs11-modules/*.module`). So a healthy container proves the
bridge works, not just that SoftHSM is installed.

## Client recipes (tickets 62 and 64)

**In a container (`mw-crypto`, `mw-simulators` images).** Install p11-kit and socat, then add one
module file per token:

```
# /etc/pkcs11/modules/mw-crypto.module
remote: |socat - TCP:softhsm:5657
```

Then point SunPKCS11 at the p11-kit proxy, which is `libp11-kit.so.0` itself (`/usr/lib/libp11-kit.so.0` on
Alpine). The mw-crypto image adds `connect-timeout=3,keepalive,keepidle=5,keepintvl=2,keepcnt=3` to the
socat address, so a vanished softhsm ends the connection within seconds instead of hanging:

```
name = mw-crypto
library = /usr/lib/libp11-kit.so.0
slotListIndex = 0
```

Log in with the user PIN from Vault (`mw.crypto.pkcs11.user-pin`) and never log it. Remove any
other module files under `/usr/share/p11-kit/modules/` and `/etc/pkcs11/modules/` in the client image.
Otherwise the proxy shows their slots too and `slotListIndex = 0` may pick the wrong token.

**From one fixed library path, no module files.** Use `p11-kit-client.so`
(`/usr/lib/pkcs11/p11-kit-client.so` on Alpine) with `P11_KIT_SERVER_ADDRESS=unix:path=/tmp/p11.sock`.
A local `socat UNIX-LISTEN:/tmp/p11.sock,fork TCP:softhsm:5657` bridges that socket to the port.

**Testcontainers (`Pkcs11ProviderIT`, ticket 62; the simulator IT, ticket 64).** Module isolation
forbids a path outside the module, so copy this folder into the module's test resources
(`src/test/resources/softhsm/`, with `pkcs11-modules/`). Build it with `ImageFromDockerfile` and set the
six `SOFTHSM_*` variables with obviously fake values, network alias `softhsm`, and wait with
`Wait.forHealthcheck()`. The image has a `HEALTHCHECK`, and every script runs through `/bin/sh`, so the
copy works even if the exec bits are lost. No PKCS#11 tooling on the host (decision 2026-11-14): the
PKCS#11 side never runs in the host test JVM. The IT runs the client's own image next to this
container on a Testcontainers network and drives it over HTTP (mw-crypto: `HsmStack`, described in
`services/mw-crypto/CLAUDE.md`, "HSM integration tests"). `scripts/test/test_softhsm.sh` fails when a
copy differs from this folder.

## Files

| File | Purpose |
| --- | --- |
| `Dockerfile` | Alpine 3.22 pinned by digest; `softhsm`, `opensc`, `p11-kit`, `p11-kit-server` and `socat` at pinned versions; user 10001; `HEALTHCHECK` |
| `init-tokens.sh` | Creates the two tokens on a fresh volume and keeps them on every later start (matched by exact label). Never generates keys and never prints a PIN |
| `serve.sh` | Entrypoint: `init-tokens.sh`, then one socat listener per token. Exits when a listener dies |
| `remote-token.sh` | One `p11-kit remote` session limited to one token |
| `healthcheck.sh`, `pkcs11-modules/` | `pkcs11-tool --list-slots` through both sockets must show both labels |
| `softhsm2.conf` | Token directory `/var/lib/softhsm/tokens` (the `softhsm-tokens` volume), file object store |

## Operating it

From `deploy/`:

- Start it alone: `docker compose --profile core up -d --build --wait softhsm`.
- A second `up` on the same volume logs `token mw-crypto kept`.
- List the slots from inside: `docker compose --profile core exec softhsm pkcs11-tool --module /usr/lib/libp11-kit.so.0 --list-slots`.
- Start with fresh tokens: `./reset.sh` drops the volume, which destroys every key in it.
- Change a label or a PIN in `deploy/.env` only together with `./reset.sh`. A PIN is set only when
  the token is first created.

Knobs: `SOFTHSM_MEM` (memory limit, default 48M), `MW_PORT_SOFTHSM_MWCRYPTO` (default 5657) and
`MW_PORT_SOFTHSM_PAYSIM` (default 5658). The host ports are bound to 127.0.0.1.
