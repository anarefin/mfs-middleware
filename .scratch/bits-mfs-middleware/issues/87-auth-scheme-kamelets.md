# 87: More auth scheme Kamelets (basic, OAuth2 client credentials, mTLS, JWS)

**What to build:** Auth Kamelets so a partner using any of these schemes is onboarded by config only (spec 12.1). They ship in all three adapters,
identical, and are added to the loader and admin-api allowlists.

| Kamelet | Behaviour |
|---|---|
| `mw-auth-basic` | `Authorization: Basic`. User and password by `secretRef` |
| `mw-auth-oauth2-cc` | client-credentials token from `tokenUrl` (on the host allowlist), with `clientId`, the secret by `secretRef`, `scope` and `audience`. The token is **cached** per partner until `expires_in − skew`. A 401 from the partner invalidates the cache and retries once. The token is never logged or audited |
| `mw-auth-jws` | signs the body as a detached JWS (`alg` ES256/RS256 as a property), sets the header named in the record. The key is through mw-crypto if a fitting key purpose exists (check `services/mw-crypto` caller policy; **ask the user** before adding a key purpose there), otherwise a dev key from OpenBao |
| mTLS (`mw-http-call` option `sslContextRef`) | a client keystore per partner, from OpenBao at startup (static, D28), registered as a named `SSLContextParameters` bean. The route can only name it, never supply key material |

- **Secrets:** all of them come through Camel's HashiCorp Vault properties function against OpenBao (82), static at startup. The mTLS keystore is read
  the same way into a named `SSLContextParameters` bean.
- **Kamelet rules from 82:** the `action` type label, documented properties, secret refs marked as secrets, lint, and no secret or token in an endpoint
  URI (the token goes in a header). Check each Camel feature against the current Camel 4.x docs (Context7) before use.
- **Tests:** each Kamelet gets an AdviceWith/MockEndpoint unit test (headers or signature verified against known vectors) and an IT with a WireMock partner (for OAuth2: token caching and refresh on 401).
- **Masking:** `MaskingPolicy` and `scripts/log-scan.sh` patterns cover bearer tokens and basic credentials.
- **Docs:** the runbook gets the auth options table; `docs/concepts/` adds the Camel page section.

**Blocked by:** 84

**Status:** done (b4d2b1c1, 7677e0fa)

- [ ] A new biller using OAuth2-CC is onboarded by config only (`NewBillerRouteIT` variant): one token call for N payments, a refresh after expiry or a 401
- [ ] mTLS IT: the call succeeds with the named keystore and fails closed (UNKNOWN or refused) with a wrong one
- [ ] No token, password or key in logs or audit (`scripts/log-scan.sh` with the stack up)
- [ ] The three adapters build green with Docker
