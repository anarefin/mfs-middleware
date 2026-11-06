#!/usr/bin/env bash
# One module of the gate (scripts/lib/gate.sh gate_check runs it, JOBS at a time).
# Usage: check-module.sh LABEL OUTDIR JOBS MODULE
# Runs ./gradlew check in MODULE with Testcontainers reuse on, logs to OUTDIR/<name>.log (also to
# the terminal when JOBS is 1), writes "pass|fail SECONDS" to OUTDIR/<name>.result, then removes
# the module's reused containers (unless KEEP_TC=1). Always exits 0; the result file carries it.
set -eu
. "$(dirname "$0")/modules.sh"
. "$(dirname "$0")/gate.sh"

label="$1"; out="$2"; jobs="$3"; mod="$4"
name="$(basename "$mod")"
log="$out/$name.log"
start="$(date +%s)"
status=fail
export TESTCONTAINERS_REUSE_ENABLE=true

if [ ! -x "$MW_ROOT/$mod/gradlew" ]; then
  printf 'error: %s has no executable gradlew\n' "$mod" | tee "$log" >&2
elif [ "$jobs" = 1 ]; then
  info "$label $name: ./gradlew check"
  set -o pipefail
  # shellcheck disable=SC2086 # GRADLE_ARGS is a list of words
  if (cd "$MW_ROOT/$mod" && ./gradlew check ${GRADLE_ARGS:-}) 2>&1 | tee "$log"; then status=pass; fi
  set +o pipefail
else
  # shellcheck disable=SC2086
  if (cd "$MW_ROOT/$mod" && ./gradlew check ${GRADLE_ARGS:-}) >"$log" 2>&1; then status=pass; fi
fi
tc_cleanup "$name"

secs="$(( $(date +%s) - start ))"
printf '%s %s\n' "$status" "$secs" >"$out/$name.result"
printf '==> %s %s: %s (%ss)\n' "$label" "$name" "$status" "$secs"
exit 0
