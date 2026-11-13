#!/usr/bin/env bash
# Reports version drift between the modules' build files (services/*/build.gradle.kts and the
# template's): the versions are written inline (no version catalog), so every plugin
# `id("x") version "v"` and every coordinate "group:artifact:version" (platforms/BOMs included)
# is compared by plugin id / group:artifact.
# Usage: scripts/versions-check.sh [MODULE]
#   With MODULE, only drift on keys where MODULE's version differs from another
#   module is reported. Exits 1 when any drift is reported.
# Modules own their versions (D14), so drift is allowed but must be visible: this check is a
# report, not part of `./gradlew build` (plugin versions must match anyway: scripts/check-isolation.sh).
set -eu
. "$(dirname "$0")/lib/common.sh"

m="${1:-}"
focus=""
[ -n "$m" ] && focus="$(selected_modules "$m")"

# Emit "module<TAB>key<TAB>version" for every inline version of every module's build.gradle.kts
# (whole-line comments skipped).
pairs() {
  local mod f
  for mod in $(all_modules); do
    f="$MW_ROOT/$mod/build.gradle.kts"
    [ -f "$f" ] || continue
    awk -v mod="$mod" '
      /^[[:space:]]*(\/\/|\/\*|\*)/ { next }
      {
        line = $0; sub(/[[:space:]]\/\/.*/, "", line)
        if (match(line, /id[[:space:]]*\([[:space:]]*"[^"]+"[[:space:]]*\)[[:space:]]*version[[:space:]]*"[^"]+"/)) {
          t = substr(line, RSTART, RLENGTH)
          split(t, q, "\"")
          printf "%s\tplugin %s\t%s\n", mod, q[2], q[4]
        }
        rest = line
        while (match(rest, /"[A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^":@ ]+"/)) {
          t = substr(rest, RSTART + 1, RLENGTH - 2)
          rest = substr(rest, RSTART + RLENGTH)
          n = split(t, c, ":")
          printf "%s\t%s:%s\t%s\n", mod, c[1], c[2], c[3]
        }
      }' "$f"
  done
}

report="$(pairs | sort -t "$(printf '\t')" -k2,2 -k1,1 | awk -F '\t' -v focus="$focus" '
  function flush() {
    if (n > 1 && distinct > 1 && (focus == "" || hasfocus)) {
      printf "DRIFT %s:", key
      for (i = 1; i <= n; i++) printf " %s=%s", mods[i], vals[i]
      printf "\n"
    }
  }
  $2 != key { flush(); key = $2; n = 0; distinct = 0; hasfocus = 0; delete seen }
  {
    n++; mods[n] = $1; vals[n] = $3
    if (!($3 in seen)) { seen[$3] = 1; distinct++ }
    if ($1 == focus) hasfocus = 1
  }
  END { flush() }')"

if [ -n "$focus" ] && [ -n "$report" ]; then
  # Keep only keys where the focus module itself disagrees with someone.
  report="$(printf '%s\n' "$report" | awk -v focus="$focus" '{
    fv = ""; for (i = 3; i <= NF; i++) { split($i, kv, "="); if (kv[1] == focus) fv = kv[2] }
    other = 0; for (i = 3; i <= NF; i++) { split($i, kv, "="); if (kv[1] != focus && kv[2] != fv) other = 1 }
    if (other) print }')"
fi

if [ -n "$report" ]; then
  printf '%s\n' "$report"
  printf 'versions-check: %d key(s) drift between module build files\n' "$(printf '%s\n' "$report" | wc -l | tr -d ' ')" >&2
  exit 1
fi
info "versions-check: no version drift${focus:+ involving $focus}"
