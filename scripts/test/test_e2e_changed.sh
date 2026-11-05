# make e2e-changed: rebuild only the modules changed since a base ref inside the running MVP
# project, then run the e2e suite. Also make up with MW_UP_ONLY, which it builds on.
# docker is a stub on PATH: `compose ... ps -q` prints .running (stack up when non-empty) and
# `compose ... ps -a --format` prints .ps (healthy by default); every call is recorded.

stub_stack() {
  mkdir -p .bin deploy/compose/mvp
  touch deploy/compose/mvp/compose.yml
  printf 'mw-a\trunning\thealthy\t0\tcom.docker.compose.project=mw\n' >.ps
  cat >.bin/docker <<EOF
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *" ps -q"*) cat "$PWD/.running" 2>/dev/null ;;
  *" ps -a "*) cat "$PWD/.ps" ;;
esac
exit 0
EOF
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
}

# stack_module ROOT NAME: a stack module (Dockerfile, compose.yml with NAME-init and NAME).
stack_module() {
  new_module "$1" "services/$2"
  touch "$1/services/$2/Dockerfile"
  printf 'services:\n  %s-init:\n    image: x\n  %s:\n    image: x\n' "$2" "$2" >"$1/services/$2/compose.yml"
}

# e2e_suite ROOT: a tests/e2e suite whose gradlew stub records its arguments.
e2e_suite() {
  mkdir -p "$1/tests/e2e"
  printf '#!/bin/sh\necho "$*" >> "$(dirname "$0")/.gradlew-calls"\n' >"$1/tests/e2e/gradlew"
  chmod +x "$1/tests/e2e/gradlew"
}

# git_base ROOT: commit everything on main, then switch to a work branch.
git_base() {
  ( cd "$1" && git init -q -b main && git -c user.email=t@example.invalid -c user.name=t add -A \
    && git -c user.email=t@example.invalid -c user.name=t commit -q -m base && git checkout -q -b work )
}

commit_all() {
  git -c user.email=t@example.invalid -c user.name=t add -A
  git -c user.email=t@example.invalid -c user.name=t commit -q -m change
}

changed() { OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/changed-modules.sh" "$@" 2>&1)"; }

test_changed_modules_lists_committed_staged_unstaged_and_untracked_changes() {
  for m in mw-a mw-b mw-c mw-d mw-e; do stack_module "$PWD" "$m"; done
  git_base "$PWD"
  echo x >>services/mw-a/build.gradle.kts; commit_all          # committed on the branch
  echo x >>services/mw-b/build.gradle.kts; git add -A          # staged
  echo x >>services/mw-c/build.gradle.kts                      # unstaged
  touch services/mw-d/new-file.txt                             # untracked
  changed || fail "changed-modules failed"
  [ "$(printf '%s\n' "$OUT" | tr '\n' ' ')" = "mw-a mw-b mw-c mw-d " ] || fail "wrong modules: $OUT"
}

test_changed_modules_prints_nothing_when_no_module_changed() {
  stack_module "$PWD" mw-a
  mkdir -p tests/e2e docs templates/mw-service-template
  git_base "$PWD"
  touch docs/note.md tests/e2e/x.feature templates/mw-service-template/x.txt
  changed || fail "changed-modules failed"
  [ -z "$OUT" ] || fail "expected no modules, got: $OUT"
}

test_changed_modules_prints_ALL_for_contracts_or_deploy_changes() {
  stack_module "$PWD" mw-a
  mkdir -p contracts deploy/apisix
  git_base "$PWD"
  touch contracts/x.json
  changed; [ "$OUT" = ALL ] || fail "contracts change: $OUT"
  rm contracts/x.json; touch deploy/apisix/routes.yaml
  changed; [ "$OUT" = ALL ] || fail "deploy change: $OUT"
}

test_changed_modules_fails_on_an_unknown_base() {
  stack_module "$PWD" mw-a
  git_base "$PWD"
  if changed no-such-ref; then fail "unknown base accepted"; fi
  assert_out_contains "no-such-ref"
}

test_up_with_MW_UP_ONLY_rebuilds_only_those_modules_in_the_mvp_project() {
  stub_stack
  stack_module "$PWD" mw-a; stack_module "$PWD" mw-b
  MW_UP_ONLY=mw-a mw_make "$PWD" up || fail "up failed: $OUT"
  grep -q '^bootJar' services/mw-a/.gradlew-calls 2>/dev/null || fail "mw-a jar not built"
  [ -f services/mw-b/.gradlew-calls ] && fail "mw-b built although not in MW_UP_ONLY"
  grep -q "compose -f $PWD/deploy/compose/mvp/compose.yml up -d --build --no-deps mw-a-init mw-a" .tool-calls \
    || fail "mw-a not recreated in the mvp project: $(cat .tool-calls)"
  grep -q "services/mw-a/compose.yml" .tool-calls && fail "used the module's own compose project"
  return 0
}

test_up_rejects_MW_UP_ONLY_together_with_M() {
  stub_stack
  stack_module "$PWD" mw-a
  MW_UP_ONLY=mw-a mw_make "$PWD" up M=mw-a && fail "MW_UP_ONLY with M= accepted"
  assert_out_contains "MW_UP_ONLY"
}

test_e2e_changed_rebuilds_only_changed_modules_then_runs_the_full_suite() {
  stub_stack; echo abc123 >.running
  stack_module "$PWD" mw-a; stack_module "$PWD" mw-b; e2e_suite "$PWD"
  git_base "$PWD"
  echo x >>services/mw-b/build.gradle.kts
  mw_make "$PWD" e2e-changed || fail "e2e-changed failed: $OUT"
  grep -q '^bootJar' services/mw-b/.gradlew-calls 2>/dev/null || fail "mw-b not rebuilt"
  grep -q '^bootJar' services/mw-a/.gradlew-calls 2>/dev/null && fail "unchanged mw-a rebuilt"
  grep -q -- '--no-deps mw-b-init mw-b' .tool-calls || fail "mw-b not recreated: $(cat .tool-calls)"
  [ "$(cat tests/e2e/.gradlew-calls)" = test ] || fail "full suite not run: $(cat tests/e2e/.gradlew-calls)"
  assert_out_contains "mw-b"
}

test_e2e_changed_with_module_scope_filters_scenarios_by_module_tags() {
  stub_stack; echo abc123 >.running
  stack_module "$PWD" mw-a; stack_module "$PWD" mw-b; e2e_suite "$PWD"
  git_base "$PWD"
  echo x >>services/mw-a/build.gradle.kts; echo x >>services/mw-b/build.gradle.kts
  E2E_SCOPE=modules mw_make "$PWD" e2e-changed TAGS=@p2p || fail "e2e-changed failed: $OUT"
  grep -q -F 'cucumber.filter.tags=((@mw-a or @mw-b) and (@p2p))' tests/e2e/.gradlew-calls \
    || fail "wrong tag filter: $(cat tests/e2e/.gradlew-calls)"
}

test_e2e_changed_starts_the_whole_stack_when_it_is_down_or_ALL_changed() {
  stub_stack                                   # .running absent: stack down
  stack_module "$PWD" mw-a; stack_module "$PWD" mw-b; e2e_suite "$PWD"
  mkdir -p contracts; git_base "$PWD"
  echo x >>services/mw-a/build.gradle.kts
  mw_make "$PWD" e2e-changed || fail "e2e-changed failed: $OUT"
  grep -q '^bootJar' services/mw-b/.gradlew-calls 2>/dev/null || fail "stack down: not every module built"
  grep -q -- '--no-deps mw-' .tool-calls && fail "stack down: partial recreate"
  # stack up, but a contract changed: everything again
  rm -f .tool-calls services/*/.gradlew-calls; echo abc123 >.running; touch contracts/x.json
  mw_make "$PWD" e2e-changed || fail "e2e-changed failed: $OUT"
  grep -q '^bootJar' services/mw-b/.gradlew-calls 2>/dev/null || fail "ALL: not every module built"
  grep -q -- '--no-deps mw-' .tool-calls && fail "ALL: partial recreate"
  return 0
}

test_e2e_changed_with_no_module_changes_skips_the_rebuild_and_still_runs_e2e() {
  stub_stack; echo abc123 >.running
  stack_module "$PWD" mw-a; e2e_suite "$PWD"
  git_base "$PWD"
  mkdir -p tests/e2e/features; touch tests/e2e/features/x.feature
  mw_make "$PWD" e2e-changed || fail "e2e-changed failed: $OUT"
  [ -f services/mw-a/.gradlew-calls ] && fail "rebuilt with no module change"
  # the only `up -d` is suite.sh re-applying the test routes (apisix-init)
  grep ' up -d' .tool-calls | grep -v apisix-init | grep -q . && fail "stack touched: $(cat .tool-calls)"
  [ -f tests/e2e/.gradlew-calls ] || fail "e2e not run"
}

test_mvp_targets_add_the_low_resource_override_when_present() {
  stub_stack
  stack_module "$PWD" mw-a
  touch deploy/compose/mvp/compose.lowmem.yml
  mvp="$PWD/deploy/compose/mvp"
  mw_make "$PWD" up || fail "up failed: $OUT"
  grep -q -F "compose -f $mvp/compose.yml -f $mvp/compose.lowmem.yml up -d --build" .tool-calls \
    || fail "up without the override: $(cat .tool-calls)"
  grep -q -F "compose -f $mvp/compose.yml -f $mvp/compose.lowmem.yml ps -a" .tool-calls \
    || fail "health wait without the override: $(cat .tool-calls)"
  rm -f .tool-calls
  mw_make "$PWD" down || fail "down failed: $OUT"
  grep -q -F "compose -f $mvp/compose.yml -f $mvp/compose.lowmem.yml down" .tool-calls \
    || fail "down without the override: $(cat .tool-calls)"
  # a module's own compose.yml (M=) never gets the MVP override
  rm -f .tool-calls
  mw_make "$PWD" up M=mw-a || fail "up M= failed: $OUT"
  grep -q 'compose.lowmem.yml' .tool-calls && fail "override applied to M=mw-a"
  return 0
}
