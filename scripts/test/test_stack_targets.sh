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

test_up_with_no_stack_and_no_modules_is_a_noop() {
  stub_tools
  assert_ok mw_make "$PWD" up
  assert_out_contains "nothing to start"
}

test_up_always_rebuilds_service_jars_and_images_unless_opted_out() {
  # Never run stale code: an existing jar or an existing mw-x:dev / mw-x-init:dev image may
  # predate the source. Every stack module's bootJar runs (Gradle is incremental) and compose
  # builds every image (layer cache) on each make up; MW_UP_NO_BUILD=1 skips both.
  stub_tools
  mkdir -p deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
  new_module "$PWD" services/mw-a; touch services/mw-a/Dockerfile services/mw-a/compose.yml
  new_module "$PWD" services/mw-b; touch services/mw-b/Dockerfile services/mw-b/compose.yml
  mkdir -p services/mw-b/build/libs && touch services/mw-b/build/libs/mw-b-0.1.0.jar
  assert_ok mw_make "$PWD" up
  grep -q '^bootJar' services/mw-a/.gradlew-calls 2>/dev/null || fail "mw-a jar not built"
  grep -q '^bootJar' services/mw-b/.gradlew-calls 2>/dev/null || fail "mw-b jar not refreshed although one exists"
  grep -q "compose -f $PWD/deploy/compose/mvp/compose.yml up -d --build" .tool-calls || fail "images not rebuilt: $(cat .tool-calls)"
  # --build given once even when COMPOSE_ARGS has it too
  rm -f .tool-calls
  assert_ok mw_make "$PWD" up COMPOSE_ARGS=--build
  [ "$(grep -o -- '--build' .tool-calls | wc -l | tr -d ' ')" = 1 ] || fail "--build repeated: $(cat .tool-calls)"
  # M= rebuilds that module only
  rm -f .tool-calls services/mw-*/.gradlew-calls
  assert_ok mw_make "$PWD" up M=mw-a
  grep -q '^bootJar' services/mw-a/.gradlew-calls 2>/dev/null || fail "M=mw-a jar not built"
  [ -f services/mw-b/.gradlew-calls ] && fail "M=mw-a also built mw-b"
  grep -q "compose -f $PWD/services/mw-a/compose.yml up -d --build" .tool-calls || fail "M=mw-a image not rebuilt"
  # opt-out
  rm -f .tool-calls services/mw-*/.gradlew-calls
  MW_UP_NO_BUILD=1 mw_make "$PWD" up || fail "up with MW_UP_NO_BUILD=1 failed: $OUT"
  [ -f services/mw-a/.gradlew-calls ] && fail "MW_UP_NO_BUILD=1 still built jars"
  grep -q -- '--build' .tool-calls && fail "MW_UP_NO_BUILD=1 still rebuilt images"
  # a failing build stops make up before compose
  rm -f services/mw-a/.gradlew-calls .tool-calls; rm -rf services/mw-a/build; touch services/mw-a/.gradlew-fail
  assert_fails mw_make "$PWD" up
  assert_out_contains "mw-a"
  grep -q 'compose' .tool-calls 2>/dev/null && fail "compose ran after a failed jar build"
  return 0
}

test_up_starts_the_mvp_compose_project() {
  stub_tools
  mkdir -p deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
  assert_ok mw_make "$PWD" up
  grep -q "docker compose -f $PWD/deploy/compose/mvp/compose.yml up -d" .tool-calls || fail "mvp project not started: $(cat .tool-calls)"
}

# stub_compose_ps: docker stub whose `compose ... ps` prints .ps-<n> on the n-th call
# (the last file once they run out); every other call is recorded and succeeds.
stub_compose_ps() {
  mkdir -p .bin
  cat >.bin/docker <<EOF
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *" ps "*|*" ps") n=\$(( \$(cat "$PWD/.ps-n" 2>/dev/null || echo 0) + 1 )); echo \$n > "$PWD/.ps-n"
    f="$PWD/.ps-\$n"; [ -f "\$f" ] || f="\$(ls "$PWD"/.ps-[0-9]* | sort -t- -k2 -n | tail -1)"; cat "\$f" ;;
esac
exit 0
EOF
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
  mkdir -p deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
}

# ps lines as up.sh asks for them: service, state, health, exit code, labels. One-shot init
# containers carry the compose label mw.oneshot=true.
L='com.docker.compose.project=mvp'
O="$L,mw.oneshot=true"

# up_with TIMEOUT: run make up against the stubbed docker; output in OUT, status returned.
up_with() {
  OUT="$(MW_UP_TIMEOUT="$1" MW_UP_POLL=0 make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 up 2>&1)"
}

test_up_returns_once_every_long_running_service_is_healthy() {
  stub_compose_ps
  printf 'kafka\trunning\tstarting\t0\t%s\nkafka-init\trunning\t\t0\t%s\npostgres\trunning\thealthy\t0\t%s\n' "$L" "$O" "$L" >.ps-1
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\texited\t\t0\t%s\netcd-init\texited\t\t0\t%s\npostgres\trunning\thealthy\t0\t%s\n' "$L" "$O" "$O" "$L" >.ps-2
  up_with 30 || fail "up failed: $OUT"
  [ "$(cat .ps-n)" = 2 ] || fail "did not poll until healthy (polls: $(cat .ps-n))"
  assert_out_contains "healthy"
}

test_up_waits_for_running_one_shots_to_finish() {
  # Services report healthy before kafka-init has created the topics they use: up must not
  # return until every one-shot has exited (0).
  stub_compose_ps
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\trunning\t\t0\t%s\nnew-init\tcreated\t\t0\t%s\n' "$L" "$O" "$O" >.ps-1
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\texited\t\t0\t%s\nnew-init\texited\t\t0\t%s\n' "$L" "$O" "$O" >.ps-2
  up_with 30 || fail "up failed: $OUT"
  [ "$(cat .ps-n)" = 2 ] || fail "returned while one-shots were still running (polls: $(cat .ps-n))"
  rm -f .ps-*
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\trunning\t\t0\t%s\n' "$L" "$O" >.ps-1
  if up_with 1; then fail "up succeeded with kafka-init still running"; fi
  assert_out_contains "kafka-init"
}

test_up_fails_naming_the_unhealthy_services_on_timeout() {
  stub_compose_ps
  printf 'kafka\trunning\thealthy\t0\t%s\nkeycloak\trunning\tstarting\t0\t%s\napisix\tcreated\t\t0\t%s\nkafka-init\texited\t\t0\t%s\n' "$L" "$L" "$L" "$O" >.ps-1
  if up_with 1; then fail "up succeeded with services still starting"; fi
  assert_out_contains "keycloak"; assert_out_contains "apisix"
  assert_out_lacks "kafka-init"
  case "$OUT" in *timed*out*|*timeout*) ;; *) fail "no timeout message" ;; esac
  printf '%s\n' "$OUT" | grep -i 'unhealthy\|not healthy' | grep -q 'kafka,\|kafka ' && fail "healthy kafka named as unhealthy"
  return 0
}

test_up_fails_fast_naming_a_long_running_service_that_exited_died_or_is_unhealthy() {
  local state health
  for state_health in exited: dead: running:unhealthy; do
    state="${state_health%%:*}"; health="${state_health#*:}"
    rm -f .ps-* .tool-calls
    stub_compose_ps
    printf 'kafka\trunning\tstarting\t0\t%s\nopenbao\t%s\t%s\t1\t%s\n' "$L" "$state" "$health" "$L" >.ps-1
    if up_with 5; then fail "up succeeded with openbao $state $health"; fi
    [ "$(cat .ps-n)" = 1 ] || fail "$state/$health: kept polling ($(cat .ps-n) polls) instead of failing fast"
    assert_out_contains "openbao"
    assert_out_contains "${health:-$state}"
    assert_out_lacks "timed out"
    printf '%s\n' "$OUT" | grep -q 'kafka(' && fail "still-starting kafka named as failed: $OUT"
  done
  return 0
}

test_up_fails_fast_naming_a_one_shot_that_exited_non_zero() {
  stub_compose_ps
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\texited\t\t1\t%s\netcd-init\texited\t\t0\t%s\napisix\trunning\tstarting\t0\t%s\n' "$L" "$O" "$O" "$L" >.ps-1
  if up_with 5; then fail "up succeeded with a failed one-shot"; fi
  [ "$(cat .ps-n)" = 1 ] || fail "kept polling ($(cat .ps-n) polls) instead of failing fast"
  assert_out_contains "kafka-init"
  assert_out_contains "exit 1"
  assert_out_lacks "etcd-init"
  assert_out_lacks "timed out"
}

test_up_reads_one_shots_from_the_compose_label_not_a_list() {
  # An exited service without the label is long-running (fails); one with it is a one-shot.
  stub_compose_ps
  printf 'kafka\trunning\thealthy\t0\t%s\nnew-init\texited\t\t0\t%s\n' "$L" "$O" >.ps-1
  up_with 30 || fail "labelled one-shot new-init exited 0 was not skipped: $OUT"
  rm -f .ps-*
  printf 'kafka\trunning\thealthy\t0\t%s\nkafka-init\texited\t\t0\t%s\n' "$L" "$L" >.ps-1
  if up_with 30; then fail "unlabelled exited kafka-init treated as a one-shot"; fi
  assert_out_contains "kafka-init"
}

test_up_with_M_starts_only_that_module() {
  stub_tools
  new_module "$PWD" services/mw-a
  touch services/mw-a/compose.yml
  assert_ok mw_make "$PWD" up M=mw-a
  grep -q "docker compose -f $PWD/services/mw-a/compose.yml up -d" .tool-calls || fail "module not started"
}

test_up_with_M_fails_when_module_has_no_compose_file() {
  stub_tools
  new_module "$PWD" services/mw-a
  assert_fails mw_make "$PWD" up M=mw-a
  assert_out_contains "compose.yml"
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
  mkdir -p .bin deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
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
  mkdir -p .bin deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
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
  mkdir -p .bin deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
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
