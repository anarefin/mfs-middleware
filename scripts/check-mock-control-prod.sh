#!/usr/bin/env bash
# Fails when anything but a test double turns on the Spring profile mock-control (FRS MCT-08, ADR D33,
# ticket 103). The profile carries the mock-control engine and its API (scenarios, switches, the global
# reset that wipes the core ledger); only the mocks have it, and only in the dev/test stack.
# Usage: scripts/check-mock-control-prod.sh
#
# Checked:
#  - deploy/docker-compose.yml: a SPRING_PROFILES_ACTIVE naming mock-control only in the block of a mock
#    service (MOCK_SERVICES); never in a shared anchor (x-jvm-env and the like) or another service;
#  - deploy/dev.env.defaults and deploy/.env: SPRING_PROFILES_ACTIVE never names it (it would reach every
#    service through the shared anchor);
#  - services/*/src/main/resources/application*.yml: no spring.profiles active/include/group line names it
#    (a module may not switch it on by itself; the mocks get it from compose and their test config).
# Each service guards itself as well: with the profile and MW_ENV=prod it refuses to start.
set -eu
. "$(dirname "$0")/lib/common.sh"

# The test doubles allowed to run the profile.
MOCK_SERVICES="${MOCK_SERVICES:-mw-core-mock mw-simulators}"

compose="$MW_ROOT/deploy/docker-compose.yml"
bad=""

if [ -f "$compose" ]; then
  hits="$(awk -v mocks=" $MOCK_SERVICES " '
    /^[^[:space:]#]/ { top = $0; svc = "" }
    top ~ /^services:/ && /^  [A-Za-z0-9_.-]+:[[:space:]]*$/ { svc = $1; sub(/:$/, "", svc) }
    /^[[:space:]]*#/ { next }
    /SPRING_PROFILES_ACTIVE/ && /mock-control/ {
      where = (top ~ /^services:/ && svc != "") ? svc : "(shared, outside a service: " top ")"
      if (index(mocks, " " svc " ") == 0 || top !~ /^services:/) printf "%s:%d: %s\n", where, NR, $0
    }
  ' "$compose")"
  [ -z "$hits" ] || bad="$bad$(printf 'deploy/docker-compose.yml enables mock-control outside a mock service:\n%s\n' "$hits")
"
fi

for envf in "$MW_ROOT/deploy/dev.env.defaults" "$MW_ROOT/deploy/.env"; do
  [ -f "$envf" ] || continue
  if grep -nE '^[[:space:]]*SPRING_PROFILES_ACTIVE=.*mock-control' "$envf" >/dev/null; then
    bad="$bad${envf#"$MW_ROOT"/}: SPRING_PROFILES_ACTIVE names mock-control (every service would get it)
"
  fi
done

for f in "$MW_ROOT"/services/*/src/main/resources/application*.yml "$MW_ROOT"/services/*/src/main/resources/application*.yaml \
         "$MW_ROOT"/services/*/src/main/resources/application*.properties; do
  [ -f "$f" ] || continue
  if grep -nE '(active|include|group|spring\.profiles[.a-z]*)[[:space:]]*[:=].*mock-control' "$f" >/dev/null; then
    bad="$bad${f#"$MW_ROOT"/}: switches the mock-control profile on by itself
"
  fi
done

if [ -n "$bad" ]; then
  printf 'mock-control profile outside the test doubles (FRS MCT-08):\n%s' "$bad" >&2
  exit 1
fi
echo "mock-control: only the test doubles ($MOCK_SERVICES) enable it"
