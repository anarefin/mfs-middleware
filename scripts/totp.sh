#!/usr/bin/env bash
# Prints the current one-time code (TOTP) of an ops or portal persona with a pre-set secret, so a
# tester signs in to https://localhost/console (or an org portal login) without an authenticator app
# (docs/testing-guide.html):
#   scripts/totp.sh [PERSONA]     (default seed-ops-maker)
# Realms ops and portals (deploy/keycloak/realms): RFC 6238, HmacSHA256, 6 digits, 30 s. The secret
# (deploy/.env: KC_OPS_TEST_TOTP_SECRET for ops, KC_PORTALS_TEST_TOTP_SECRET for portals, ticket 73) is
# keyed as its raw UTF-8 bytes, never base32-decoded: the same algorithm as mw-seeder util/Totp,
# tests/api/lib.js and ui/mw-admin-ui/e2e/support/totp.ts.
# Personas with a pre-set secret: ops seed-ops-* (mw-seeder) and ops-e2e-* (realm import); every
# portals user: seed-org-* (mw-seeder) and org-*, sp-viewer01 (realm import). The other ops users
# (ops-maker01, ...) enrol their own authenticator app at the first sign-in.
# MW_TOTP_TIME=<epoch seconds> fixes the clock (tests).
set -eu
. "$(dirname "$0")/lib/common.sh"

persona="${1:-seed-ops-maker}"
case "$persona" in
  seed-ops-maker|seed-ops-checker|seed-ops-viewer|seed-ops-compliance|seed-ops-secadmin1|seed-ops-secadmin2|ops-e2e-maker01|ops-e2e-checker01) var=KC_OPS_TEST_TOTP_SECRET ;;
  seed-org-maker|seed-org-checker|org-maker01|org-checker01|org-makerchecker01|org-checker02|sp-viewer01) var=KC_PORTALS_TEST_TOTP_SECRET ;;
  *) die "totp: $persona has no pre-set secret; it enrols an authenticator app (FreeOTP or Aegis, SHA-256) at its first sign-in. Pre-set: seed-ops-maker, seed-ops-checker, seed-ops-viewer, seed-ops-compliance, seed-ops-secadmin1, seed-ops-secadmin2, ops-e2e-maker01, ops-e2e-checker01 (ops); seed-org-maker, seed-org-checker, org-maker01, org-checker01, org-makerchecker01, org-checker02, sp-viewer01 (portals)" ;;
esac
[ -f "$STACK_ENV" ] || die "totp: deploy/.env does not exist (cd deploy && cp dev.env.defaults .env)"
secret="$(sed -n "s/^$var=//p" "$STACK_ENV" | tail -1)"
[ -n "$secret" ] || die "totp: $var is not in deploy/.env (see deploy/dev.env.defaults)"
command -v node >/dev/null 2>&1 || die "totp: node is not on PATH (prerequisite: Node.js 24)"

MW_TOTP_SECRET="$secret" MW_TOTP_PERSONA="$persona" node -e '
const { createHmac } = require("node:crypto");
const now = process.env.MW_TOTP_TIME ? Number(process.env.MW_TOTP_TIME) : Date.now() / 1000;
const step = Math.floor(now / 30);
const counter = Buffer.alloc(8);
counter.writeBigUInt64BE(BigInt(step));
const h = createHmac("sha256", Buffer.from(process.env.MW_TOTP_SECRET, "utf8")).update(counter).digest();
const o = h[h.length - 1] & 0x0f;
const bin = ((h[o] & 0x7f) << 24) | (h[o + 1] << 16) | (h[o + 2] << 8) | h[o + 3];
const code = String(bin % 1000000).padStart(6, "0");
const left = 30 - Math.floor(now % 30);
console.log(`${process.env.MW_TOTP_PERSONA}: ${code}  (valid ${left} s more; a code works once: for a second sign-in wait for the next one)`);
'
