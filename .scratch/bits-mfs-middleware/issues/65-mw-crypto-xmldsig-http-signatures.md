# 65: mw-crypto XML-DSig and HTTP-message signatures

**What to build:** Signing helpers for NPSB/BanglaQR (ISO 20022) and the SOAP partners (spec 18.8).

- `POST /internal/v1/xmldsig/sign` and `/verify`: enveloped signature, RSA-SHA256, exclusive C14N. The signer is the `XMLDSIG` key; verification uses partner certificates given by reference (PEM in the request, or a keyId). XXE is off, and the size is capped.
- `POST /internal/v1/http-signature/sign` and `/verify`: RFC 9421 HTTP message signatures over the given components, ES256/RSA-PSS/HMAC.
- These are building blocks only: no adapter uses them yet (ATM, interop and merchant SOAP adapters come later).
- **Tests:** round-trip and tamper tests (a changed element or header fails verify), a signature-wrapping attack test for XML, and contract fixtures.

**Blocked by:** 63

**Status:** open

- [ ] `./gradlew :mw-crypto:build` green with Docker
