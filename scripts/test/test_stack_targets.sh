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
