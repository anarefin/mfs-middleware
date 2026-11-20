# 139: dry-run candidates must not reach a live OAuth2 token endpoint or reuse a live token (security follow-up)

Source: automated security review 2026-11-20 on the merchant copy; pre-existing in every adapter's partner framework
(`partner/route/PartnerRouteLoader.java` ~584 in mw-adapter-bank, -billpay, -telco, and -merchant once 115a merges).

**Problem:** in a dry-run candidate only the `url`/`address` parameter of `mw-http-call` / SOAP is rewritten to the
dry-run target; an `oauth2` auth step keeps the partner's real `tokenUrl`, so a dry run fetches a live token with the
live client secret. `OAuth2ClientCredentials` caches tokens per partner id, so a candidate can also reuse the live
partner's cached token.

**Fix (test-first, identical in all adapter copies; `scripts/test/test_partner_engine_copies.sh` stays green):**
- In a dry-run candidate rewrite `tokenUrl` to the dry-run target as well (or refuse oauth2-cc in dry-run with a clear
  dry-run error; pick the one that keeps folder dry-run tests green).
- Token cache key gains a candidate/version discriminator and a hash of the client secret, so a candidate never reuses a
  live token.
- Tests: a dry-run of an oauth2 partner never calls the real token URL (WireMock); live and candidate get separate tokens.

**Blocked by:** 115a (so the merchant copy is fixed too). Milestone: M9 follow-up.
