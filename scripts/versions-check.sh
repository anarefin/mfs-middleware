#!/usr/bin/env bash
# Reports version drift between modules' own version catalogs
# (<module>/gradle/libs.versions.toml, [versions] table only).
# Usage: scripts/versions-check.sh [MODULE]
#   With MODULE, only drift on keys where MODULE's version differs from another
#   module is reported. Exits 1 when any drift is reported.
# Modules own their versions (D14), so drift is allowed but must be visible:
# this check is a report, not part of `make verify`.
set -eu
. "$(dirname "$0")/lib/modules.sh"

m="${1:-}"
focus=""
[ -n "$m" ] && focus="$(selected_modules "$m")"

# Emit "module<TAB>key<TAB>version" for every [versions] entry of every module.
pairs() {
  local mod cat
  for mod in $(all_modules); do
    cat="$MW_ROOT/$mod/gradle/libs.versions.toml"
    [ -f "$cat" ] || continue
    awk -v mod="$mod" '
      /^[[:space:]]*\[/ { in_versions = ($0 ~ /^[[:space:]]*\[versions\][[:space:]]*(#.*)?$/); next }
      in_versions && /^[[:space:]]*[A-Za-z0-9_.-]+[[:space:]]*=[[:space:]]*"/ {
        line = $0; sub(/#.*/, "", line)
        key = line; sub(/^[[:space:]]*/, "", key); sub(/[[:space:]]*=.*/, "", key)
        val = line; sub(/^[^"]*"/, "", val); sub(/".*/, "", val)
        printf "%s\t%s\t%s\n", mod, key, val
      }' "$cat"
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
  printf 'versions-check: %d key(s) drift between module catalogs\n' "$(printf '%s\n' "$report" | wc -l | tr -d ' ')" >&2
  exit 1
fi
info "versions-check: no version drift${focus:+ involving $focus}"
