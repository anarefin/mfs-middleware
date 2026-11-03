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

test_up_returns_once_every_long_running_service_is_healthy() {
  stub_compose_ps
  printf 'kafka\trunning\tstarting\nkafka-init\trunning\t\npostgres\trunning\thealthy\n' >.ps-1
  printf 'kafka\trunning\thealthy\nkafka-init\texited\t\netcd-init\texited\t\npostgres\trunning\thealthy\n' >.ps-2
  OUT="$(MW_UP_TIMEOUT=30 MW_UP_POLL=0 make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 up 2>&1)" || fail "up failed"
  [ "$(cat .ps-n)" = 2 ] || fail "did not poll until healthy (polls: $(cat .ps-n))"
  assert_out_contains "healthy"
}

test_up_fails_naming_the_unhealthy_services_on_timeout() {
  stub_compose_ps
  printf 'kafka\trunning\thealthy\nkeycloak\trunning\tunhealthy\napisix\trunning\tstarting\nkafka-init\texited\t\nopenbao\texited\t\n' >.ps-1
  if OUT="$(MW_UP_TIMEOUT=1 MW_UP_POLL=0 make -s -f "$REPO_DIR/Makefile" MW_ROOT="$PWD" SELFTEST=0 up 2>&1)"; then
    fail "up succeeded with unhealthy services"
  fi
  assert_out_contains "keycloak"; assert_out_contains "apisix"; assert_out_contains "openbao"
  assert_out_lacks "kafka-init"
  case "$OUT" in *timed*out*|*timeout*) ;; *) fail "no timeout message" ;; esac
  printf '%s\n' "$OUT" | grep -i 'unhealthy\|not healthy' | grep -q 'kafka,\|kafka ' && fail "healthy kafka named as unhealthy"
  return 0
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

test_perf_runs_gatling_suite() {
  new_module "$PWD" tests/perf
  assert_ok mw_make "$PWD" perf
  grep -q 'gatlingRun' tests/perf/.gradlew-calls || fail "gatling not run"
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
