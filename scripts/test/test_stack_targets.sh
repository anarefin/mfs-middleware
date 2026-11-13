# The stack and suite scripts run directly (no Makefile): deploy/reset.sh, scripts/api-test.sh,
# tester.sh, tester-e2e.sh, suite.sh (perf), security.sh, new-service.sh, compose-check.sh and the
# seed-manifest copy of scripts/lib/common.sh. The stack itself starts with plain docker compose.
# docker, gitleaks and trivy are replaced by stubs on PATH that record their arguments.

stub_tools() {
  mkdir -p .bin
  for t in docker gitleaks trivy; do
    printf '#!/bin/sh\necho "%s $*" >> "%s/.tool-calls"\n' "$t" "$PWD" >".bin/$t"
    chmod +x ".bin/$t"
  done
  PATH="$PWD/.bin:$PATH"
}

# stack_file [SERVICE...]: a fixture deploy/docker-compose.yml naming SERVICE... (one per line, as
# the scripts look services up).
stack_file() {
  mkdir -p deploy
  { printf 'name: mfs-mw\nservices:\n'; for s in "$@"; do printf '  %s:\n    image: x\n' "$s"; done; } >deploy/docker-compose.yml
}

F() { printf 'compose -f %s/deploy/docker-compose.yml --env-file %s/deploy/.env' "$PWD" "$PWD"; }

# perf_suite: tests/perf, its own standalone Gradle build, with a stub wrapper that records its tasks.
perf_suite() {
  mkdir -p tests/perf
  cat >tests/perf/gradlew <<'EOF'
#!/bin/sh
echo "$*" >> "$(dirname "$0")/.gradlew-calls"
[ -f "$(dirname "$0")/.gradlew-fail" ] && exit 1
exit 0
EOF
  chmod +x tests/perf/gradlew
}

test_seed_manifest_is_copied_out_of_the_seeder_container() {
  # the replacement of the old seed step: the suites copy mw-seeder's manifest themselves
  stub_tools
  stack_file mw-seeder
  touch deploy/.env
  OUT="$(MW_ROOT="$PWD" bash -c '. "$1"; stack_seed_manifest' _ "$REPO_DIR/scripts/lib/common.sh" 2>&1)" || fail "stack_seed_manifest failed"
  grep -qF "docker $(F) --profile core --profile seed cp mw-seeder:/run/mw/seed/manifest.json $PWD/build/seed/manifest.json" .tool-calls \
    || fail "manifest not copied: $(cat .tool-calls)"
  grep -q 'stack_seed_manifest' "$REPO_DIR/tests/api/run.sh" || fail "tests/api/run.sh does not copy the manifest"
  grep -q 'stack_seed_manifest' "$REPO_DIR/scripts/ui-e2e.sh" || fail "ui-e2e.sh does not copy the manifest"
}

test_seed_manifest_copy_never_fails_without_a_stack() {
  OUT="$(MW_ROOT="$PWD" bash -c '. "$1"; stack_seed_manifest' _ "$REPO_DIR/scripts/lib/common.sh" 2>&1)" || fail "failed without a stack file"
}

test_TIMERS_fast_adds_the_test_timers_env_file_and_rejects_others() {
  stack_file mw-a
  touch deploy/.env
  OUT="$(MW_ROOT="$PWD" TIMERS=fast bash -c '. "$1"; stack_compose; echo "${DC_F[*]}"' _ "$REPO_DIR/scripts/lib/common.sh" 2>&1)"
  assert_out_contains "--env-file $PWD/deploy/test-timers.env --profile core"
  OUT="$(MW_ROOT="$PWD" TIMERS=slow bash -c '. "$1"; stack_compose' _ "$REPO_DIR/scripts/lib/common.sh" 2>&1)" && fail "TIMERS=slow accepted"
  assert_out_contains "TIMERS must be empty or fast"
}

test_deploy_reset_sh_drops_every_profile_from_any_directory() {
  # deploy/reset.sh (BitsPay's): docker compose down -v --remove-orphans for every profile, on its
  # own, from deploy/ or any other directory
  [ -x "$REPO_DIR/deploy/reset.sh" ] || fail "deploy/reset.sh missing or not executable"
  stub_tools
  stack_file mw-a
  local d
  for d in deploy /; do
    rm -f .tool-calls
    OUT="$(root="$PWD"; cd "$d" && MW_ROOT="$root" "$REPO_DIR/deploy/reset.sh" 2>&1)" || fail "reset.sh failed (from $d)"
    grep -qF "docker $(F) --profile minimal --profile core --profile seed --profile tester down -v --remove-orphans" .tool-calls \
      || fail "reset.sh (from $d): $(cat .tool-calls)"
  done
}

test_api_test_with_no_suite_is_a_noop() {
  assert_ok mw_run "$PWD" api-test.sh
  assert_out_contains "no API suite"
}

# plant_api_suite: a stub tests/api/run.sh that records its arguments.
plant_api_suite() {
  mkdir -p tests/api
  printf '#!/bin/sh\necho "$@" >> "$(dirname "$0")/.run-calls"\n[ -f "$(dirname "$0")/.fail" ] && exit 1\nexit 0\n' >tests/api/run.sh
  chmod +x tests/api/run.sh
}

test_api_test_runs_the_default_folders() {
  plant_api_suite
  assert_ok mw_run "$PWD" api-test.sh
  [ "$(cat tests/api/.run-calls)" = "" ] || fail "default run got arguments: $(cat tests/api/.run-calls)"
}

test_api_test_passes_folders_and_all() {
  plant_api_suite
  assert_ok mw_run "$PWD" api-test.sh "01, 05 09" 1
  [ "$(cat tests/api/.run-calls)" = "--folder 01 --folder 05 --folder 09 --all" ] \
    || fail "run.sh got: $(cat tests/api/.run-calls)"
}

test_api_test_fails_when_the_suite_fails() {
  plant_api_suite
  touch tests/api/.fail
  assert_fails mw_run "$PWD" api-test.sh
}

test_api_test_rejects_a_bad_ALL() {
  plant_api_suite
  assert_fails mw_run "$PWD" api-test.sh "" yes
  assert_out_contains "ALL must be empty or 1"
}

test_the_api_suite_enables_the_test_only_gateway_routes_on_the_running_stack_first() {
  # The test routes (deploy/apisix/routes-test) exist only while apisix-init last ran with
  # MW_E2E_ROUTES=1: tests/api/run.sh re-runs it with the flag and waits for it.
  grep -q 'MW_E2E_ROUTES=1 docker compose "${DC_F\[@\]}" up -d --no-deps --force-recreate apisix-init' "$REPO_DIR/tests/api/run.sh" \
    || fail "tests/api/run.sh does not re-run apisix-init with MW_E2E_ROUTES=1"
  grep -q 'docker compose "${DC_F\[@\]}" wait apisix-init' "$REPO_DIR/tests/api/run.sh" || fail "tests/api/run.sh does not wait for apisix-init"
}

test_tester_enables_the_test_routes_then_starts_the_console() {
  # The console's route is a test-only route: apisix-init re-runs with MW_E2E_ROUTES=1 first.
  stub_tools
  stack_file apisix-init mw-test-console
  assert_ok mw_run "$PWD" tester.sh
  grep -qF "docker $(F) --profile core --profile tester up -d --no-deps --force-recreate apisix-init" .tool-calls \
    || fail "test routes not re-applied: $(cat .tool-calls)"
  grep -qF "docker $(F) --profile core --profile tester wait apisix-init" .tool-calls || fail "apisix-init not awaited: $(cat .tool-calls)"
  grep -qF "docker $(F) --profile core --profile tester up -d --no-deps --wait --wait-timeout 120 mw-test-console" .tool-calls \
    || fail "console not started: $(cat .tool-calls)"
  grep -q -- '--build' .tool-calls && fail "tester.sh built an image: $(cat .tool-calls)"
  grep -q 'MW_E2E_ROUTES=1 docker compose' "$REPO_DIR/scripts/tester.sh" || fail "tester.sh does not set MW_E2E_ROUTES=1"
  assert_out_contains "https://localhost/tester"
}

test_tester_fails_without_the_console_in_the_stack_file() {
  stub_tools
  stack_file apisix-init
  assert_fails mw_run "$PWD" tester.sh
  assert_out_contains "no mw-test-console service"
}

test_tester_e2e_installs_then_runs_the_suite_against_the_edge() {
  mkdir -p ui/mw-test-console .bin
  printf '{"name":"mw-test-console"}\n' >ui/mw-test-console/package.json
  printf 'lockfileVersion: 9\n' >ui/mw-test-console/pnpm-lock.yaml
  {
    printf '#!/bin/sh\n'
    printf 'echo "pnpm $* | E2E_BASE_URL=$E2E_BASE_URL | cwd=$(pwd)" >> "%s/.tool-calls"\n' "$PWD"
    printf '[ "$1" = install ] && mkdir -p node_modules && touch node_modules/.modules.yaml\n'
    printf 'exit 0\n'
  } >.bin/pnpm
  chmod +x .bin/pnpm
  PATH="$PWD/.bin:$PATH"
  assert_ok mw_run "$PWD" tester-e2e.sh @ussd
  grep -q "^pnpm install --frozen-lockfile .*cwd=$PWD/ui/mw-test-console$" .tool-calls || fail "no frozen install: $(cat .tool-calls)"
  grep -qF "pnpm test:e2e --grep @ussd | E2E_BASE_URL=https://localhost |" .tool-calls || fail "suite not run: $(cat .tool-calls)"
}

test_perf_runs_gatling_suite() {
  perf_suite
  assert_ok mw_run "$PWD" suite.sh perf
  grep -q 'gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
  grep -q '^perfOverhead' tests/perf/.gradlew-calls || fail "overhead report not run: $(cat tests/perf/.gradlew-calls)"
}

test_perf_reports_the_overhead_even_when_gatling_fails_and_then_fails() {
  # A failed Gatling assertion is a finding: the overhead breakdown must still be written.
  perf_suite
  touch tests/perf/.gradlew-fail
  assert_fails mw_run "$PWD" suite.sh perf
  grep -q '^gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
  grep -q '^perfOverhead' tests/perf/.gradlew-calls || fail "overhead report skipped after a failed run"
}

test_perf_passes_extra_settings_to_both_runs() {
  perf_suite
  mw_run "$PWD" MW_PERF_ARGS="-Dmw.perf.steady=10m -Dmw.perf.tps=150" suite.sh perf >/dev/null 2>&1 || fail "perf failed"
  [ "$(grep -c -- '-Dmw.perf.steady=10m -Dmw.perf.tps=150' tests/perf/.gradlew-calls)" = 2 ] \
    || fail "settings not passed: $(cat tests/perf/.gradlew-calls)"
}

test_perf_enables_the_test_only_gateway_routes_first() {
  # The run's setup needs the core's PIN key and the SMSC simulator (binding OTPs): test routes.
  mkdir -p .bin deploy && touch deploy/docker-compose.yml
  cat >.bin/docker <<EOF
#!/bin/sh
echo "MW_E2E_ROUTES=\${MW_E2E_ROUTES:-} docker \$*" >> "$PWD/.tool-calls"
exit 0
EOF
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
  perf_suite
  assert_ok mw_run "$PWD" suite.sh perf
  grep -q '^MW_E2E_ROUTES=1 docker compose .*up -d --no-deps --force-recreate apisix-init' .tool-calls \
    || fail "apisix-init not re-run with MW_E2E_ROUTES=1: $(cat .tool-calls 2>/dev/null)"
  grep -q '^gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
}

test_perf_with_unknown_M_fails() {
  perf_suite
  assert_fails mw_run "$PWD" suite.sh perf mw-nope
  assert_out_contains "unknown module"
}

test_security_scans_every_module_and_the_repo() {
  stub_tools
  new_module "$PWD" services/mw-a
  root_gradlew
  assert_ok mw_run "$PWD" security.sh
  grep -q "gitleaks" .tool-calls || fail "no secret scan"
  grep -q "trivy fs" .tool-calls || fail "no dependency scan"
  grep -qx -- '-q :mw-a:bootJar' .gradlew-calls || fail "boot jar not built through the root build: $(cat .gradlew-calls)"
  grep -q "trivy rootfs .*--format cyclonedx .*services/mw-a/build/libs/mw-a.jar" .tool-calls || fail "no SBOM of the boot jar"
  grep -q "trivy sbom" .tool-calls || fail "SBOM not scanned"
}

test_security_with_M_scans_only_that_module() {
  stub_tools
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  root_gradlew
  assert_ok mw_run "$PWD" security.sh mw-b
  grep -q "services/mw-b" .tool-calls || fail "mw-b not scanned"
  if grep -q "services/mw-a" .tool-calls; then fail "mw-a scanned"; fi
}

test_security_fails_when_a_scanner_is_missing() {
  mkdir -p .bin && ln -s "$(command -v sh)" .bin/sh
  PATH="$PWD/.bin:/usr/bin:/bin" mw_run "$PWD" security.sh && fail "passed without scanners"
  assert_out_contains "gitleaks"
}

test_new_service_requires_a_name() {
  assert_fails mw_run "$PWD" new-service.sh
  assert_out_contains "usage: scripts/new-service.sh mw-<name>"
}

test_new_service_fails_until_the_template_exists() {
  assert_fails mw_run "$PWD" new-service.sh mw-x
  assert_out_contains "templates/mw-service-template"
}

test_new_service_delegates_to_the_template_scaffolder() {
  mkdir -p templates/mw-service-template
  printf '#!/bin/sh\necho "$@" > "%s/.scaffold-args"\n' "$PWD" >templates/mw-service-template/scaffold.sh
  chmod +x templates/mw-service-template/scaffold.sh
  assert_ok mw_run "$PWD" new-service.sh mw-x
  grep -q "mw-x $PWD/services/mw-x" .scaffold-args || fail "scaffolder not called: $(cat .scaffold-args)"
}

test_new_service_rejects_bad_names_and_existing_modules() {
  mkdir -p templates/mw-service-template
  printf '#!/bin/sh\n' >templates/mw-service-template/scaffold.sh
  chmod +x templates/mw-service-template/scaffold.sh
  assert_fails mw_run "$PWD" new-service.sh Bad_Name
  new_module "$PWD" services/mw-x
  assert_fails mw_run "$PWD" new-service.sh mw-x
  assert_out_contains "exists"
}

# ---- scripts/compose-check.sh (./gradlew :composeCheck): memory budget of deploy/docker-compose.yml (real `docker compose config`) ----

have_compose() {
  docker compose version >/dev/null 2>&1 && command -v node >/dev/null 2>&1 && return 0
  echo "    SKIP: docker compose CLI or node missing" >&2; return 1
}

# budget_stack: one long-running platform service, one one-shot, one profiled module
budget_stack() {
  mkdir -p deploy
  cat >deploy/docker-compose.yml <<'YML'
name: mfs-mw
services:
  db:
    image: postgres:18
    environment: { POSTGRES_PASSWORD: "${POSTGRES_PASSWORD:?run scripts/gen-dev-env.sh}" }
    deploy: { resources: { limits: { memory: 384M } } }
  db-init:
    image: alpine:3
    labels: { mw.oneshot: "true" }
    restart: "no"
  mw-a:
    profiles: [core]
    image: mfs-mw/mw-a:local
    deploy: { resources: { limits: { memory: 512M } } }
YML
}

test_compose_check_counts_every_profile_and_skips_one_shots() {
  have_compose || return 0
  budget_stack
  assert_ok mw_run "$PWD" compose-check.sh
  assert_out_contains "896 MiB of 8192 MiB"
}

test_compose_check_fails_over_the_budget_or_without_a_limit() {
  have_compose || return 0
  budget_stack
  mw_run "$PWD" MW_RAM_BUDGET_MIB=800 compose-check.sh && fail "896 MiB accepted under an 800 MiB budget"
  assert_out_contains "over the 800 MiB budget"
  sed -i.bak '/memory: 512M/d' deploy/docker-compose.yml
  assert_fails mw_run "$PWD" compose-check.sh
  assert_out_contains "without a memory limit: mw-a"
}

# knob_stack: the budget stack with its limits as ${KNOB:-default} (BitsPay pattern), plus a service
# of another profile (outside the core budget)
knob_stack() {
  mkdir -p deploy
  cat >deploy/docker-compose.yml <<'YML'
name: mfs-mw
services:
  db:
    image: postgres:18
    environment: { POSTGRES_PASSWORD: "${POSTGRES_PASSWORD:?run scripts/gen-dev-env.sh}" }
    deploy: { resources: { limits: { memory: "${POSTGRES_MEM:-384M}" } } }
  mw-a:
    profiles: [core]
    image: mfs-mw/mw-a:local
    deploy: { resources: { limits: { memory: "${APP_MEM_LIMIT:-512M}" } } }
  mw-tester:
    profiles: [tester]
    image: mfs-mw/mw-tester:local
    deploy: { resources: { limits: { memory: 4G } } }
YML
}

compose_check() { OUT="$(env -u APP_MEM_LIMIT -u POSTGRES_MEM MW_ROOT="$PWD" "$@" "$REPO_DIR/scripts/compose-check.sh" 2>&1)"; }

test_compose_check_sums_the_knob_defaults_and_skips_other_profiles() {
  have_compose || return 0
  knob_stack
  assert_ok compose_check
  assert_out_contains "896 MiB of 8192 MiB"
}

test_compose_check_respects_a_knob_from_the_shell_or_deploy_env() {
  have_compose || return 0
  knob_stack
  assert_ok compose_check APP_MEM_LIMIT=1G
  assert_out_contains "1408 MiB of 8192 MiB"
  printf 'POSTGRES_MEM=128M\n' >deploy/.env
  assert_ok compose_check
  assert_out_contains "640 MiB of 8192 MiB"
  # the shell wins over deploy/.env, as in Compose
  assert_ok compose_check POSTGRES_MEM=256M
  assert_out_contains "768 MiB of 8192 MiB"
  assert_fails compose_check APP_MEM_LIMIT=8G
  assert_out_contains "over the 8192 MiB budget"
}

test_compose_check_passes_on_the_repo_stack_file() {
  have_compose || return 0
  mkdir -p deploy && cp "$REPO_DIR/deploy/docker-compose.yml" deploy/
  assert_ok mw_run "$PWD" compose-check.sh
  assert_out_contains "of 8192 MiB"
}
