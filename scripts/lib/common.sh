# Small helpers shared by the scripts in scripts/ (bash 3.2 compatible). Sourced, never run.
#
# MW_ROOT is the repo root (defaults to the folder holding scripts/; tests point it at a fixture).
#
# Modules: every services/* folder with a build.gradle.kts is a subproject of the root build
# (settings.gradle.kts includes it), run as `./gradlew :mw-x:<task>`. templates/mw-service-template
# has a build.gradle.kts too but is never included; the repo checks (isolation, field injection,
# versions) still look at it, because new services are copied from it.

MW_ROOT="${MW_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)}"
MODULE_PARENTS="services templates"

die() { printf 'error: %s\n' "$*" >&2; exit 2; }
info() { printf '==> %s\n' "$*"; }

# all_modules: print every module dir relative to MW_ROOT (services/mw-x, templates/...), sorted.
all_modules() {
  local parent d
  for parent in $MODULE_PARENTS; do
    for d in "$MW_ROOT/$parent"/*/; do
      [ -d "$d" ] || continue
      d="${d%/}"
      [ -f "$d/build.gradle.kts" ] && printf '%s\n' "${d#"$MW_ROOT"/}"
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
# every profile of the stack file (deploy/reset.sh and the log scan cover them all)
STACK_PROFILES="minimal core seed tester"

# stack_compose [PROFILE...]: set DC_F to the docker compose arguments for the stack: the file, the
# generated deploy/.env, with TIMERS=fast also deploy/test-timers.env, then --profile for each PROFILE
# (default core). The scripts that use it only read the stack (ps, logs, exec, cp) or (re)start a
# one-shot with --no-deps, so they never recreate a service: TIMERS matters only when a script starts
# services, and then it must match the `up` that started the stack (TIMERS=fast for
# `--env-file test-timers.env`).
# Use as: stack_compose; docker compose "${DC_F[@]}" ...
stack_compose() {
  [ -f "$STACK_FILE" ] || die "deploy/docker-compose.yml does not exist"
  DC_F=(-f "$STACK_FILE")
  # gen-dev-env.sh writes it; a read-only call on a checkout without it still works
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

# stack_modules [M]: the stack modules (services/* with a Dockerfile), or only M.
stack_modules() {
  local mod
  for mod in $(selected_modules "${1:-}"); do
    case "$mod" in services/*) ;; *) [ -n "${1:-}" ] && die "$mod is not a stack module (services/*)"; continue ;; esac
    [ -f "$MW_ROOT/$mod/Dockerfile" ] || continue
    printf '%s\n' "$mod"
  done
}

# stack_seed_manifest: copy mw-seeder's manifest (seeded ids and personas, no secret) out of its
# container (the seed-manifest volume; an exited container still has it) to build/seed/manifest.json
# (MW_SEED_MANIFEST overrides), for the API and Playwright suites. The seed profile's mw-seeder runs
# with `docker compose ... --profile seed up -d` and must exit 0 first. Read-only on the stack.
# Never fails: without the container an existing copy stays; the suites fail on a missing manifest.
stack_seed_manifest() {
  local manifest="${MW_SEED_MANIFEST:-$MW_ROOT/build/seed/manifest.json}" saved=("${DC_F[@]+"${DC_F[@]}"}")
  [ -f "$STACK_FILE" ] && command -v docker >/dev/null 2>&1 || return 0
  mkdir -p "$(dirname "$manifest")"
  stack_compose core seed
  if docker compose "${DC_F[@]}" cp mw-seeder:/run/mw/seed/manifest.json "$manifest" >/dev/null 2>&1; then
    info "seed manifest: copied from mw-seeder to ${manifest#"$MW_ROOT"/}"
  elif [ -f "$manifest" ]; then
    info "seed manifest: no mw-seeder container, keeping ${manifest#"$MW_ROOT"/}"
  fi
  DC_F=("${saved[@]+"${saved[@]}"}")
  return 0
}
