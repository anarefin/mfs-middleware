#!/usr/bin/env bash
# Fails on @Autowired field injection anywhere under a module's src/ (main and test).
# Usage: scripts/check-field-injection.sh [MODULE]
#
# Constructor injection only (template CLAUDE.md): production beans use final fields set
# by the constructor; JUnit tests use an @Autowired constructor or
# @TestConstructor(autowireMode = ALL). @Autowired on a constructor, method or method
# parameter (e.g. a static @BeforeAll hook) is allowed; on a field it is not.
set -eu
. "$(dirname "$0")/lib/common.sh"

m="${1:-}"
modules="$(selected_modules "$m")"

# scan FILE...: print "file:line: text" for every @Autowired that annotates a field.
scan() {
  awk '
    function strip_annotations(s) {
      while (match(s, /^[[:space:]]*@[A-Za-z_][A-Za-z0-9_.]*(\([^)]*\))?/)) s = substr(s, RLENGTH + 1)
      sub(/^[[:space:]]+/, "", s)
      return s
    }
    function is_field(s) {
      if (s ~ /[()]/) return 0                       # constructor, method or parameter
      if (s ~ /(^|[[:space:]])(lateinit[[:space:]]+)?(var|val)[[:space:]]/) return 1   # Kotlin property
      return s ~ /;/                                 # Java field declaration
    }
    FNR == 1 { pending = 0 }
    /^[[:space:]]*(\/\/|\/\*|\*)/ { next }
    {
      line = $0
      if (index(line, "@Autowired") > 0) {
        rest = substr(line, index(line, "@Autowired") + length("@Autowired"))
        sub(/^\([^)]*\)/, "", rest)
        rest = strip_annotations(rest)
        if (rest == "") { pending = FNR; ptext = line; next }
        if (is_field(rest)) printf "%s:%d: %s\n", FILENAME, FNR, line
        pending = 0
        next
      }
      if (pending) {
        rest = strip_annotations(line)
        if (rest == "") next
        if (is_field(rest)) printf "%s:%d: %s\n", FILENAME, pending, ptext
        pending = 0
      }
    }
  ' "$@"
}

found=0
for mod in $modules; do
  [ -d "$MW_ROOT/$mod/src" ] || continue
  files="$(find "$MW_ROOT/$mod/src" -type f \( -name '*.java' -o -name '*.kt' -o -name '*.groovy' \) | sort)"
  [ -n "$files" ] || continue
  # shellcheck disable=SC2086
  out="$(scan $files)"
  if [ -n "$out" ]; then
    printf '%s\n' "$out" | sed "s|^$MW_ROOT/|FIELD-INJECTION |"
    found=$((found + $(printf '%s\n' "$out" | wc -l)))
  fi
done

if [ "$found" -gt 0 ]; then
  printf 'check-field-injection FAILED: %d @Autowired field(s); use constructor injection\n' "$found" >&2
  exit 1
fi
info "check-field-injection passed: $(printf '%s' "$modules" | grep -c . || true) module(s)"
