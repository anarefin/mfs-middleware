#!/bin/sh
# Healthy when pkcs11-tool --list-slots sees both tokens through the sockets, over the same p11-kit
# client path mw-crypto and mw-simulators use (the p11-kit proxy loads pkcs11-modules/*.module, each
# a `remote: |socat - TCP:127.0.0.1:<port>`), not by loading SoftHSM directly.
set -eu
slots="$(pkcs11-tool --module /usr/lib/libp11-kit.so.0 --list-slots 2>/dev/null)"
for label in "$SOFTHSM_MWCRYPTO_LABEL" "$SOFTHSM_PAYSIM_LABEL"; do
  printf '%s\n' "$slots" | grep -qE "token label +: $label\$" || { echo "token $label not served"; exit 1; }
done
