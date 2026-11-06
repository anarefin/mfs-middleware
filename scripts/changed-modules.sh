#!/usr/bin/env bash
# Prints the modules changed since BASE, one module name per line, sorted.
# Usage: scripts/changed-modules.sh [--contracts-precise] [BASE]   (BASE defaults to main)
# "Changed" covers commits on this branch since it left BASE (git diff BASE...HEAD), staged and
# unstaged edits, and untracked files.
#
# Default (used by scripts/e2e-changed.sh): the services/* modules changed. Prints the single word
# ALL when contracts/ or deploy/ changed (APISIX schemas, compose and topic config feed every
# module), and nothing when no module changed (docs/, tests/, templates/ only). Markdown under
# deploy/ (deploy/**/*.md) and docs/ never select a module, in either mode.
#
# --contracts-precise (used by scripts/verify-changed.sh, ticket 53): a changed contract file
# selects the modules whose contracts.yml lists its schema or API (producers and consumers; a
# payload schema or sample counts as its topic's schema) plus its owner from contracts/OWNERS.yml.
# A change in contracts/common/ or contracts/OWNERS.yml, deploy/, scripts/ or the root build
# (Makefile, build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/) prints ALL. A
# changed templates/* module is listed too. Other contracts/ files (README, EXTERNAL.yml) select
# no module (the contracts lint still runs).
set -eu
. "$(dirname "$0")/lib/modules.sh"

precise=""
if [ "${1:-}" = --contracts-precise ]; then precise=1; shift; fi
base="${1:-main}"
cd "$MW_ROOT"
git rev-parse --verify -q "$base^{commit}" >/dev/null || die "changed-modules: unknown base '$base' (a branch, tag or commit)"

files="$(
  {
    git diff --name-only "$base"...HEAD
    git diff --name-only HEAD
    git ls-files --others --exclude-standard
  } | sort -u
)"
# Documentation selects no module (ticket 54): Markdown under deploy/ and anything under docs/.
files="$(printf '%s\n' "$files" | grep -v -E '^deploy/.*\.md$|^docs/' || true)"

if [ -z "$precise" ]; then
  if printf '%s\n' "$files" | grep -q -E '^(contracts|deploy)/'; then
    echo ALL
    exit 0
  fi
  stack="$(all_modules | grep '^services/' || true)"
  printf '%s\n' "$files" | sed -n 's#^\(services/[^/]*\)/.*#\1#p' | sort -u | while read -r mod; do
    printf '%s\n' "$stack" | grep -qx "$mod" && basename "$mod"
  done
  exit 0
fi

if printf '%s\n' "$files" | grep -q -E '^(deploy/|scripts/|contracts/common/|contracts/OWNERS\.yml$|Makefile$|build\.gradle\.kts$|settings\.gradle\.kts$|gradle\.properties$|gradle/)'; then
  echo ALL
  exit 0
fi

modules="$(all_modules)"

# contract_of FILE: the schema or API (path under contracts/) a changed contract file belongs to.
contract_of() {
  local f="${1#contracts/}"
  case "$f" in
    events/*/samples/*)
      # events/<owner>/samples/<topic>/[<Msg>/]<sample> -> events/<owner>/<topic>.schema.json
      printf '%s\n' "$f" | sed -n 's#^\(events/[^/]*\)/samples/\([^/]*\)/.*#\1/\2.schema.json#p' ;;
    events/*/*/*.payload.schema.json)
      printf '%s\n' "$f" | sed -n 's#^\(events/[^/]*/[^/]*\)/[^/]*\.payload\.schema\.json$#\1.schema.json#p' ;;
    events/*.schema.json | api/*) printf '%s\n' "$f" ;;
  esac
}

{
  printf '%s\n' "$files" | sed -n -E 's#^((services|templates)/[^/]*)/.*#\1#p'
  printf '%s\n' "$files" | grep '^contracts/' | while read -r f; do
    c="$(contract_of "$f")"
    [ -n "$c" ] || continue
    for mod in $modules; do
      if [ -f "$mod/contracts.yml" ] && grep -qF "$c" "$mod/contracts.yml"; then printf '%s\n' "$mod"; fi
    done
    owner="$(sed -n "s#^  $c: *\\(mw-[a-z0-9-]*\\).*#\\1#p" contracts/OWNERS.yml 2>/dev/null | head -1)"
    if [ -n "$owner" ]; then printf 'services/%s\n' "$owner"; fi
  done
} | sort -u | while read -r mod; do
  if printf '%s\n' "$modules" | grep -qx "$mod"; then basename "$mod"; fi
done
exit 0
