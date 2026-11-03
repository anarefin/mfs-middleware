# make contracts-lint: schemas, sample payloads and OWNERS.yml in contracts/ (WP04).
#
# Layout the linter expects:
#   contracts/OWNERS.yml                                   files: { <path>: <owning service> }
#   contracts/events/<svc>/<name>.schema.json              JSON Schema 2020-12
#   contracts/events/<svc>/samples/<name>/valid*.json      must validate
#   contracts/events/<svc>/samples/<name>/invalid*.json    must NOT validate

# plant_schema SVC NAME: a minimal well-formed schema with one valid and one
# invalid sample, owned by SVC.
plant_schema() {
  local svc="$1" name="$2" dir="contracts/events/$1"
  mkdir -p "$dir/samples/$name"
  cat >"$dir/$name.schema.json" <<EOF
{
  "\$schema": "https://json-schema.org/draft/2020-12/schema",
  "\$id": "https://contracts.upay.mw/events/$svc/$name.schema.json",
  "title": "$name",
  "type": "object",
  "required": ["schemaVersion", "amount"],
  "properties": {
    "schemaVersion": { "const": 1 },
    "amount": {
      "type": "object",
      "required": ["valueMinor", "currency"],
      "properties": {
        "valueMinor": { "type": "integer", "minimum": 0 },
        "currency": { "const": "BDT" }
      }
    }
  }
}
EOF
  echo '{"schemaVersion":1,"amount":{"valueMinor":1050,"currency":"BDT"}}' >"$dir/samples/$name/valid-basic.json"
  echo '{"schemaVersion":1,"amount":{"valueMinor":10.5,"currency":"BDT"}}' >"$dir/samples/$name/invalid-decimal-amount.json"
  [ -f contracts/OWNERS.yml ] || printf 'files:\n' >contracts/OWNERS.yml
  printf '  events/%s/%s.schema.json: %s\n' "$svc" "$name" "$svc" >>contracts/OWNERS.yml
}

# --- the repo's own contracts (WP04) ---

test_repo_contracts_pass_the_lint() {
  assert_ok mw_make "$REPO_DIR" contracts-lint
}

test_repo_has_the_v1_contract_set_under_each_owning_service() {
  for f in \
    events/mw-orchestrator/mw.txn.request.v1.schema.json \
    events/mw-orchestrator/mw.txn.status.v1.schema.json \
    events/mw-orchestrator/core.command.v1.schema.json \
    events/mw-orchestrator/mw.partner.request.v1.schema.json \
    events/mw-orchestrator/mw.partner.reply.v1.schema.json \
    events/mw-orchestrator/mw.partner.inbound.v1.schema.json \
    events/mw-core-mock/core.event.v1.schema.json \
    events/mw-notify/mw.notify.request.v1.schema.json \
    events/mw-notify/mw.notify.status.v1.schema.json \
    events/mw-audit-dw/mw.audit.event.v1.schema.json \
    events/mw-admin-api/mw.config.changed.v1.schema.json; do
    [ -f "$REPO_DIR/contracts/$f" ] || fail "missing contract $f"
    grep -q "^  $f: " "$REPO_DIR/contracts/OWNERS.yml" || fail "no OWNERS.yml entry for $f"
  done
}

# Core commands needing customer authorisation carry encryptedPin { keyId, ciphertext } (6.3, M11).
test_repo_core_command_requires_encryptedPin_on_authorising_commands() {
  local s="$REPO_DIR/contracts/events/mw-orchestrator/samples/core.command.v1"
  for c in ValidateTransaction HoldFunds PostTransfer; do
    ls "$s"/invalid-*.json >/dev/null 2>&1 || fail "no invalid core.command samples"
    grep -l "\"$c\"" "$s"/invalid-*.json | xargs grep -L encryptedPin | grep -q . \
      || fail "no invalid sample proving $c without encryptedPin is rejected"
  done
}

test_lint_passes_on_empty_contracts() {
  assert_ok mw_make "$PWD" contracts-lint
}

test_lint_passes_on_a_well_formed_contract() {
  plant_schema mw-a mw.a.event.v1
  assert_ok mw_make "$PWD" contracts-lint
  assert_out_contains "1 schema"
}

test_lint_fails_when_a_valid_sample_does_not_validate() {
  plant_schema mw-a mw.a.event.v1
  echo '{"schemaVersion":1}' >contracts/events/mw-a/samples/mw.a.event.v1/valid-missing-amount.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "valid-missing-amount.json"
}

test_lint_fails_when_an_invalid_sample_validates() {
  plant_schema mw-a mw.a.event.v1
  echo '{"schemaVersion":1,"amount":{"valueMinor":5,"currency":"BDT"}}' >contracts/events/mw-a/samples/mw.a.event.v1/invalid-but-ok.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "invalid-but-ok.json"
}

test_lint_fails_on_a_schema_that_does_not_compile() {
  plant_schema mw-a mw.a.event.v1
  sed -i.bak 's/"type": "object",/"type": "objekt",/' contracts/events/mw-a/mw.a.event.v1.schema.json
  rm contracts/events/mw-a/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "does not compile"
}

test_lint_fails_on_malformed_json() {
  plant_schema mw-a mw.a.event.v1
  echo '{"schemaVersion":' >contracts/events/mw-a/samples/mw.a.event.v1/valid-broken.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "not valid JSON"
}

test_lint_fails_when_a_schema_has_no_valid_sample() {
  plant_schema mw-a mw.a.event.v1
  rm contracts/events/mw-a/samples/mw.a.event.v1/valid-*.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "no valid sample"
}

test_lint_fails_when_a_schema_has_no_invalid_sample() {
  plant_schema mw-a mw.a.event.v1
  rm contracts/events/mw-a/samples/mw.a.event.v1/invalid-*.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "no invalid sample"
}

test_lint_fails_on_samples_without_a_schema() {
  plant_schema mw-a mw.a.event.v1
  mkdir -p contracts/events/mw-a/samples/mw.gone.v1
  echo '{}' >contracts/events/mw-a/samples/mw.gone.v1/valid-x.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "mw.gone.v1/valid-x.json"
}

test_lint_fails_when_a_schema_lacks_a_required_schemaVersion() {
  plant_schema mw-a mw.a.event.v1
  sed -i.bak 's/"required": \["schemaVersion", "amount"\]/"required": ["amount"]/' contracts/events/mw-a/mw.a.event.v1.schema.json
  rm contracts/events/mw-a/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "schemaVersion"
}

test_lint_fails_when_a_contract_file_has_no_owner() {
  plant_schema mw-a mw.a.event.v1
  plant_schema mw-a mw.a.other.v1
  grep -v 'mw.a.other.v1' contracts/OWNERS.yml >owners.tmp && mv owners.tmp contracts/OWNERS.yml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "events/mw-a/mw.a.other.v1.schema.json: no owner"
}

test_lint_fails_when_OWNERS_is_missing() {
  plant_schema mw-a mw.a.event.v1
  rm contracts/OWNERS.yml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "OWNERS.yml"
}

test_lint_requires_an_owner_for_non_schema_contract_files() {
  plant_schema mw-a mw.a.event.v1
  mkdir -p contracts/api/mw-b
  echo 'openapi: 3.1.0' >contracts/api/mw-b/b.yaml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "api/mw-b/b.yaml: no owner"
  printf '  api/mw-b/b.yaml: mw-b\n' >>contracts/OWNERS.yml
  assert_ok mw_make "$PWD" contracts-lint
}

test_lint_fails_on_an_owner_entry_for_a_missing_file() {
  plant_schema mw-a mw.a.event.v1
  printf '  events/mw-a/gone.schema.json: mw-a\n' >>contracts/OWNERS.yml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "gone.schema.json"
}

test_lint_fails_when_the_owner_differs_from_the_owning_service_folder() {
  plant_schema mw-a mw.a.event.v1
  sed -i.bak 's/: mw-a$/: mw-b/' contracts/OWNERS.yml && rm contracts/OWNERS.yml.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "mw-b"
}

test_lint_fails_on_floating_point_numbers_in_a_schema() {
  plant_schema mw-a mw.a.event.v1
  sed -i.bak 's/"valueMinor": { "type": "integer"/"valueMinor": { "type": "number"/' contracts/events/mw-a/mw.a.event.v1.schema.json
  rm contracts/events/mw-a/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "valueMinor"
}
