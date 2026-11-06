#!/usr/bin/env bash
# make api-test [FOLDER=] [ALL=1]: the Newman API suite in tests/api against the running stack
# (ticket 58; it replaced the Cucumber suite and make e2e / e2e-changed).
#   FOLDER  folders to run, by number or full name, separated by commas ("01, 05"; numbers may
#           also be separated by spaces: "01 05")
#           default every folder except 90 Slow and 99 Destructive
#   ALL=1   every folder, the slow ones included
# tests/api/run.sh re-applies the test-only gateway routes, then runs Newman.
set -eu
. "$(dirname "$0")/lib/modules.sh"

folders="${1:-}"
all="${2:-}"
case "$all" in '' | 0 | 1) ;; *) die "api-test: ALL must be empty or 1, got '$all'" ;; esac

run="$MW_ROOT/tests/api/run.sh"
if [ ! -x "$run" ]; then
  info "api-test: no API suite in tests/api yet, nothing to run"
  exit 0
fi

args=()
# a comma separates folders; a part of numbers only ("05 09") is one folder per number
if [ -n "$folders" ]; then
  IFS=',' read -r -a parts <<<"$folders"
  for f in "${parts[@]}"; do
    f="$(printf '%s' "$f" | sed -e 's/^ *//' -e 's/ *$//')"
    [ -z "$f" ] && continue
    if printf '%s' "$f" | grep -qE '^[0-9 ]+$'; then
      for w in $f; do args+=(--folder "$w"); done
    else
      args+=(--folder "$f")
    fi
  done
fi
[ "$all" = 1 ] && args+=(--all)
exec "$run" ${args[@]+"${args[@]}"}
