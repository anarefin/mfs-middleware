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

# The single-host stack: one hand-written Compose file with profiles (ADR D16).
STACK_FILE="$MW_ROOT/deploy/docker-compose.yml"
STACK_ENV="$MW_ROOT/deploy/.env"
STACK_TIMERS="$MW_ROOT/deploy/test-timers.env"
# the timers the running stack was started with (make up writes it, make reset removes it)
STACK_TIMERS_STATE="$MW_ROOT/build/stack-timers"
# every profile of the stack file (make down / make reset stop them all)
STACK_PROFILES="minimal core seed"

# stack_compose [PROFILE...]: set DC_F to the docker compose arguments for the stack: the file,
# the generated deploy/.env (and deploy/test-timers.env with TIMERS=fast), then --profile for each
# PROFILE (default core: every service, as the read-only targets ps, exec and logs need).
# Without TIMERS, the timers the running stack was started with (build/stack-timers, written by
# make up) apply, so a later target never recreates a service with other timers; make up itself
# sets MW_STACK_UP=1 and uses only its own TIMERS.
# Use as: stack_compose; docker compose "${DC_F[@]}" ...
stack_compose() {
  [ -f "$STACK_FILE" ] || die "deploy/docker-compose.yml does not exist"
  if [ -z "${TIMERS:-}" ] && [ "${MW_STACK_UP:-0}" != 1 ] && [ -f "$STACK_TIMERS_STATE" ]; then
    TIMERS="$(cat "$STACK_TIMERS_STATE")"
  fi
  DC_F=(-f "$STACK_FILE")
  # gen-dev-env.sh writes it (make up does); a read-only call on a checkout without it still works
  # (scripts that start or stop the stack run gen-dev-env.sh first, so they always pass it)
  if [ -f "$STACK_ENV" ] || [ "${TIMERS:-}" = fast ]; then DC_F+=(--env-file "$STACK_ENV"); fi
  case "${TIMERS:-}" in
    '') ;;
    fast) DC_F+=(--env-file "$STACK_TIMERS") ;;
    *) die "TIMERS must be empty or fast, got '$TIMERS'" ;;
  esac
  local p
  [ "$#" -gt 0 ] || set -- core
  for p in "$@"; do DC_F+=(--profile "$p"); done
  return 0
}

# stack_has_service NAME: true when deploy/docker-compose.yml defines service NAME (a two-space
# indented key; the file is hand-written in that layout).
stack_has_service() {
  grep -q "^  $1:\$" "$STACK_FILE" 2>/dev/null
}

# stack_project: the Compose project name (COMPOSE_PROJECT_NAME, or the file's `name:`).
stack_project() {
  if [ -n "${COMPOSE_PROJECT_NAME:-}" ]; then printf '%s\n' "$COMPOSE_PROJECT_NAME"; return 0; fi
  sed -n 's/^name: *//p' "$STACK_FILE" 2>/dev/null | head -1 | grep . || printf 'mfs-mw\n'
}

# stack_modules [M]: the stack modules (services/* with a Dockerfile, also in the stack file),
# or only M.
stack_modules() {
  local mod
  for mod in $(selected_modules "${1:-}"); do
    case "$mod" in services/*) ;; *) [ -n "${1:-}" ] && die "$mod is not a stack module (services/*)"; continue ;; esac
    [ -f "$MW_ROOT/$mod/Dockerfile" ] || continue
    printf '%s\n' "$mod"
  done
}
