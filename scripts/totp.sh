#!/usr/bin/env bash
# Prints the current ops console one-time code (TOTP) of a persona with a pre-set secret, so a tester
# signs in to https://localhost/console without an authenticator app (docs/testing-guide.html):
#   make totp [PERSONA=seed-ops-maker]
# Realm ops (deploy/keycloak/realms/ops-realm.json): RFC 6238, HmacSHA256, 6 digits, 30 s. The secret
# KC_OPS_TEST_TOTP_SECRET (deploy/.env) is keyed as its raw UTF-8 bytes, never base32-decoded: the same
# algorithm as mw-seeder util/Totp, tests/api/lib.js and ui/mw-admin-ui/e2e/support/totp.ts.
# Personas with the secret: seed-ops-* (make seed) and ops-e2e-* (realm import). The other ops users
# (ops-maker01, ...) enrol their own authenticator app at the first sign-in.
# MW_TOTP_TIME=<epoch seconds> fixes the clock (tests).
set -eu
. "$(dirname "$0")/lib/modules.sh"

persona="${1:-seed-ops-maker}"
case "$persona" in
  seed-ops-maker|seed-ops-checker|seed-ops-viewer|seed-ops-compliance|ops-e2e-maker01|ops-e2e-checker01) ;;
  *) die "totp: $persona has no pre-set secret; it enrols an authenticator app (FreeOTP or Aegis, SHA-256) at its first sign-in. Pre-set: seed-ops-maker, seed-ops-checker, seed-ops-viewer, seed-ops-compliance, ops-e2e-maker01, ops-e2e-checker01" ;;
esac
[ -f "$STACK_ENV" ] || die "totp: deploy/.env does not exist (make up generates it)"
secret="$(sed -n 's/^KC_OPS_TEST_TOTP_SECRET=//p' "$STACK_ENV" | tail -1)"
[ -n "$secret" ] || die "totp: KC_OPS_TEST_TOTP_SECRET is not in deploy/.env (make up adds it)"
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
