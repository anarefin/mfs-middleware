# scripts/changed-modules.sh: which changes select every module (ALL). Ticket 54: documentation
# under deploy/ (deploy/**/*.md) and docs/ selects no module in either mode.

cm_repo() {
  new_module "$PWD" services/mw-a
  mkdir -p deploy/apisix docs
  echo 'x: 1' >deploy/apisix/config.yml
  echo '# edge' >deploy/apisix/README.md
  echo '# docs' >docs/runbook.md
  git init -q -b main && git -c user.email=t@example.invalid -c user.name=t add -A \
    && git -c user.email=t@example.invalid -c user.name=t commit -q -m base && git checkout -q -b work
}

cm() { OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/changed-modules.sh" "$@" main 2>&1)" || fail "changed-modules failed"; }

test_changed_modules_ignores_markdown_under_deploy_and_docs() {
  cm_repo
  echo more >>deploy/apisix/README.md
  echo more >>docs/runbook.md
  mkdir -p deploy/compose/mvp && echo '# new' >deploy/compose/mvp/NOTES.md
  cm
  [ -z "$OUT" ] || fail "docs-only change selected modules (default mode): $OUT"
  cm --contracts-precise
  [ -z "$OUT" ] || fail "docs-only change selected modules (precise mode): $OUT"
}

test_changed_modules_still_selects_all_for_deploy_config() {
  cm_repo
  echo more >>deploy/apisix/README.md
  echo 'y: 2' >>deploy/apisix/config.yml
  cm
  [ "$OUT" = ALL ] || fail "deploy config change should be ALL (default mode), got: $OUT"
  cm --contracts-precise
  [ "$OUT" = ALL ] || fail "deploy config change should be ALL (precise mode), got: $OUT"
}

test_changed_modules_lists_a_module_beside_a_docs_change() {
  cm_repo
  echo more >>docs/runbook.md
  echo x >>services/mw-a/build.gradle.kts
  cm --contracts-precise
  [ "$OUT" = mw-a ] || fail "expected mw-a, got: $OUT"
}
