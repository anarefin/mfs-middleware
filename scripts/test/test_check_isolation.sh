# make check-isolation: module independence rules (spec 4.3, D13, D14).

# assert_violation [VAR=VAL...]: check-isolation fails *because of* a reported violation.
assert_violation() {
  assert_fails mw_make "$PWD" check-isolation "$@"
  assert_out_contains "ISOLATION"
}

test_comment_lines_are_ignored() {
  new_module "$PWD" services/mw-a
  printf '// never includeBuild("../x") here\n' >>services/mw-a/build.gradle.kts
  printf '# mwContractsDir=../../services/mw-b\n' >>services/mw-a/gradle.properties
  assert_ok mw_make "$PWD" check-isolation
}

test_clean_modules_pass() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  assert_ok mw_make "$PWD" check-isolation
}

test_no_modules_pass() {
  assert_ok mw_make "$PWD" check-isolation
}

test_contracts_dir_reference_is_allowed() {
  new_module "$PWD" services/mw-a
  cat >>services/mw-a/build.gradle.kts <<'EOF'
val contracts = file("../../contracts/events/mw-a")
tasks.test { systemProperty("contracts", "../../contracts") }
EOF
  assert_ok mw_make "$PWD" check-isolation
}

test_fails_on_cross_module_project_dependency() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  echo 'dependencies { implementation(project(":mw-b")) }' >>services/mw-a/build.gradle.kts
  assert_violation
  assert_out_contains "services/mw-a"
}

test_fails_on_dependency_on_an_mfs_mw_artifact() {
  new_module "$PWD" services/mw-a
  echo 'dependencies { implementation("mfs.mw:mw-b:0.1.0") }' >>services/mw-a/build.gradle.kts
  assert_violation
}

test_fails_on_mfs_mw_artifact_in_version_catalog() {
  new_module "$PWD" services/mw-a
  echo 'mwb = { module = "mfs.mw.adapter:mw-adapter-telco", version = "1" }' >>services/mw-a/gradle/libs.versions.toml
  assert_violation
}

test_fails_on_includeBuild_inside_a_module() {
  new_module "$PWD" services/mw-a
  echo 'includeBuild("../../templates/mw-service-template")' >>services/mw-a/settings.gradle.kts
  assert_violation
  assert_out_contains "includeBuild"
}

test_fails_on_buildSrc_convention_plugin() {
  new_module "$PWD" services/mw-a
  mkdir -p services/mw-a/buildSrc/src/main/kotlin
  echo 'plugins { `kotlin-dsl` }' >services/mw-a/buildSrc/build.gradle.kts
  assert_violation
  assert_out_contains "convention plugin"
}

test_fails_on_build_logic_convention_plugin_applied_by_id() {
  new_module "$PWD" services/mw-a
  printf 'plugins {\n  id("mfs.mw.java-conventions")\n}\n' >>services/mw-a/build.gradle.kts
  assert_violation
  assert_out_contains "convention plugin"
}

test_fails_on_applied_script_plugin() {
  new_module "$PWD" services/mw-a
  echo 'apply(from = "shared.gradle.kts")' >>services/mw-a/build.gradle.kts
  assert_violation
}

test_fails_on_relative_path_to_another_module() {
  new_module "$PWD" services/mw-a
  echo 'dependencies { implementation(files("../mw-b/build/libs/mw-b.jar")) }' >>services/mw-a/build.gradle.kts
  assert_violation
  assert_out_contains "../mw-b/build/libs/mw-b.jar"
}

test_fails_on_relative_path_escaping_through_contracts() {
  new_module "$PWD" services/mw-a
  echo 'mwContractsDir=../../contracts/../services/mw-b' >services/mw-a/gradle.properties
  assert_violation
}

test_fails_on_relative_path_to_root_build() {
  new_module "$PWD" services/mw-a
  echo 'val x = file("../../gradle/libs.versions.toml")' >>services/mw-a/build.gradle.kts
  assert_violation
}

test_fails_on_parentFile_navigation() {
  new_module "$PWD" services/mw-a
  echo 'val x = rootDir.parentFile.resolve("mw-b")' >>services/mw-a/build.gradle.kts
  assert_violation
}

test_template_is_checked_too() {
  new_module "$PWD" templates/mw-service-template
  echo 'includeBuild("../../services/mw-a")' >>templates/mw-service-template/settings.gradle.kts
  assert_violation
}

test_M_limits_the_check_to_one_module() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  echo 'includeBuild("../mw-a")' >>services/mw-b/settings.gradle.kts
  assert_ok mw_make "$PWD" check-isolation M=mw-a
  assert_violation M=mw-b
}

test_build_output_dirs_are_ignored() {
  new_module "$PWD" services/mw-a
  mkdir -p services/mw-a/build/tmp
  echo 'includeBuild("../x")' >services/mw-a/build/tmp/settings.gradle.kts
  assert_ok mw_make "$PWD" check-isolation
}

test_fails_on_shared_root_configuration() {
  printf 'allprojects {\n  repositories { mavenCentral() }\n}\n' >build.gradle.kts
  assert_violation
  assert_out_contains "root"
}

test_fails_on_root_buildSrc() {
  mkdir -p buildSrc
  assert_violation
}

test_fails_on_shared_root_version_catalog() {
  mkdir -p gradle && echo '[versions]' >gradle/libs.versions.toml
  assert_violation
}

# ADR D16: one hand-written deploy/docker-compose.yml; no module has its own compose.yml.
test_fails_on_a_module_compose_file() {
  new_module "$PWD" services/mw-a
  touch services/mw-a/compose.yml
  assert_violation
  assert_out_contains "services/mw-a: compose.yml"
  rm services/mw-a/compose.yml
  mkdir -p ui/mw-ui && touch ui/mw-ui/compose.yml
  assert_violation
  assert_out_contains "ui/mw-ui: compose.yml"
}

test_fails_on_a_reference_to_the_old_compose_layout() {
  new_module "$PWD" services/mw-a
  printf '# see deploy/%s/mvp/compose.yml\n' compose >services/mw-a/Dockerfile
  assert_violation
  assert_out_contains "services/mw-a/Dockerfile"
  # ADRs keep the history
  rm services/mw-a/Dockerfile
  mkdir -p docs/adr && printf 'was deploy/%s/mvp\n' compose >docs/adr/D16-x.md
  assert_ok mw_make "$PWD" check-isolation
}

test_reports_all_violations_in_one_run() {
  new_module "$PWD" services/mw-a
  new_module "$PWD" services/mw-b
  echo 'includeBuild("../x")' >>services/mw-a/settings.gradle.kts
  echo 'includeBuild("../y")' >>services/mw-b/settings.gradle.kts
  assert_violation
  assert_out_contains "services/mw-a"
  assert_out_contains "services/mw-b"
}
