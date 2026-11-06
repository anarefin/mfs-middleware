#!/usr/bin/env bash
# One module of `make build` (scripts/build.sh runs it, JOBS at a time).
# Usage: build-jar.sh OUTDIR MODULE
# Runs ./gradlew bootJar in MODULE, logs to OUTDIR/<name>.log, writes "pass|fail SECONDS" to
# OUTDIR/<name>.result. Always exits 0; the result file carries it.
set -eu
. "$(dirname "$0")/modules.sh"

out="$1"; mod="$2"
name="$(basename "$mod")"
start="$(date +%s)"
status=fail
(cd "$MW_ROOT/$mod" && ./gradlew bootJar -q) >"$out/$name.log" 2>&1 && status=pass
secs="$(( $(date +%s) - start ))"
printf '%s %s\n' "$status" "$secs" >"$out/$name.result"
printf '==> build %s: bootJar %s (%ss)\n' "$name" "$status" "$secs"
exit 0
