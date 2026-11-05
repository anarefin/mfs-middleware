# Module discovery shared by the Makefile scripts (bash 3.2 compatible).
#
# A module is a standalone Gradle build: a directory directly under one of
# MODULE_PARENTS that has its own settings.gradle(.kts). New module kinds
# (e.g. tests/ suites) plug in by adding their parent folder here.
#
# MW_ROOT is the repo root (defaults to the folder holding scripts/).

MW_ROOT="${MW_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
MODULE_PARENTS="services templates"

die() { printf 'error: %s\n' "$*" >&2; exit 2; }
info() { printf '==> %s\n' "$*"; }

# all_modules: print every module dir relative to MW_ROOT, one per line, sorted.
all_modules() {
  local parent d
  for parent in $MODULE_PARENTS; do
    for d in "$MW_ROOT/$parent"/*/; do
      [ -d "$d" ] || continue
      d="${d%/}"
      if [ -f "$d/settings.gradle.kts" ] || [ -f "$d/settings.gradle" ]; then
        printf '%s\n' "${d#"$MW_ROOT"/}"
      fi
    done
  done | sort
}

# selected_modules [M]: all modules, or only the one whose name (or path) is M.
selected_modules() {
  local m="${1:-}" mod
  if [ -z "$m" ]; then all_modules; return 0; fi
  m="${m%/}"
  for mod in $(all_modules); do
    if [ "$mod" = "$m" ] || [ "$(basename "$mod")" = "$m" ]; then
      printf '%s\n' "$mod"; return 0
    fi
  done
  die "unknown module '$m' (known: $(all_modules | xargs -n1 basename | tr '\n' ' '))"
}

# compose_files FILE: set DC_F to the -f arguments for FILE. For the MVP project
# (deploy/compose/mvp/compose.yml) that adds its low-resource override compose.lowmem.yml when
# present, so every target (up, down, smoke, edge-test, e2e) sees the same model of the stack.
# Use as: compose_files "$file"; docker compose "${DC_F[@]}" ...
compose_files() {
  DC_F=(-f "$1")
  local over
  over="$(dirname "$1")/compose.lowmem.yml"
  if [ "$1" = "$MW_ROOT/deploy/compose/mvp/compose.yml" ] && [ -f "$over" ]; then
    DC_F+=(-f "$over")
  fi
  return 0
}
