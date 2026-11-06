# Per-module check runner shared by scripts/verify.sh and scripts/verify-changed.sh (tickets 53, 54).
# Source after modules.sh (bash 3.2 compatible).
#
# Each module's tests run with TESTCONTAINERS_REUSE_ENABLE=true: its TestInfrastructure starts one
# PostgreSQL and one Kafka container labelled mw.module=<module> and mw.root=<repo root>, and both
# test JVMs (integrationTest, conformance) use them. The containers are removed once the module's
# check ends, and again (every module of this checkout) at the end of a full `make verify`, unless
# KEEP_TC=1 (then the next run of the same module reuses them).

GATE_LIB="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# gate_jobs JOBS: fail unless JOBS is a positive number.
gate_jobs() {
  case "$1" in '' | *[!0-9]* | 0) die "JOBS must be a positive number, got '$1'" ;; esac
}

# tc_cleanup [MODULE]: remove the reused test containers of MODULE (default: every module) of this
# checkout. A no-op with KEEP_TC=1 or without a docker CLI; never fails.
tc_cleanup() {
  case "${KEEP_TC:-0}" in '' | 0 | false) ;; *) return 0 ;; esac
  command -v docker >/dev/null 2>&1 || return 0
  local which="label=mw.module" ids
  [ -n "${1:-}" ] && which="label=mw.module=$1"
  ids="$(docker ps -aq --filter "$which" --filter "label=mw.root=$MW_ROOT" 2>/dev/null || true)"
  if [ -n "$ids" ]; then
    # shellcheck disable=SC2086 # one id per word
    docker rm -f $ids >/dev/null 2>&1 || true
  fi
  return 0
}

# gate_check LABEL OUTDIR JOBS MODULE...: ./gradlew check in each MODULE, JOBS at a time. Output
# goes to OUTDIR/<module>.log (and to the terminal too with JOBS=1); prints a pass/fail and
# duration summary and returns 1 when any module failed.
gate_check() {
  local label="$1" out="$2" jobs="$3" mod name result failed=""
  shift 3
  rm -rf "$out"
  mkdir -p "$out"
  printf '%s\n' "$@" | xargs -P "$jobs" -I{} "$GATE_LIB/check-module.sh" "$label" "$out" "$jobs" {}

  printf '\n%s summary (logs in %s/):\n' "$label" "${out#"$MW_ROOT"/}"
  for mod in "$@"; do
    name="$(basename "$mod")"
    result="fail ?"
    [ -f "$out/$name.result" ] && result="$(cat "$out/$name.result")"
    printf '  %-24s %s %ss\n' "$name" "${result% *}" "${result#* }"
    [ "${result% *}" = pass ] || failed="$failed $name"
  done
  if [ -n "$failed" ]; then
    printf '%s FAILED:%s\n' "$label" "$failed" >&2
    return 1
  fi
  return 0
}
