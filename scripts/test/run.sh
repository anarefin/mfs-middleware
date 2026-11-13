#!/usr/bin/env bash
# Runs the shell test suite for scripts/ and the repo layout (./gradlew :scriptTests runs it).
# Usage: scripts/test/run.sh [test_file.sh ...]   (default: every scripts/test/test_*.sh)
set -u

here="$(cd "$(dirname "$0")" && pwd)"
files=("$@")
[ ${#files[@]} -eq 0 ] && files=("$here"/test_*.sh)

pass=0; failed=0; failures=""
for f in "${files[@]}"; do
  tests="$( (source "$here/lib.sh"; source "$f"; declare -F | awk '{print $3}' | grep '^test_') )"
  for t in $tests; do
    root="$(source "$here/lib.sh"; new_root)"
    ( set -u; source "$here/lib.sh"; source "$f"; cd "$root" && "$t" ); rc=$?
    rm -rf "$root"
    if [ "$rc" -eq 0 ]; then
      pass=$((pass + 1)); printf 'ok   %s :: %s\n' "$(basename "$f")" "$t"
    else
      failed=$((failed + 1)); failures="$failures $(basename "$f")::$t"
      printf 'FAIL %s :: %s\n' "$(basename "$f")" "$t"
    fi
  done
done

printf '\n%d passed, %d failed\n' "$pass" "$failed"
[ "$failed" -eq 0 ] || { printf 'failed:%s\n' "$failures"; exit 1; }
