# make verify-changed: the verify gate for the modules changed since BASE only (ticket 53).
# Modules are fixture builds whose gradlew stub records its tasks; contracts/ holds minimal schemas
# and each module a contracts.yml, so a changed contract selects its producers, consumers and owner.

# vc_schema SVC TOPIC: contracts/events/SVC/TOPIC.schema.json with one valid and one invalid sample.
vc_schema() {
  local dir="contracts/events/$1"
  mkdir -p "$dir/samples/$2"
  cat >"$dir/$2.schema.json" <<EOF
{
  "\$schema": "https://json-schema.org/draft/2020-12/schema",
  "\$id": "https://contracts.bits.mw/events/$1/$2.schema.json",
  "title": "$2",
  "type": "object",
  "required": ["schemaVersion"],
  "properties": { "schemaVersion": { "const": 1 } }
}
EOF
  echo '{"schemaVersion":1}' >"$dir/samples/$2/valid-basic.json"
  echo '{"schemaVersion":2}' >"$dir/samples/$2/invalid-version.json"
  echo '{"invalid-version.json":["/schemaVersion must be equal to constant"]}' >"$dir/samples/$2/expected-errors.json"
  [ -f contracts/OWNERS.yml ] || printf 'files:\n' >contracts/OWNERS.yml
  printf '  events/%s/%s.schema.json: %s\n' "$1" "$2" "$1" >>contracts/OWNERS.yml
}

# vc_manifest MODULE PRODUCED CONSUMED: services/MODULE/contracts.yml (flow-style event lists).
vc_manifest() {
  printf 'produces:\n  events: [%s]\n  apis: []\nconsumes:\n  events: [%s]\n  apis: []\n' "$2" "$3" \
    >"services/$1/contracts.yml"
}

vc_git_base() {
  git init -q -b main && git -c user.email=t@example.invalid -c user.name=t add -A \
    && git -c user.email=t@example.invalid -c user.name=t commit -q -m base && git checkout -q -b work
}

# A small repo: mw-notify owns and consumes mw.notify.request.v1, mw-a produces it, mw-b neither;
# mw-b produces mw.b.event.v1, consumed by mw-a.
vc_repo() {
  local m
  for m in mw-notify mw-a mw-b; do new_module "$PWD" "services/$m"; done
  vc_schema mw-notify mw.notify.request.v1
  vc_schema mw-b mw.b.event.v1
  vc_manifest mw-notify "" "events/mw-notify/mw.notify.request.v1.schema.json"
  vc_manifest mw-a "events/mw-notify/mw.notify.request.v1.schema.json" "events/mw-b/mw.b.event.v1.schema.json"
  vc_manifest mw-b "events/mw-b/mw.b.event.v1.schema.json" ""
  vc_git_base
}

checked() { [ -f "services/$1/.gradlew-calls" ] && grep -q check "services/$1/.gradlew-calls"; }

test_verify_changed_on_a_notify_contract_checks_only_its_producers_and_consumers() {
  vc_repo
  echo '{"schemaVersion":1,"x":1}' >contracts/events/mw-notify/samples/mw.notify.request.v1/valid-more.json
  assert_ok mw_make "$PWD" verify-changed BASE=main
  checked mw-notify || fail "consumer and owner mw-notify not checked"
  checked mw-a || fail "producer mw-a not checked"
  ! checked mw-b || fail "mw-b checked although it neither produces nor consumes the topic"
  assert_out_contains "verify-changed summary"
  assert_out_contains "contracts-lint passed"
}

test_changed_modules_precise_maps_a_contract_to_producers_consumers_and_owner() {
  vc_repo
  echo ' ' >>contracts/events/mw-b/mw.b.event.v1.schema.json
  OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/changed-modules.sh" --contracts-precise main 2>&1)" || fail "changed-modules failed"
  [ "$(printf '%s\n' "$OUT" | tr '\n' ' ')" = "mw-a mw-b " ] || fail "wrong modules: $OUT"
  # without the flag the old behaviour stays: any contracts/ change is ALL (e2e-changed)
  OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/changed-modules.sh" main 2>&1)"
  [ "$OUT" = ALL ] || fail "default mode should print ALL, got: $OUT"
}

test_verify_changed_checks_every_module_for_common_owners_deploy_or_scripts_changes() {
  local f
  vc_repo
  for f in contracts/common/defs.v1.schema.json contracts/OWNERS.yml deploy/x.yml scripts/x.sh; do
    mkdir -p "$(dirname "$f")"; echo '' >>"$f"
    OUT="$(MW_ROOT="$PWD" "$REPO_DIR/scripts/changed-modules.sh" --contracts-precise main 2>&1)"
    [ "$OUT" = ALL ] || fail "$f should select all modules, got: $OUT"
    git checkout -q -- . 2>/dev/null; git clean -qfd
  done
}

test_verify_changed_checks_a_changed_module_and_reports_its_duration() {
  vc_repo
  echo x >>services/mw-b/build.gradle.kts
  assert_ok mw_make "$PWD" verify-changed BASE=main JOBS=2
  checked mw-b || fail "mw-b not checked"
  ! checked mw-a || fail "mw-a checked although unchanged"
  [ -f build/verify-changed/mw-b.log ] || fail "no per-module log"
  assert_out_contains "mw-b"
  assert_out_contains "pass"
}

test_verify_changed_fails_when_a_module_check_fails() {
  vc_repo
  echo x >>services/mw-b/build.gradle.kts
  echo x >>services/mw-a/build.gradle.kts
  touch services/mw-b/.gradlew-fail
  assert_fails mw_make "$PWD" verify-changed BASE=main
  checked mw-a || fail "mw-a not checked after mw-b failed"
  assert_out_contains "verify-changed FAILED: mw-b"
}

test_verify_changed_with_nothing_changed_runs_the_static_checks_only() {
  vc_repo
  assert_ok mw_make "$PWD" verify-changed BASE=main
  assert_out_contains "no module changed"
  ! checked mw-a || fail "gradle ran with nothing changed"
}

test_verify_changed_stops_before_gradle_when_isolation_is_broken() {
  vc_repo
  echo 'includeBuild("../mw-b")' >>services/mw-a/settings.gradle.kts
  assert_fails mw_make "$PWD" verify-changed BASE=main
  assert_out_contains "ISOLATION"
  ! checked mw-a || fail "gradle ran despite isolation failure"
}

test_verify_changed_rejects_a_bad_JOBS_value() {
  vc_repo
  assert_fails mw_make "$PWD" verify-changed BASE=main JOBS=x
  assert_out_contains "JOBS must be a positive number"
}

test_verify_changed_warns_when_the_stack_is_running() {
  vc_repo
  mkdir -p .bin deploy
  touch deploy/docker-compose.yml
  git -c user.email=t@example.invalid -c user.name=t add -A
  git -c user.email=t@example.invalid -c user.name=t commit -q -m stack
  # docker stub: no compose CLI version (compose-check skipped), but the MVP project has containers
  printf '#!/bin/sh\ncase "$*" in *" ps -q"*) echo abc123; exit 0 ;; esac\nexit 1\n' >.bin/docker
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH" mw_make "$PWD" verify-changed BASE=HEAD || fail "verify-changed failed"
  assert_out_contains "the stack (deploy/docker-compose.yml) is running"
}
