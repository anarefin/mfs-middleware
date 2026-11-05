# 29: App device binding + request signature

**What to build:** Sensitive app operations come only from a bound device. The app gateway checks `X-Device-Id` against the registered device public key and verifies an ECDSA P-256 signature over method, path, body hash and timestamp. Replays are rejected through a ±5 min window plus a nonce cache in Valkey. Sessions use `sess:{subject}:{deviceId}` with idle timeout and a single active device per wallet (configurable). Spec WP20 (part).

**Blocked by:**

- 09: App gateway P2P + minimal APISIX route

**Status:** done

- [x] Unit tests: valid signature accepted; tampered body, wrong key and stale timestamp are rejected
- [x] Integration test: the same nonce reused is rejected (`MW-AUTH-*`)
- [x] e2e: a P2P with a valid signature passes; a replayed signed request is rejected (green at the milestone gate, below)
- [x] `make verify M=<module>` passes for every module touched, with the test output attached to the PR (green at the milestone gate, below)

## Milestone gate (2026-11-05, integration branch `spec/upay-mfs-middleware`)

- `make verify` (all 13 modules, at 9b73ac2 after the review fixes; later gate fixes verified per module): `==> verify passed: 13 module(s)`, exit 0
- Fresh stack at fcd51ef (`make down DOWN_ARGS=-v && make up`):
  - `make smoke`: `smoke: 46 passed, 0 failed`
  - `make edge-test`: `edge-test: 42 passed, 0 failed`
  - `make e2e` (full suite): `110 scenarios, 0 failed, 0 skipped`, `BUILD SUCCESSFUL in 21m 10s`
- Gate fixes and user decisions: see the merge commits after 70f7add and memory `upay-m3-decisions`.
