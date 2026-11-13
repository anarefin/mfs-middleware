#!/usr/bin/env bash
# Runs the shell test suite for scripts/ and the repo layout (./gradlew :scriptTests runs it).
# Usage: scripts/test/run.sh [test_file.sh ...]   (default: every scripts/test/test_*.sh)
#
# Test files run side by side, SCRIPT_TESTS_JOBS at a time (default 3; 1 = one after another):
# every test already runs in its own fresh fixture root (lib.sh new_root), so files share no
# state. The output is printed per file in the order given, whichever file finishes first.
set -u

here="$(cd "$(dirname "$0")" && pwd)"

# run_file FILE: every test_* function of FILE, each in a fresh subshell and fixture root; prints
# one "ok"/"FAIL" line per test.
run_file() {
  local f="$1" tests t root rc
  tests="$( (source "$here/lib.sh"; source "$f"; declare -F | awk '{print $3}' | grep '^test_') )"
  for t in $tests; do
    root="$(source "$here/lib.sh"; new_root)"
    ( set -u; source "$here/lib.sh"; source "$f"; cd "$root" && "$t" ); rc=$?
    rm -rf "$root"
    if [ "$rc" -eq 0 ]; then
      printf 'ok   %s :: %s\n' "$(basename "$f")" "$t"
    else
      printf 'FAIL %s :: %s\n' "$(basename "$f")" "$t"
    fi
  done
}

# Worker mode (used by xargs below): run one file, its output into OUTDIR/<index>.out.
if [ "${1:-}" = "--one" ]; then
  run_file "$4" >"$2/$3.out" 2>&1
  exit 0
fi

files=("$@")
[ ${#files[@]} -eq 0 ] && files=("$here"/test_*.sh)
jobs="${SCRIPT_TESTS_JOBS:-3}"

out="$(mktemp -d "${TMPDIR:-/tmp}/mwtests.XXXXXX")"
trap 'rm -rf "$out"' EXIT
i=0
for f in "${files[@]}"; do
  printf '%s\0%s\0' "$i" "$f"
  i=$((i + 1))
done | xargs -0 -n 2 -P "$jobs" bash "$here/run.sh" --one "$out"

pass=0; failed=0; failures=""
i=0
for f in "${files[@]}"; do
  if [ -f "$out/$i.out" ]; then
    cat "$out/$i.out"
  else
    printf 'FAIL %s :: (did not run)\n' "$(basename "$f")" | tee "$out/$i.out"
  fi
  pass=$((pass + $(grep -c '^ok   ' "$out/$i.out")))
  for t in $(grep '^FAIL ' "$out/$i.out" | sed 's/^FAIL \([^ ]*\) :: \(.*\)$/\1::\2/' | tr ' ' '_'); do
    failed=$((failed + 1)); failures="$failures $t"
  done
  i=$((i + 1))
done

printf '\n%d passed, %d failed\n' "$pass" "$failed"
[ "$failed" -eq 0 ] || { printf 'failed:%s\n' "$failures"; exit 1; }
