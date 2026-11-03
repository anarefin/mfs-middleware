#!/usr/bin/env bash
# Runs a black-box suite in tests/<suite> (its own Gradle build) against the running stack.
# Usage: scripts/suite.sh e2e|perf [MODULE] [TAGS]
#   e2e:  ./gradlew test with Cucumber tags; MODULE adds the tag @<module>
#   perf: ./gradlew gatlingRun; MODULE is passed as -Dmw.module=<module>
# Passes (does nothing) while the suite does not exist yet.
set -eu
. "$(dirname "$0")/lib/modules.sh"

suite="${1:?suite name (e2e|perf)}"
m="${2:-}"
tags="${3:-}"
name=""
if [ -n "$m" ]; then
  mod="$(selected_modules "$m")"
  name="$(basename "$mod")"
fi

dir="$MW_ROOT/tests/$suite"
if [ ! -x "$dir/gradlew" ]; then
  info "$suite: no $suite suite in tests/$suite yet, nothing to run"
  exit 0
fi

args=()
case "$suite" in
  e2e)
    args=(test)
    filter=""
    [ -n "$name" ] && filter="@$name"
    if [ -n "$tags" ]; then filter="${filter:+$filter and }($tags)"; fi
    [ -n "$filter" ] && args+=("-Dcucumber.filter.tags=$filter")
    ;;
  perf)
    args=(gatlingRun)
    [ -n "$name" ] && args+=("-Dmw.module=$name")
    ;;
  *) die "unknown suite '$suite'" ;;
esac

info "$suite: tests/$suite ./gradlew ${args[*]}"
cd "$dir" && ./gradlew "${args[@]}"
