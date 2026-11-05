# make contracts-lint: schemas, sample payloads and OWNERS.yml in contracts/ (WP04).
#
# Layout the linter expects (contracts/README.md):
#   contracts/OWNERS.yml                                         files: { <path>: <owning service> }
#   contracts/common/<name>.schema.json                          shared types, $ref'd by others
#   contracts/events/<svc>/<topic>.schema.json                   message or envelope schema
#   contracts/events/<svc>/<topic>/<Msg>.payload.schema.json     one payload per message type (or one
#       shared by several types, referenced from each of their oneOf branches)
#   contracts/events/<svc>/samples/<topic>[/<Msg>]/valid*.json   must validate
#   contracts/events/<svc>/samples/<topic>[/<Msg>]/invalid*.json must fail with exactly the
#       errors stated for it in expected-errors.json next to it

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
  echo '{"invalid-decimal-amount.json":["/amount/valueMinor must be integer"]}' >"$dir/samples/$name/expected-errors.json"
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
# Each has an invalid sample stated to fail only for the missing encryptedPin.
test_repo_core_command_requires_encryptedPin_on_authorising_commands() {
  local s="$REPO_DIR/contracts/events/mw-orchestrator/samples/core.command.v1" c
  for c in ValidateTransaction HoldFunds PostTransfer; do
    grep -q "\"/payload must have required property 'encryptedPin'\"" "$s/$c/expected-errors.json" 2>/dev/null \
      || fail "no invalid $c sample stated to fail for a missing encryptedPin"
  done
}

# A decimal amount is rejected because valueMinor must be an integer (spec 5.1).
test_repo_has_a_decimal_amount_sample_rejected_as_non_integer() {
  grep -rq "valueMinor must be integer" "$REPO_DIR/contracts/events" --include=expected-errors.json \
    || fail "no invalid sample stated to fail for a decimal valueMinor"
}

# Ticket 48: one envelope per topic, one payload schema per message type.
test_repo_envelopes_have_one_payload_schema_per_message_type() {
  local e="$REPO_DIR/contracts/events" t n refs r
  # one payload file on disk per oneOf branch of the envelope (counted, not fixed: tickets add
  # message types), and every branch's $ref names a file that exists
  for t in mw-orchestrator/core.command.v1 mw-core-mock/core.event.v1 \
    mw-orchestrator/mw.partner.request.v1 mw-orchestrator/mw.partner.reply.v1 \
    mw-orchestrator/mw.partner.inbound.v1; do
    n="$(ls "$e/$t"/*.payload.schema.json 2>/dev/null | wc -l | tr -d ' ')"
    refs="$(jq -r '[.. | objects | select(has("oneOf")) | .oneOf[] | .. | objects | select(has("$ref")) | ."$ref"
      | select(endswith(".payload.schema.json"))] | unique | .[]' "$e/$t.schema.json")"
    [ "$n" -gt 0 ] || fail "$t: no payload schemas"
    [ "$n" = "$(printf '%s\n' "$refs" | grep -c .)" ] \
      || fail "$t: $n payload schemas on disk, $(printf '%s\n' "$refs" | grep -c .) referenced by the envelope's oneOf"
    for r in $refs; do
      [ -f "$e/${t%/*}/$r" ] || fail "$t: oneOf names a missing $r"
    done
    grep -q '"oneOf"' "$e/$t.schema.json" || fail "$t.schema.json has no oneOf over payloads"
    grep -q '"if"' "$e/$t.schema.json" && fail "$t.schema.json still picks payload rules with if/then"
  done
  for f in HoldFunds ValidateTransaction PostTransfer CommitHold ReleaseHold CreditWallet ReverseTransaction \
    QueryTransaction ApplyComplianceAction UnlockWallet; do
    [ -f "$e/mw-orchestrator/core.command.v1/$f.payload.schema.json" ] || fail "no $f payload schema"
  done
  # Partner topics (review of 48): the families share one payload per topic, but keep a family
  # enum and one oneOf branch per family so a family can get its own payload file later.
  for t in request reply inbound; do
    for f in telco billpay bank interop compliance; do
      grep -qF "\"family\": { \"const\": \"$f\" }, \"payload\": { \"\$ref\": \"mw.partner.$t.v1/shared.payload.schema.json\" }" \
        "$e/mw-orchestrator/mw.partner.$t.v1.schema.json" || fail "mw.partner.$t.v1: no $f branch on the shared payload"
      ls "$e/mw-orchestrator/samples/mw.partner.$t.v1/$f"/valid*.json >/dev/null 2>&1 || fail "mw.partner.$t.v1: no valid $f sample"
      ls "$e/mw-orchestrator/samples/mw.partner.$t.v1/$f"/invalid*.json >/dev/null 2>&1 || fail "mw.partner.$t.v1: no invalid $f sample"
    done
  done
}

# Shared types live once in contracts/common/defs.v1.schema.json, owned in OWNERS.yml.
test_repo_shared_types_live_once_in_common_defs() {
  local d="$REPO_DIR/contracts/common/defs.v1.schema.json" t
  [ -f "$d" ] || fail "missing contracts/common/defs.v1.schema.json"
  for t in SchemaVersion TxnId CorrelationId Amount Msisdn Party EncryptedPin; do
    grep -q "\"$t\": {" "$d" || fail "defs.v1 lacks $t"
  done
  grep -q '^  common/defs.v1.schema.json: mw-' "$REPO_DIR/contracts/OWNERS.yml" || fail "common/defs.v1 has no service owner"
  grep -rl '"valueMinor"\|"ciphertext"' "$REPO_DIR/contracts/events" --include='*.schema.json' | grep -q . \
    && fail "an event schema redefines Amount or EncryptedPin instead of using common/defs.v1"
  return 0
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

# plant_common OWNER: contracts/common/defs.v1.schema.json with an Amount type.
plant_common() {
  mkdir -p contracts/common
  cat >contracts/common/defs.v1.schema.json <<'EOF'
{
  "$schema": "https://json-schema.org/draft/2020-12/schema",
  "$id": "https://contracts.upay.mw/common/defs.v1.schema.json",
  "title": "defs.v1",
  "$defs": {
    "Amount": {
      "type": "object",
      "required": ["valueMinor", "currency"],
      "properties": { "valueMinor": { "type": "integer", "minimum": 0 }, "currency": { "const": "BDT" } }
    }
  }
}
EOF
  [ -f contracts/OWNERS.yml ] || printf 'files:\n' >contracts/OWNERS.yml
  printf '  common/defs.v1.schema.json: %s\n' "$1" >>contracts/OWNERS.yml
}

# plant_payload SVC TOPIC MSG: a payload schema for message type MSG that needs an amount.
plant_payload() {
  local dir="contracts/events/$1/$2"
  mkdir -p "$dir"
  cat >"$dir/$3.payload.schema.json" <<EOF
{
  "\$schema": "https://json-schema.org/draft/2020-12/schema",
  "\$id": "https://contracts.upay.mw/events/$1/$2/$3.payload.schema.json",
  "title": "$2 $3 payload",
  "type": "object",
  "required": ["amount"],
  "properties": { "amount": { "\$ref": "../../../common/defs.v1.schema.json#/\$defs/Amount" } }
}
EOF
}

# plant_msg_samples SVC TOPIC MSG: one valid and one invalid sample for message type MSG.
plant_msg_samples() {
  local dir="contracts/events/$1/samples/$2/$3"
  mkdir -p "$dir"
  echo "{\"schemaVersion\":1,\"kind\":\"$3\",\"payload\":{\"amount\":{\"valueMinor\":1050,\"currency\":\"BDT\"}}}" >"$dir/valid-basic.json"
  echo "{\"schemaVersion\":1,\"kind\":\"$3\",\"payload\":{\"amount\":{\"valueMinor\":10.5,\"currency\":\"BDT\"}}}" >"$dir/invalid-decimal-amount.json"
  echo '{"invalid-decimal-amount.json":["/payload/amount/valueMinor must be integer"]}' >"$dir/expected-errors.json"
}

# plant_envelope SVC TOPIC MSG...: an envelope keyed on "kind" whose payload is a oneOf of one
# payload schema per message type, with samples per type and common defs owned by SVC.
plant_envelope() {
  local svc="$1" topic="$2"; shift 2
  local enum="" branches="" m
  [ -f contracts/common/defs.v1.schema.json ] || plant_common "$svc"
  for m in "$@"; do
    enum="$enum${enum:+, }\"$m\""
    branches="$branches${branches:+,
    }{ \"properties\": { \"kind\": { \"const\": \"$m\" }, \"payload\": { \"\$ref\": \"$topic/$m.payload.schema.json\" } } }"
    plant_payload "$svc" "$topic" "$m"
    plant_msg_samples "$svc" "$topic" "$m"
  done
  cat >"contracts/events/$svc/$topic.schema.json" <<EOF
{
  "\$schema": "https://json-schema.org/draft/2020-12/schema",
  "\$id": "https://contracts.upay.mw/events/$svc/$topic.schema.json",
  "title": "$topic",
  "type": "object",
  "required": ["schemaVersion", "kind", "payload"],
  "properties": {
    "schemaVersion": { "const": 1 },
    "kind": { "enum": [$enum] },
    "payload": { "type": "object" }
  },
  "discriminator": { "propertyName": "kind" },
  "oneOf": [
    $branches
  ]
}
EOF
  printf '  events/%s/%s.schema.json: %s\n' "$svc" "$topic" "$svc" >>contracts/OWNERS.yml
}

# --- v1.1 layout: envelope + one payload schema per message type, shared common/ (ticket 48) ---

test_lint_resolves_cross_file_refs_between_envelope_payload_and_common_defs() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  assert_ok mw_make "$PWD" contracts-lint
  assert_out_contains "4 schema"
  assert_out_contains "4 sample"
}

test_lint_fails_when_a_ref_points_at_a_missing_file() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  sed -i.bak 's/defs.v1.schema.json/gone.v1.schema.json/' contracts/events/mw-a/mw.a.cmd.v1/Alpha.payload.schema.json
  rm contracts/events/mw-a/mw.a.cmd.v1/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "does not compile"
}

test_lint_fails_when_a_schema_id_does_not_match_its_path() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  sed -i.bak 's#mw.a.cmd.v1/Alpha.payload#mw.a.cmd.v1/Other.payload#' contracts/events/mw-a/mw.a.cmd.v1/Alpha.payload.schema.json
  rm contracts/events/mw-a/mw.a.cmd.v1/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "\$id"
}

test_lint_fails_when_a_payload_file_has_no_enum_value() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  plant_payload mw-a mw.a.cmd.v1 Stray
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "Stray.payload.schema.json"
  assert_out_contains "no kind enum value"
}

test_lint_fails_when_an_enum_value_has_no_payload_file() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  rm contracts/events/mw-a/mw.a.cmd.v1/Beta.payload.schema.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "Beta"
  assert_out_contains "no payload file"
}

test_lint_fails_when_a_oneOf_branch_refs_another_message_payload() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  sed -i.bak 's#"const": "Beta" }, "payload": { "$ref": "mw.a.cmd.v1/Beta#"const": "Beta" }, "payload": { "$ref": "mw.a.cmd.v1/Alpha#' contracts/events/mw-a/mw.a.cmd.v1.schema.json
  rm contracts/events/mw-a/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "oneOf branch for Beta"
}

# share_payload SVC TOPIC SHARED MSG...: point the branches of MSG... at one shared payload file
# <TOPIC>/<SHARED>.payload.schema.json instead of one file each (partner topics, review of 48).
share_payload() {
  local svc="$1" topic="$2" shared="$3" m; shift 3
  plant_payload "$svc" "$topic" "$shared"
  for m in "$@"; do
    sed -i.bak "s#\"$topic/$m.payload.schema.json\"#\"$topic/$shared.payload.schema.json\"#" "contracts/events/$svc/$topic.schema.json"
    rm "contracts/events/$svc/$topic/$m.payload.schema.json"
  done
  rm -f contracts/events/"$svc"/*.bak
}

test_lint_accepts_one_shared_payload_referenced_by_several_enum_values() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  share_payload mw-a mw.a.cmd.v1 shared Alpha Beta
  assert_ok mw_make "$PWD" contracts-lint
  assert_out_contains "3 schema"
  assert_out_contains "4 sample"
}

test_lint_accepts_a_family_split_out_of_a_shared_payload() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta Gamma
  share_payload mw-a mw.a.cmd.v1 shared Alpha Beta
  assert_ok mw_make "$PWD" contracts-lint
}

test_lint_fails_when_an_enum_value_has_no_branch_with_a_shared_payload() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  share_payload mw-a mw.a.cmd.v1 shared Alpha Beta
  sed -i.bak 's/"kind": { "enum": \["Alpha", "Beta"\] }/"kind": { "enum": ["Alpha", "Beta", "Delta"] }/' contracts/events/mw-a/mw.a.cmd.v1.schema.json
  rm contracts/events/mw-a/*.bak
  plant_msg_samples mw-a mw.a.cmd.v1 Delta
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "kind Delta has no payload file"
}

test_lint_fails_on_a_shared_payload_file_nothing_references() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  share_payload mw-a mw.a.cmd.v1 shared Alpha Beta
  plant_payload mw-a mw.a.cmd.v1 unused
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "unused.payload.schema.json: payload file is referenced by no oneOf branch"
}

test_lint_fails_when_a_message_type_has_no_samples() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  rm -r contracts/events/mw-a/samples/mw.a.cmd.v1/Beta
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "samples/mw.a.cmd.v1/Beta/"
}

test_lint_fails_when_a_message_type_has_no_invalid_sample() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  rm contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/invalid-*.json
  echo '{}' >contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/expected-errors.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "no invalid sample"
}

test_lint_fails_when_a_sample_type_differs_from_its_folder() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  sed -i.bak 's/"kind":"Alpha"/"kind":"Beta"/' contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/valid-basic.json
  rm contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "Alpha/valid-basic.json"
}

test_lint_fails_on_samples_for_a_message_type_the_envelope_lacks() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  plant_msg_samples mw-a mw.a.cmd.v1 Gamma
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "Gamma"
}

test_lint_fails_when_an_invalid_sample_fails_for_another_reason() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  echo '{"schemaVersion":1,"kind":"Alpha","payload":{"amount":{"valueMinor":10.5,"currency":"USD"}}}' \
    >contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/invalid-decimal-amount.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "/payload/amount/currency"
}

test_lint_fails_when_an_invalid_sample_has_no_stated_reason() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  echo '{}' >contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/expected-errors.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "no stated reason"
}

test_lint_fails_on_a_stated_reason_without_a_sample() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  echo '{"invalid-decimal-amount.json":["/payload/amount/valueMinor must be integer"],"invalid-gone.json":["/ x"]}' \
    >contracts/events/mw-a/samples/mw.a.cmd.v1/Alpha/expected-errors.json
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "invalid-gone.json"
}

test_lint_fails_on_floating_point_numbers_in_a_payload_schema() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  sed -i.bak 's/"required": \["amount"\],/"required": ["amount"], "patternProperties": { "^rate$": { "type": "number" } },/' \
    contracts/events/mw-a/mw.a.cmd.v1/Alpha.payload.schema.json
  rm contracts/events/mw-a/mw.a.cmd.v1/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "Alpha.payload.schema.json: uses type \"number\""
}

test_lint_fails_when_an_envelope_lacks_a_required_schemaVersion() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  sed -i.bak 's/"required": \["schemaVersion", "kind", "payload"\]/"required": ["kind", "payload"]/' contracts/events/mw-a/mw.a.cmd.v1.schema.json
  rm contracts/events/mw-a/*.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "mw.a.cmd.v1.schema.json: must declare a required schemaVersion"
}

test_lint_accepts_a_service_as_owner_of_common_and_still_requires_one() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  assert_ok mw_make "$PWD" contracts-lint
  grep -v 'common/defs.v1' contracts/OWNERS.yml >owners.tmp && mv owners.tmp contracts/OWNERS.yml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "common/defs.v1.schema.json: no owner"
}

test_lint_rejects_a_non_service_owner_for_common() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  sed -i.bak 's/common\/defs.v1.schema.json: mw-a/common\/defs.v1.schema.json: nobody/' contracts/OWNERS.yml && rm contracts/OWNERS.yml.bak
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "nobody"
}

# --- contract manifests: services/<mw-x>/contracts.yml (ticket 53) ---

# plant_manifest MODULE PRODUCED_EVENTS CONSUMED_EVENTS [PRODUCED_APIS]: services/MODULE/contracts.yml
# with the given flow-style lists (e.g. "events/mw-a/x.v1.schema.json, events/mw-a/y.v1.schema.json").
plant_manifest() {
  mkdir -p "services/$1"
  cat >"services/$1/contracts.yml" <<EOF
produces:
  events: [$2]
  apis: [${4:-}]
consumes:
  events: [$3]
  apis: []
EOF
}

test_repo_every_module_has_a_contract_manifest() {
  local d
  for d in "$REPO_DIR"/services/*/ "$REPO_DIR"/templates/mw-service-template/; do
    [ -f "$d/settings.gradle.kts" ] || continue
    [ -f "$d/contracts.yml" ] || fail "no contracts.yml in $d"
  done
}

test_lint_accepts_manifests_that_cover_every_schema() {
  plant_schema mw-a mw.a.event.v1
  plant_envelope mw-a mw.a.cmd.v1 Alpha Beta
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json, events/mw-a/mw.a.cmd.v1.schema.json#Alpha" ""
  plant_manifest mw-b "" "events/mw-a/mw.a.event.v1.schema.json, events/mw-a/mw.a.cmd.v1.schema.json"
  assert_ok mw_make "$PWD" contracts-lint
  assert_out_contains "2 manifest(s)"
}

test_lint_fails_when_a_manifest_entry_names_a_missing_file() {
  plant_schema mw-a mw.a.event.v1
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json, events/mw-a/mw.a.gone.v1.schema.json" ""
  plant_manifest mw-b "" "events/mw-a/mw.a.event.v1.schema.json"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "services/mw-a/contracts.yml: produces.events entry events/mw-a/mw.a.gone.v1.schema.json: no such contract file"
}

test_lint_fails_when_a_manifest_api_entry_names_a_missing_file() {
  plant_schema mw-a mw.a.event.v1
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json" "events/mw-a/mw.a.event.v1.schema.json" "api/mw-a/gone.v1.openapi.yaml"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "produces.apis entry api/mw-a/gone.v1.openapi.yaml: no such contract file"
}

test_lint_fails_when_a_manifest_names_a_message_type_the_envelope_lacks() {
  plant_envelope mw-a mw.a.cmd.v1 Alpha
  plant_manifest mw-a "events/mw-a/mw.a.cmd.v1.schema.json#Gamma" "events/mw-a/mw.a.cmd.v1.schema.json#Alpha"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "produces.events entry events/mw-a/mw.a.cmd.v1.schema.json#Gamma: events/mw-a/mw.a.cmd.v1.schema.json has no message type Gamma"
}

test_lint_fails_when_a_manifest_names_a_message_type_of_a_single_type_topic() {
  plant_schema mw-a mw.a.event.v1
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json#Alpha" "events/mw-a/mw.a.event.v1.schema.json"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "is a single-type topic, it has no message type Alpha"
}

test_lint_fails_on_an_orphan_schema_without_producer_or_consumer() {
  plant_schema mw-a mw.a.event.v1
  plant_schema mw-a mw.a.orphan.v1
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json" "events/mw-a/mw.a.event.v1.schema.json"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "events/mw-a/mw.a.orphan.v1.schema.json: orphan schema: no contracts.yml produces it"
  assert_out_contains "events/mw-a/mw.a.orphan.v1.schema.json: orphan schema: no contracts.yml consumes it"
  assert_out_lacks "mw.a.event.v1.schema.json: orphan"
}

test_lint_accepts_a_side_played_outside_the_repo_from_EXTERNAL() {
  plant_schema mw-a mw.a.event.v1
  plant_manifest mw-b "" "events/mw-a/mw.a.event.v1.schema.json"
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "no contracts.yml produces it"
  printf 'producers:\n  events/mw-a/mw.a.event.v1.schema.json: the core banking system\n' >contracts/EXTERNAL.yml
  printf '  EXTERNAL.yml: mw-a\n' >>contracts/OWNERS.yml
  assert_ok mw_make "$PWD" contracts-lint
}

test_lint_fails_on_an_EXTERNAL_entry_for_a_missing_schema() {
  plant_schema mw-a mw.a.event.v1
  plant_manifest mw-a "events/mw-a/mw.a.event.v1.schema.json" "events/mw-a/mw.a.event.v1.schema.json"
  printf 'consumers:\n  events/mw-a/mw.a.gone.v1.schema.json: nobody\n' >contracts/EXTERNAL.yml
  printf '  EXTERNAL.yml: mw-a\n' >>contracts/OWNERS.yml
  assert_fails mw_make "$PWD" contracts-lint
  assert_out_contains "EXTERNAL.yml: consumers entry events/mw-a/mw.a.gone.v1.schema.json is not an event schema"
}
