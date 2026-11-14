#!/bin/sh
# Entrypoint of the softhsm container (ticket 61): create the tokens (init-tokens.sh, idempotent),
# then serve each token on its own TCP port as the p11-kit RPC protocol (deploy/softhsm/README.md):
#   5657  SOFTHSM_MWCRYPTO_LABEL  (mw-crypto)
#   5658  SOFTHSM_PAYSIM_LABEL    (payShield simulator in mw-simulators)
# Every connection gets its own `p11-kit remote` process (remote-token.sh). The container exits when
# either listener dies, so Compose restarts it.
set -eu

sh /opt/softhsm/init-tokens.sh

socat TCP-LISTEN:5657,reuseaddr,fork EXEC:"/bin/sh /opt/softhsm/remote-token.sh $SOFTHSM_MWCRYPTO_LABEL" &
a=$!
socat TCP-LISTEN:5658,reuseaddr,fork EXEC:"/bin/sh /opt/softhsm/remote-token.sh $SOFTHSM_PAYSIM_LABEL" &
b=$!
printf 'softhsm: serving token %s on 5657 and %s on 5658\n' "$SOFTHSM_MWCRYPTO_LABEL" "$SOFTHSM_PAYSIM_LABEL"

trap 'kill "$a" "$b" 2>/dev/null; exit 0' TERM INT
while kill -0 "$a" 2>/dev/null && kill -0 "$b" 2>/dev/null; do
  sleep 2 &
  wait $! || true
done
printf 'softhsm: a listener stopped, exiting\n' >&2
kill "$a" "$b" 2>/dev/null || true
exit 1
