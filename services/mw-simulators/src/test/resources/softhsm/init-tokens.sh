#!/bin/sh
# Creates the dev/test PKCS#11 tokens on the token volume, idempotently (ticket 61, spec 18.8, D23):
#   SOFTHSM_MWCRYPTO_LABEL (mw-crypto): mw-crypto's token (wallet PINs, signing, tokenisation, envelopes)
#   SOFTHSM_PAYSIM_LABEL   (paysim):    the payShield simulator's LMK store (mw-simulators, ticket 64)
# A token whose label already exists is kept (keys survive a restart and a second `up`); a missing
# one is initialised in the next free slot with its SO and user PIN (SOFTHSM_*_SO_PIN /
# SOFTHSM_*_USER_PIN, fake documented values from deploy/dev.env.defaults). No key is generated here:
# mw-crypto generates its keys through its key API. Run by serve.sh before it opens the sockets.
# Dev and test only; a PIN is never printed.
set -eu

log() { printf 'softhsm: %s\n' "$*"; }
die() { log "$*" >&2; exit 1; }

check() { # check <LABEL_VAR> <SO_PIN_VAR> <USER_PIN_VAR>
  for v in "$@"; do
    eval "val=\${$v:-}"
    [ -n "$val" ] || die "$v is not set (cp dev.env.defaults .env)"
  done
  eval "label=\$$1 so=\$$2 pin=\$$3"
  [ "${#label}" -le 32 ] || die "$1 is longer than 32 characters (PKCS#11 token label)"
  case "$label" in *[!A-Za-z0-9._-]*) die "$1 may hold only letters, digits, '.', '_' and '-'" ;; esac
  [ "${#so}" -ge 4 ] && [ "${#pin}" -ge 4 ] || die "$2 and $3 need at least 4 characters (SoftHSM PIN minimum)"
}

# has_token <label>: an initialised token with exactly this label (labels are space-padded to 32)
has_token() {
  softhsm2-util --show-slots 2>/dev/null | sed -n 's/^[[:space:]]*Label:[[:space:]]*//p' \
    | sed 's/[[:space:]]*$//' | grep -qxF -- "$1"
}

init_token() { # init_token <label> <so-pin> <user-pin>
  if has_token "$1"; then
    log "token $1 kept"
  else
    softhsm2-util --init-token --free --label "$1" --so-pin "$2" --pin "$3" >/dev/null
    log "token $1 created"
  fi
}

check SOFTHSM_MWCRYPTO_LABEL SOFTHSM_MWCRYPTO_SO_PIN SOFTHSM_MWCRYPTO_USER_PIN
check SOFTHSM_PAYSIM_LABEL SOFTHSM_PAYSIM_SO_PIN SOFTHSM_PAYSIM_USER_PIN
[ "$SOFTHSM_MWCRYPTO_LABEL" != "$SOFTHSM_PAYSIM_LABEL" ] || die "the two token labels must differ"

init_token "$SOFTHSM_MWCRYPTO_LABEL" "$SOFTHSM_MWCRYPTO_SO_PIN" "$SOFTHSM_MWCRYPTO_USER_PIN"
init_token "$SOFTHSM_PAYSIM_LABEL" "$SOFTHSM_PAYSIM_SO_PIN" "$SOFTHSM_PAYSIM_USER_PIN"
