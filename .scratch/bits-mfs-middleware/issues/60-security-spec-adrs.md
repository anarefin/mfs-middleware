# 60: Security group: spec revision 6 and ADRs D20–D24

**What to build:** Docs only, no code. Bring the spec and the ADRs in line with the security decisions the user made on 2026-11-14 (plan `spec-mobile-money-middleware-rfp-md-spe-shiny-sparrow`, interview decisions S1–S15), so tickets 61–76 build against a written design.

- `spec/bits-mfs-middleware-spec.md` revision 6:
  - decisions D20–D24 in "Decisions confirmed"; M11 marked superseded;
  - 2 (second money-path exception), 4.1 (`mw-auth`, `mw-crypto`), 8.2 (`/app/v1` JWKS, `/auth/v1`), 9.1–9.3, 14.3 (signed anchor), 16.2 (`auth`, `crypto` schemas), 18.2–18.4;
  - new 18.7 (`mw-auth`) and 18.8 (`mw-crypto`), 22.2 (softhsm, payShield simulator), 24 traceability, 25 open points 22–24.
- `docs/adr/D20-pin-custody.md` … `D24-service-mtls-phase-1.md`, the index `docs/adr/README.md`, the M11 status line.
- No contract files here: each later ticket adds the contracts it produces or consumes, with fixtures, so `./gradlew :contractsLint` stays green at every step.

**Blocked by:** —

**Status:** done

- [x] `./gradlew :scriptTests` green (ADR per decision row, every path the ADRs name exists)
- [x] Each of S1–S15 is in the spec and in at least one of tickets 61–76
