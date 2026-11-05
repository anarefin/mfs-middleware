#!/usr/bin/env bash
# Prints the services/* modules changed since BASE, one module name per line, sorted.
# Usage: scripts/changed-modules.sh [BASE]   (BASE defaults to main)
# "Changed" covers commits on this branch since it left BASE (git diff BASE...HEAD), staged and
# unstaged edits, and untracked files. Prints the single word ALL when contracts/ or deploy/
# changed (APISIX schemas, compose and topic config feed every module), and nothing when no module
# changed (docs/, tests/, templates/ only). Used by scripts/e2e-changed.sh.
set -eu
. "$(dirname "$0")/lib/modules.sh"

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

if printf '%s\n' "$files" | grep -q -E '^(contracts|deploy)/'; then
  echo ALL
  exit 0
fi

stack="$(all_modules | grep '^services/' || true)"
printf '%s\n' "$files" | sed -n 's#^\(services/[^/]*\)/.*#\1#p' | sort -u | while read -r mod; do
  printf '%s\n' "$stack" | grep -qx "$mod" && basename "$mod"
done
exit 0
