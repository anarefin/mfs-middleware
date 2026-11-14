#!/bin/sh
# One PKCS#11 RPC session over stdin/stdout for one token (socat runs this per TCP connection, see
# serve.sh): `p11-kit remote` loads SoftHSMv2 here, inside the softhsm container, and exposes only
# the token with label $1, so a client sees exactly one slot and never the other token.
exec p11-kit remote --provider /usr/lib/softhsm/libsofthsm2.so "pkcs11:token=$1"
