# make up / e2e / perf / security / new-service: dispatch for all modules or one (M=).
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

test_up_with_no_stack_file_is_a_noop() {
  stub_tools
  assert_ok mw_make "$PWD" up
  assert_out_contains "nothing to start"
}

test_up_starts_the_core_profile_and_waits_without_building() {
  # Images are reused: make up never runs Gradle and never passes --build (make build does that).
  stub_tools
  stack_file mw-a
  new_module "$PWD" services/mw-a; touch services/mw-a/Dockerfile
  assert_ok mw_make "$PWD" up
  grep -qF "docker $(F) --profile core up -d --wait" .tool-calls || fail "core profile not started: $(cat .tool-calls)"
  grep -q -- '--build' .tool-calls && fail "make up rebuilt images: $(cat .tool-calls)"
  [ -f services/mw-a/.gradlew-calls ] && fail "make up ran Gradle"
  return 0
}

test_up_with_PROFILE_starts_that_profile_and_rejects_unknown_ones() {
  stub_tools
  stack_file mw-a
  assert_ok mw_make "$PWD" up PROFILE=minimal
  grep -qF -- "--profile minimal up -d --wait" .tool-calls || fail "minimal not started: $(cat .tool-calls)"
  rm -f .tool-calls
  assert_fails mw_make "$PWD" up PROFILE=everything
  assert_out_contains "PROFILE"
  [ -f .tool-calls ] && grep -q ' up ' .tool-calls && fail "started with an unknown profile"
  return 0
}

test_up_with_TIMERS_fast_adds_the_test_timers_env_file() {
  stub_tools
  stack_file mw-a
  assert_ok mw_make "$PWD" up TIMERS=fast
  grep -qF "docker $(F) --env-file $PWD/deploy/test-timers.env --profile core up -d --wait" .tool-calls \
    || fail "test timers not loaded: $(cat .tool-calls)"
  assert_fails mw_make "$PWD" up TIMERS=slow
  assert_out_contains "TIMERS"
}

test_up_with_M_starts_that_service_and_its_dependencies() {
  stub_tools
  stack_file mw-a-init mw-a mw-b
  new_module "$PWD" services/mw-a
  assert_ok mw_make "$PWD" up M=mw-a
  grep -F -- "--profile core up -d --wait" .tool-calls | grep -q " mw-a$" || fail "mw-a not started: $(cat .tool-calls)"
  grep -q -- '--no-deps' .tool-calls && fail "M= must start the dependencies too"
  return 0
}

test_up_with_M_fails_when_the_module_is_not_in_the_stack_file() {
  stub_tools
  stack_file mw-b
  new_module "$PWD" services/mw-a
  assert_fails mw_make "$PWD" up M=mw-a
  assert_out_contains "deploy/docker-compose.yml"
}

test_up_waits_for_long_running_services_then_runs_the_one_shots_nothing_depends_on() {
  # `up --wait` fails on a one-shot that exits unless a started service depends on it
  # (apisix-init): name the long-running services, then run and wait for the dangling one-shots.
  command -v jq >/dev/null 2>&1 || return 0
  mkdir -p .bin
  cat >.bin/docker <<EOF
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *"config --format json"*) cat <<'J'
{"services":{"db":{},"db-init":{"labels":{"mw.oneshot":"true"}},"svc":{"depends_on":{"db-init":{}}},
 "edge-init":{"labels":{"mw.oneshot":"true"},"depends_on":{"db":{}}}}}
J
  ;;
esac
exit 0
EOF
  chmod +x .bin/docker; PATH="$PWD/.bin:$PATH"
  stack_file db db-init svc edge-init
  assert_ok mw_make "$PWD" up
  grep -q -- "up -d --wait --wait-timeout 300 db svc$" .tool-calls || fail "long-running services not named: $(cat .tool-calls)"
  grep -q -- "up -d edge-init$" .tool-calls || fail "dangling one-shot not started: $(cat .tool-calls)"
  grep -q -- " wait edge-init$" .tool-calls || fail "dangling one-shot exit not checked: $(cat .tool-calls)"
  grep -q -- "db-init" .tool-calls && fail "a one-shot with a dependant was started on its own"
  return 0
}

test_up_fails_when_compose_fails() {
  mkdir -p .bin; printf '#!/bin/sh\nexit 1\n' >.bin/docker; chmod +x .bin/docker; PATH="$PWD/.bin:$PATH"
  stack_file mw-a
  assert_fails mw_make "$PWD" up
}

test_build_builds_every_stack_jar_then_the_images() {
  stub_tools
  stack_file mw-a-init mw-a mw-b
  new_module "$PWD" services/mw-a; touch services/mw-a/Dockerfile
  new_module "$PWD" services/mw-b; touch services/mw-b/Dockerfile
  new_module "$PWD" templates/mw-service-template; touch templates/mw-service-template/Dockerfile
  assert_ok mw_make "$PWD" build
  grep -q '^bootJar' services/mw-a/.gradlew-calls || fail "mw-a jar not built"
  grep -q '^bootJar' services/mw-b/.gradlew-calls || fail "mw-b jar not built"
  [ -f templates/mw-service-template/.gradlew-calls ] && fail "the template is not a stack module"
  grep -qF "docker $(F) --profile minimal --profile core --profile seed build" .tool-calls || fail "images not built: $(cat .tool-calls)"
}

test_build_with_M_builds_only_that_jar_and_its_images() {
  stub_tools
  stack_file mw-a-init mw-a mw-b
  new_module "$PWD" services/mw-a; touch services/mw-a/Dockerfile
  new_module "$PWD" services/mw-b; touch services/mw-b/Dockerfile
  assert_ok mw_make "$PWD" build M=mw-a
  grep -q '^bootJar' services/mw-a/.gradlew-calls || fail "mw-a jar not built"
  [ -f services/mw-b/.gradlew-calls ] && fail "M=mw-a also built mw-b"
  grep -qF -- "build mw-a-init mw-a" .tool-calls || fail "mw-a images not built: $(cat .tool-calls)"
}

test_build_runs_JOBS_gradle_builds_at_a_time_and_stops_before_the_images_on_a_failure() {
  stub_tools
  stack_file mw-a mw-b
  new_module "$PWD" services/mw-a; touch services/mw-a/Dockerfile
  new_module "$PWD" services/mw-b; touch services/mw-b/Dockerfile services/mw-b/.gradlew-fail
  assert_fails mw_make "$PWD" build JOBS=2
  assert_out_contains "mw-b"
  grep -q '^bootJar' services/mw-a/.gradlew-calls || fail "mw-a not built next to the failing mw-b"
  grep -q ' build' .tool-calls 2>/dev/null && fail "images built after a failed jar build"
  assert_fails mw_make "$PWD" build JOBS=0
  assert_out_contains "JOBS"
}

test_seed_runs_the_seeder_one_shot_and_fails_when_it_does() {
  stub_tools
  stack_file mw-a mw-seeder
  assert_ok mw_make "$PWD" seed
  grep -qF -- "--profile core --profile seed up -d mw-seeder" .tool-calls || fail "seeder not started: $(cat .tool-calls)"
  grep -qF -- "--profile core --profile seed wait mw-seeder" .tool-calls || fail "seeder exit not checked: $(cat .tool-calls)"
  printf '#!/bin/sh\ncase "$*" in *" wait "*) exit 3 ;; esac\nexit 0\n' >.bin/docker
  assert_fails mw_make "$PWD" seed
  assert_out_contains "mw-seeder"
}

test_seed_without_a_seeder_says_so_and_does_nothing() {
  stub_tools
  stack_file mw-a
  assert_ok mw_make "$PWD" seed
  assert_out_contains "no mw-seeder"
  [ -f .tool-calls ] && grep -q ' up ' .tool-calls && fail "started something"
  return 0
}

test_down_stops_every_profile_and_reset_drops_the_volumes_too() {
  stub_tools
  stack_file mw-a
  assert_ok mw_make "$PWD" down
  grep -qF "docker $(F) --profile minimal --profile core --profile seed down" .tool-calls || fail "down: $(cat .tool-calls)"
  rm -f .tool-calls
  assert_ok mw_make "$PWD" down DOWN_ARGS=-v
  grep -qF -- "down -v" .tool-calls || fail "DOWN_ARGS not passed: $(cat .tool-calls)"
  rm -f .tool-calls
  assert_ok mw_make "$PWD" reset
  grep -qF "docker $(F) --profile minimal --profile core --profile seed down -v --remove-orphans" .tool-calls \
    || fail "reset: $(cat .tool-calls)"
}

test_e2e_with_no_suite_is_a_noop() {
  assert_ok mw_make "$PWD" e2e
  assert_out_contains "no e2e suite"
  new_module "$PWD" services/mw-a
  assert_ok mw_make "$PWD" e2e M=mw-a
}

test_e2e_runs_suite_filtered_by_module_tag() {
  new_module "$PWD" tests/e2e
  assert_ok mw_make "$PWD" e2e
  grep -q '^test' tests/e2e/.gradlew-calls || fail "suite not run"
  new_module "$PWD" services/mw-a
  rm tests/e2e/.gradlew-calls
  assert_ok mw_make "$PWD" e2e M=mw-a TAGS=@p2p
  grep -q -- '-Dcucumber.filter.tags=@mw-a and (@p2p)' tests/e2e/.gradlew-calls || fail "tags not passed: $(cat tests/e2e/.gradlew-calls)"
}

test_e2e_enables_the_test_only_gateway_routes_on_the_running_stack_first() {
  # The test routes (deploy/apisix/routes-test) exist only while apisix-init last ran with
  # MW_E2E_ROUTES=1: make e2e re-runs it with the flag and waits for it, then runs the suite.
  mkdir -p .bin deploy && touch deploy/docker-compose.yml
  cat >.bin/docker <<EOF
#!/bin/sh
echo "MW_E2E_ROUTES=\${MW_E2E_ROUTES:-} key=\${MW_E2E_API_KEY:+set} docker \$*" >> "$PWD/.tool-calls"
exit 0
EOF
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
  new_module "$PWD" tests/e2e
  assert_ok mw_make "$PWD" e2e TAGS=@p2p
  grep -q '^MW_E2E_ROUTES=1 key=set docker compose .*up -d --no-deps --force-recreate apisix-init' .tool-calls \
    || fail "apisix-init not re-run with MW_E2E_ROUTES=1: $(cat .tool-calls)"
  grep -q 'docker compose .*wait apisix-init' .tool-calls || fail "did not wait for apisix-init: $(cat .tool-calls)"
  grep -q '^test' tests/e2e/.gradlew-calls || fail "suite not run"
}

test_e2e_fails_when_the_test_routes_cannot_be_enabled() {
  mkdir -p .bin deploy && touch deploy/docker-compose.yml
  printf '#!/bin/sh\ncase "$*" in *wait*) exit 1 ;; esac\nexit 0\n' >.bin/docker
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
  new_module "$PWD" tests/e2e
  assert_fails mw_make "$PWD" e2e
  assert_out_contains "apisix-init"
  [ -f tests/e2e/.gradlew-calls ] && fail "suite ran without its routes"
  return 0
}

test_perf_runs_gatling_suite() {
  new_module "$PWD" tests/perf
  assert_ok mw_make "$PWD" perf
  grep -q 'gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
  grep -q '^perfOverhead' tests/perf/.gradlew-calls || fail "overhead report not run: $(cat tests/perf/.gradlew-calls)"
}

test_perf_reports_the_overhead_even_when_gatling_fails_and_then_fails() {
  # A failed Gatling assertion is a finding: the overhead breakdown must still be written.
  new_module "$PWD" tests/perf
  touch tests/perf/.gradlew-fail
  assert_fails mw_make "$PWD" perf
  grep -q '^gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
  grep -q '^perfOverhead' tests/perf/.gradlew-calls || fail "overhead report skipped after a failed run"
}

test_perf_passes_extra_settings_to_both_runs() {
  new_module "$PWD" tests/perf
  MW_PERF_ARGS="-Dmw.perf.steady=10m -Dmw.perf.tps=150" mw_make "$PWD" perf >/dev/null 2>&1 || fail "perf failed"
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
  new_module "$PWD" tests/perf
  assert_ok mw_make "$PWD" perf
  grep -q '^MW_E2E_ROUTES=1 docker compose .*up -d --no-deps --force-recreate apisix-init' .tool-calls \
    || fail "apisix-init not re-run with MW_E2E_ROUTES=1: $(cat .tool-calls 2>/dev/null)"
  grep -q '^gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
}

test_e2e_with_unknown_M_fails() {
  assert_fails mw_make "$PWD" e2e M=mw-nope
  assert_out_contains "unknown module"
}

test_security_scans_every_module_and_the_repo() {
  stub_tools
  new_module "$PWD" services/mw-a
  assert_ok mw_make "$PWD" security
  grep -q "gitleaks" .tool-calls || fail "no secret scan"
  grep -q "trivy fs" .tool-calls || fail "no dependency scan"
  grep -q 'cyclonedxBom' services/mw-a/.gradlew-calls || fail "no SBOM"
}

test_security_with_M_scans_only_that_module() {
  stub_tools
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" security M=mw-b
  grep -q "services/mw-b" .tool-calls || fail "mw-b not scanned"
  if grep -q "services/mw-a" .tool-calls; then fail "mw-a scanned"; fi
}

test_security_fails_when_a_scanner_is_missing() {
  mkdir -p .bin && ln -s "$(command -v sh)" .bin/sh
  OUT="$(PATH="$PWD/.bin:/usr/bin:/bin" make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 security 2>&1)" && fail "passed without scanners"
  assert_out_contains "gitleaks"
}

test_new_service_requires_a_name() {
  assert_fails mw_make "$PWD" new-service
  assert_out_contains "NAME"
}

test_new_service_fails_until_the_template_exists() {
  assert_fails mw_make "$PWD" new-service NAME=mw-x
  assert_out_contains "templates/mw-service-template"
}

test_new_service_delegates_to_the_template_scaffolder() {
  mkdir -p templates/mw-service-template
  printf '#!/bin/sh\necho "$@" > "%s/.scaffold-args"\n' "$PWD" >templates/mw-service-template/scaffold.sh
  chmod +x templates/mw-service-template/scaffold.sh
  assert_ok mw_make "$PWD" new-service NAME=mw-x
  grep -q "mw-x $PWD/services/mw-x" .scaffold-args || fail "scaffolder not called: $(cat .scaffold-args)"
}

test_new_service_rejects_bad_names_and_existing_modules() {
  mkdir -p templates/mw-service-template
  printf '#!/bin/sh\n' >templates/mw-service-template/scaffold.sh
  chmod +x templates/mw-service-template/scaffold.sh
  assert_fails mw_make "$PWD" new-service NAME=Bad_Name
  new_module "$PWD" services/mw-x
  assert_fails mw_make "$PWD" new-service NAME=mw-x
  assert_out_contains "exists"
}

# ---- make compose-check: memory budget of deploy/docker-compose.yml (real `docker compose config`) ----

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
  assert_ok mw_make "$PWD" compose-check
  assert_out_contains "896 MiB of 10240 MiB"
}

test_compose_check_fails_over_the_budget_or_without_a_limit() {
  have_compose || return 0
  budget_stack
  OUT="$(MW_RAM_BUDGET_MIB=800 make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 compose-check 2>&1)" \
    && fail "896 MiB accepted under an 800 MiB budget"
  assert_out_contains "over the 800 MiB budget"
  sed -i.bak '/memory: 512M/d' deploy/docker-compose.yml
  assert_fails mw_make "$PWD" compose-check
  assert_out_contains "without a memory limit: mw-a"
}

test_compose_check_passes_on_the_repo_stack_file() {
  have_compose || return 0
  mkdir -p deploy && cp "$REPO_DIR/deploy/docker-compose.yml" deploy/
  assert_ok mw_make "$PWD" compose-check
  assert_out_contains "of 10240 MiB"
}
