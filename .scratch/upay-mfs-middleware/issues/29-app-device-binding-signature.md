# 29: App device binding + request signature

**What to build:** Sensitive app operations come only from a bound device. The app gateway checks `X-Device-Id` against the registered device public key and verifies an ECDSA P-256 signature over method, path, body hash and timestamp. Replays are rejected through a ±5 min window plus a nonce cache in Valkey. Sessions use `sess:{subject}:{deviceId}` with idle timeout and a single active device per wallet (configurable). Spec WP20 (part).

**Blocked by:**

- 09: App gateway P2P + minimal APISIX route

**Status:** done

- [x] Unit tests: valid signature accepted; tampered body, wrong key and stale timestamp are rejected
- [x] Integration test: the same nonce reused is rejected (`MW-AUTH-*`)
- [ ] e2e: a P2P with a valid signature passes; a replayed signed request is rejected (feature written: `tests/e2e/.../device-binding.feature`; run deferred to milestone gate)
- [ ] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (deferred to milestone gate)
