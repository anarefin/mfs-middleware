# scripts/versions-check.sh: version drift between the inline versions of the modules' build files.

# bump MODULE OLD NEW: rewrite one inline version in services/MODULE/build.gradle.kts.
bump() { sed -i.bak "s|$2|$3|" "services/$1/build.gradle.kts"; }

test_same_versions_report_no_drift() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_run "$PWD" versions-check.sh
  assert_out_contains "no version drift"
}

test_reports_plugin_drift_between_two_modules() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  bump mw-b 'version "4.0.0"' 'version "4.0.1"'
  assert_fails mw_run "$PWD" versions-check.sh
  assert_out_contains "DRIFT plugin org.springframework.boot"
  assert_out_contains "services/mw-a=4.0.0"
  assert_out_contains "services/mw-b=4.0.1"
  assert_out_lacks "camel"
}

test_reports_dependency_and_platform_drift() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  bump mw-b 'camel-spring-boot-bom:4.14.0' 'camel-spring-boot-bom:4.15.0'
  assert_fails mw_run "$PWD" versions-check.sh
  assert_out_contains "DRIFT org.apache.camel.springboot:camel-spring-boot-bom"
  assert_out_contains "services/mw-b=4.15.0"
}

test_template_is_compared_too() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" templates/mw-service-template
  sed -i.bak 's|junit-jupiter:5.13.4|junit-jupiter:5.14.0|' templates/mw-service-template/build.gradle.kts
  assert_fails mw_run "$PWD" versions-check.sh
  assert_out_contains "templates/mw-service-template=5.14.0"
}

test_coordinates_in_only_one_module_are_not_drift() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  echo 'dependencies { implementation("com.dashjoin:jsonata:0.9.8") }' >>services/mw-b/build.gradle.kts
  assert_ok mw_run "$PWD" versions-check.sh
}

test_ignores_comment_lines_and_versionless_coordinates() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  printf '// was org.junit.jupiter:junit-jupiter:5.0.0\n' >>services/mw-b/build.gradle.kts
  echo 'dependencies { implementation("org.apache.camel.springboot:camel-kafka-starter") }' >>services/mw-b/build.gradle.kts
  assert_ok mw_run "$PWD" versions-check.sh
}

test_M_reports_only_drift_involving_that_module() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  new_module "$PWD" services/mw-c
  bump mw-b 'camel-spring-boot-bom:4.14.0' 'camel-spring-boot-bom:4.15.0'
  # drift between mw-a and mw-c on a coordinate mw-b does not use
  echo 'dependencies { implementation("com.dashjoin:jsonata:0.9.8") }' >>services/mw-a/build.gradle.kts
  echo 'dependencies { implementation("com.dashjoin:jsonata:0.9.9") }' >>services/mw-c/build.gradle.kts
  assert_fails mw_run "$PWD" versions-check.sh mw-b
  assert_out_contains "DRIFT org.apache.camel"
  assert_out_lacks "DRIFT com.dashjoin"
  assert_fails mw_run "$PWD" versions-check.sh
  assert_out_contains "DRIFT com.dashjoin:jsonata"
}

test_no_modules_reports_nothing_to_compare() {
  assert_ok mw_run "$PWD" versions-check.sh
}

test_runs_on_the_repo_build_files() {
  # report only: it runs on the real repo and must not crash
  OUT="$("$REPO_DIR/scripts/versions-check.sh" 2>&1)" || case "$OUT" in *DRIFT*) ;; *) fail "versions-check crashed" ;; esac
}
