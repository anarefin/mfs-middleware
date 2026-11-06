#!/usr/bin/env bash
# Enforces module independence (spec 4.3, D13, D14) over the Gradle files.
# Usage: scripts/check-isolation.sh [MODULE]
#
# A module fails when its Gradle files (settings/build scripts, gradle.properties,
# version catalogs) contain:
#   - includeBuild(...)                                  (no composite inside a module)
#   - project(":...") or an mfs.mw* artifact coordinate  (cross-module dependency)
#   - buildSrc/ or build-logic/, apply(from=...), `kotlin-dsl`, or a plugin id in the
#     mfs.mw / mw. namespace                              (convention / shared plugins)
#   - a relative path leaving the module, or parentFile navigation; the only allowed
#     target outside the module is the root contracts/ folder
# The repo root fails when it has buildSrc/, a shared version catalog, or
# allprojects/subprojects configuration.
set -eu
. "$(dirname "$0")/lib/modules.sh"

violations=0
violation() { printf 'ISOLATION %s: %s\n' "$1" "$2"; violations=$((violations + 1)); }

# Gradle-relevant files of a module, skipping build output and caches.
gradle_files() {
  find "$1" \( -name build -o -name .gradle -o -name node_modules -o -name .git \) -prune -o \
    -type f \( -name '*.gradle.kts' -o -name '*.gradle' -o -name 'gradle.properties' -o -name '*.versions.toml' \) \
    -print
}

# code FILE: print "lineno:text" for each line that is not a whole-line comment
# (//, /*, *, # for Kotlin DSL, Groovy, properties and TOML).
code() {
  awk '!/^[[:space:]]*(\/\/|\/\*|\*|#)/ { print NR ":" $0 }' "$1"
}

# normalize PATH: collapse "." and ".." segments of a relative path (textually).
# Prints "<outside-root>" when the path climbs above the repo root.
normalize() {
  local IFS=/ seg out=()
  for seg in $1; do
    case "$seg" in
      ''|.) ;;
      ..) if [ ${#out[@]} -eq 0 ]; then echo "<outside-root>"; return; fi
          unset "out[$((${#out[@]} - 1))]"; out=("${out[@]+"${out[@]}"}") ;;
      *) out=("${out[@]+"${out[@]}"}" "$seg") ;;
    esac
  done
  printf '%s\n' "${out[*]+"${out[*]}"}"
}

check_module() {
  local mod="$1" dir="$MW_ROOT/$1" f rel line tok target
  for d in buildSrc build-logic; do
    [ -e "$dir/$d" ] && violation "$mod" "convention plugin: $d/ is not allowed"
  done
  for f in $(gradle_files "$dir"); do
    rel="${f#"$MW_ROOT"/}"
    code "$f" | grep -E 'includeBuild[[:space:]]*\(' | while IFS= read -r line; do
      echo "ISOLATION $mod: includeBuild in $rel:$line"; done
    code "$f" | grep -E 'project[[:space:]]*\([[:space:]]*(path[[:space:]]*=[[:space:]]*)?"?:' | while IFS= read -r line; do
      echo "ISOLATION $mod: cross-module project dependency in $rel:$line"; done
    code "$f" | grep -E '"mfs\.mw[A-Za-z0-9._-]*:|group[[:space:]]*=[[:space:]]*"mfs\.mw' | grep -v '^[0-9]*:[[:space:]]*group[[:space:]]*=[[:space:]]*"mfs\.mw[^"]*"[[:space:]]*$' \
      | while IFS= read -r line; do echo "ISOLATION $mod: dependency on an mfs.mw artifact in $rel:$line"; done
    code "$f" | grep -E 'apply[[:space:]]*\(?[[:space:]]*from|`kotlin-dsl`|id[[:space:]]*\(?[[:space:]]*"(mfs\.mw|mw)\.' | while IFS= read -r line; do
      echo "ISOLATION $mod: convention plugin / shared script in $rel:$line"; done
    code "$f" | grep -E 'parentFile|\.parent[^A-Za-z]' | while IFS= read -r line; do
      echo "ISOLATION $mod: path navigation outside the module in $rel:$line"; done
    # Relative paths: every token with a ".." segment must stay in the module or land in contracts/.
    code "$f" | cut -d: -f2- | grep -oE "[^\"'[:space:]=(),]*\.\.[^\"'[:space:](),]*" | while IFS= read -r tok; do
      case "$tok" in ..|../*|*/..|*/../*) ;; *) continue ;; esac
      target="$(normalize "$mod/$tok")"
      case "$target" in
        "$mod"|"$mod"/*|contracts|contracts/*) ;;
        *) echo "ISOLATION $mod: relative path outside the module in $rel: $tok (-> $target)" ;;
      esac
    done
  done
}

check_root() {
  [ -e "$MW_ROOT/buildSrc" ] && violation root "convention plugin: root buildSrc/ is not allowed"
  [ -e "$MW_ROOT/build-logic" ] && violation root "convention plugin: root build-logic/ is not allowed"
  [ -e "$MW_ROOT/gradle/libs.versions.toml" ] && violation root "shared version catalog gradle/libs.versions.toml is not allowed"
  local f
  for f in "$MW_ROOT/build.gradle.kts" "$MW_ROOT/settings.gradle.kts"; do
    [ -f "$f" ] || continue
    if code "$f" | grep -E '(allprojects|subprojects)[[:space:]]*\{|dependencyResolutionManagement|versionCatalogs' >/dev/null; then
      violation root "shared configuration in $(basename "$f") (aggregate tasks only)"
    fi
  done
}

# ADR D16: the stack is one hand-written deploy/docker-compose.yml. No module (services/*,
# templates/*, ui/*) has its own compose.yml, and nothing outside docs/adr/ (the history) refers to
# the removed per-module layout (ticket 56).
check_compose_layout() {
  local f hits old="deploy/""compose/"
  for f in "$MW_ROOT"/services/*/compose.yml "$MW_ROOT"/templates/*/compose.yml "$MW_ROOT"/ui/*/compose.yml; do
    [ -f "$f" ] || continue
    f="${f#"$MW_ROOT"/}"
    violation "${f%/compose.yml}" "compose.yml: a module has no compose file of its own; its services live in deploy/docker-compose.yml (ADR D16)"
  done
  hits="$(grep -rIl -F "$old" "$MW_ROOT" \
    --exclude-dir=.git --exclude-dir=build --exclude-dir=.gradle --exclude-dir=node_modules \
    --exclude-dir=.next --exclude-dir=.scratch --exclude-dir=.claude 2>/dev/null \
    | sed "s#^$MW_ROOT/##" | grep -v -E '^(docs/adr|scripts/test)/' || true)"
  for f in $hits; do
    violation "$f" "refers to the removed $old layout (now deploy/docker-compose.yml, ADR D16)"
  done
}

m="${1:-}"
modules="$(selected_modules "$m")"
check_root
[ -n "$m" ] || check_compose_layout
for mod in $modules; do
  out="$(check_module "$mod")"
  if [ -n "$out" ]; then
    printf '%s\n' "$out"
    violations=$((violations + $(printf '%s\n' "$out" | wc -l)))
  fi
done

if [ "$violations" -gt 0 ]; then
  printf 'check-isolation FAILED: %d violation(s)\n' "$violations" >&2
  exit 1
fi
info "check-isolation passed: $(printf '%s' "$modules" | grep -c . || true) module(s)"
