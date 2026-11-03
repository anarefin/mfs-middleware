#!/usr/bin/env bash
# Dependency, container and secret scans (spec 18.5, WP29).
# Usage: scripts/security.sh [MODULE]
#   - secret scan:     gitleaks over the repo (or the module directory)
#   - dependency scan: ./gradlew cyclonedxBom per module, then trivy fs (HIGH,CRITICAL)
#   - container scan:  trivy image mw-<name>:local when that image has been built
# Requires gitleaks and trivy on PATH; fails when a scanner is missing.
set -eu
. "$(dirname "$0")/lib/modules.sh"

m="${1:-}"
modules="$(selected_modules "$m")"

missing=""
for t in gitleaks trivy; do command -v "$t" >/dev/null 2>&1 || missing="$missing $t"; done
[ -z "$missing" ] || die "security: missing scanner(s):$missing (install them, e.g. brew install gitleaks trivy)"

failed=""
if [ -n "$m" ]; then targets="$modules"; else targets="."; fi
for t in $targets; do
  info "security: gitleaks on $t"
  gitleaks dir --no-banner --redact "$MW_ROOT/$t" || failed="$failed secrets:$t"
done

for mod in $modules; do
  name="$(basename "$mod")"
  if [ -x "$MW_ROOT/$mod/gradlew" ]; then
    info "security: $mod SBOM (cyclonedxBom)"
    (cd "$MW_ROOT/$mod" && ./gradlew cyclonedxBom) || failed="$failed sbom:$mod"
  fi
  info "security: trivy fs on $mod"
  trivy fs --exit-code 1 --severity HIGH,CRITICAL "$MW_ROOT/$mod" || failed="$failed deps:$mod"
  if docker image inspect "$name:local" >/dev/null 2>&1; then
    info "security: trivy image $name:local"
    trivy image --exit-code 1 --severity HIGH,CRITICAL "$name:local" || failed="$failed image:$mod"
  fi
done

if [ -n "$failed" ]; then
  printf 'security FAILED:%s\n' "$failed" >&2
  exit 1
fi
info "security passed"
