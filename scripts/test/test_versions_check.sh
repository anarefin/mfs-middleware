# make versions-check: version drift between module version catalogs.

test_same_versions_report_no_drift() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" versions-check
  assert_out_contains "no version drift"
}

test_reports_drift_between_two_modules() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  sed -i.bak 's/^spring-boot = "4.0.0"/spring-boot = "4.0.1"/' services/mw-b/gradle/libs.versions.toml
  assert_fails mw_make "$PWD" versions-check
  assert_out_contains "DRIFT spring-boot"
  assert_out_contains "services/mw-a=4.0.0"
  assert_out_contains "services/mw-b=4.0.1"
  assert_out_lacks "DRIFT camel"
}

test_keys_present_in_only_one_module_are_not_drift() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  printf '[versions]\njsonata = "2.0.6"\nspring-boot = "4.0.0"\ncamel = "4.14.0"\n' >services/mw-b/gradle/libs.versions.toml
  assert_ok mw_make "$PWD" versions-check
}

test_ignores_comments_and_other_tables() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  printf '[versions]\nspring-boot = "4.0.0" # pinned\ncamel = "4.14.0"\n\n[libraries]\nx = { module = "a:b", version = "9" }\n' >services/mw-b/gradle/libs.versions.toml
  printf '[versions]\nspring-boot = "4.0.0"\ncamel = "4.14.0"\n\n[libraries]\nx = { module = "a:b", version = "1" }\n' >services/mw-a/gradle/libs.versions.toml
  assert_ok mw_make "$PWD" versions-check
}

test_M_reports_only_drift_involving_that_module() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  new_module "$PWD" services/mw-c
  sed -i.bak 's/^camel = .*/camel = "4.15.0"/' services/mw-b/gradle/libs.versions.toml
  # drift between mw-a and mw-c on a key mw-b does not use
  printf '[versions]\nspring-boot = "4.0.0"\ncamel = "4.14.0"\njsonata = "2.0.6"\n' >services/mw-a/gradle/libs.versions.toml
  printf '[versions]\nspring-boot = "4.0.0"\ncamel = "4.14.0"\njsonata = "2.1.0"\n' >services/mw-c/gradle/libs.versions.toml
  assert_fails mw_make "$PWD" versions-check M=mw-b
  assert_out_contains "DRIFT camel"
  assert_out_lacks "DRIFT jsonata"
  assert_fails mw_make "$PWD" versions-check
  assert_out_contains "DRIFT jsonata"
}

test_no_modules_reports_nothing_to_compare() {
  assert_ok mw_make "$PWD" versions-check
}
