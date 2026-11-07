# Security pass (ticket 46): scripts/log-scan.sh rules, the live stages of scripts/security.sh
# (ZAP baseline + log scan, run by the gate on the running stack) with docker stubbed, and the
# ZAP report gate scripts/lib/zap-gate.py.

LOGSCAN="$REPO_DIR/scripts/log-scan.sh"

# scan_lines LINE...: run the log scan over the given lines; output in $OUT
scan_lines() {
  printf '%s\n' "$@" >logs.txt
  OUT="$(MW_ROOT="$PWD" bash "$LOGSCAN" logs.txt 2>&1)"
}

b64_344() { head -c 256 /dev/urandom | base64 | tr -d '\n='; }

test_log_scan_passes_masked_values_and_system_ids() {
  assert_ok scan_lines \
    '{"ts":"2026-11-06T10:00:00Z","message":"sent to 0171****789","epoch":1762425600000}' \
    '{"pin":"****","encryptedPin":"[REDACTED]","nid":"***123","pinKeyId":"core-2026"}' \
    '{"txnId":"6f1c2a9e-3b4d-4e5f-8a7b-017123456789","event":"pin.verify.failed","mapping":"x"}' \
    '{"accountNo":"****0123","spin":"7","pin":null}'
  assert_out_contains "no PIN"
}

test_log_scan_passes_a_sql_cast_of_a_pin_column() {
  # PostgreSQL logs the statement of a deadlock: a column name cast with ::, no value (ticket 58)
  assert_ok scan_lines \
    'postgres-1  | Process 179: select txn_id, encrypted_pin::text, request::text from transaction where txn_id = $1'
  assert_fails scan_lines '{"pin":"1234"}'
  assert_fails scan_lines 'pin: 1234'
}

test_log_scan_finds_an_unmasked_msisdn_without_repeating_it() {
  assert_fails scan_lines 'payer 01712345678 ok'
  assert_out_contains "FINDING msisdn logs.txt:1"
  assert_out_lacks "01712345678"
  assert_fails scan_lines '{"to":"+8801812345678"}'
  assert_out_contains "FINDING msisdn"
}

# System ids stay whole by shape (root CLAUDE.md "Masking"): an 11-digit 01... run inside a UUID
# or a W3C trace/span id is not an MSISDN, but a real MSISDN next to one still is.
test_log_scan_ignores_msisdn_shaped_runs_inside_uuids_and_trace_ids() {
  assert_ok scan_lines \
    '{"txnId":"01a1ae14-7daf-727a-ad5b-c01653930180","event":"txn.state.changed"}' \
    'GET /portal/org/v1/bulk/batches/01A1AE18-377A-7145-9B2A-C01712345678/pause' \
    '{"traceId":"4bf92f3577b34da6a3ce929d0e0e4736","spanId":"00f067aa0ba902b7"}' \
    '{"traceId":"abcdefabcdefabcdefabc01712345678","spanId":"a01712345678bcde"}' \
    'traceparent: 00-a01712345678bcdea3ce929d0e0e4736-a01812345678bcde-01' \
    '{"to":"0171****789","txnId":"01a1ae14-7daf-727a-ad5b-c01653930180"}'
  assert_out_contains "no PIN"
}

test_log_scan_still_finds_an_msisdn_next_to_a_uuid() {
  assert_fails scan_lines '{"txnId":"01a1ae14-7daf-727a-ad5b-c01653930180","to":"01712345678"}'
  assert_out_contains "FINDING msisdn"
  assert_fails scan_lines 'id=01a1ae14-7daf-727a-ad5b-c01653930180/01712345678'
  assert_out_contains "FINDING msisdn"
  # only a whole UUID is exempt: a last group of 13 or a first group of 11 is not a UUID
  assert_fails scan_lines 'ref 01a1ae14-7daf-727a-ad5b-ac01653930180'
  assert_out_contains "FINDING msisdn"
  assert_fails scan_lines 'ref 01712345678-7daf-727a-ad5b-c01653930180'
  assert_out_contains "FINDING msisdn"
  # a 32-hex run is a trace id only where a trace id goes
  assert_fails scan_lines '{"note":"abcdefabcdefabcdefabc01712345678"}'
  assert_out_contains "FINDING msisdn"
}

test_log_scan_finds_a_plain_pin_and_any_pin_ciphertext() {
  assert_fails scan_lines '{"pin":"1234"}'
  assert_out_contains "FINDING pin"
  assert_out_lacks "1234"
  assert_fails scan_lines '{"encryptedPin":{"keyId":"k","ciphertext":"abc"}}'
  assert_out_contains "FINDING pin"
  assert_fails scan_lines "{\"body\":\"word $(b64_344)\"}"
  assert_out_contains "FINDING ciphertext"
}

test_log_scan_finds_an_unmasked_nid() {
  assert_fails scan_lines '{"nid":"1990123456789"}'
  assert_out_contains "FINDING nid"
  assert_fails scan_lines 'nationalId=19901234567890123'
  assert_out_contains "FINDING nid"
}

test_log_scan_honours_the_justified_allow_list() {
  mkdir -p scripts
  printf '# reason: fixture\n^ALLOWED .*\n' >scripts/log-scan.allow
  assert_ok scan_lines 'ALLOWED payer 01712345678'
  assert_fails scan_lines 'payer 01712345678'
}

# stub_live_docker LOGS: docker stub for a running MVP stack whose every container logs LOGS
stub_live_docker() {
  mkdir -p .bin deploy/zap scripts/lib
  touch deploy/docker-compose.yml deploy/zap/decisions.tsv scripts/lib/zap-gate.py
  printf '%s\n' "$1" >.stub-logs
  cat >.bin/docker <<EOF
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *"ps -q --status running apisix"*) echo cid1 ;;
  *"ps -a --format"*) printf 'apisix\nmw-orchestrator\n' ;;
  *" logs "*) cat "$PWD/.stub-logs" ;;
  *"image inspect"*) exit 1 ;;
  *zap-baseline*) [ -f "$PWD/.zap-fail" ] && exit 1; exit 0 ;;
esac
exit 0
EOF
  chmod +x .bin/docker
  for t in gitleaks trivy; do
    printf '#!/bin/sh\necho "%s $*" >> "%s/.tool-calls"\n' "$t" "$PWD" >".bin/$t"; chmod +x ".bin/$t"
  done
  PATH="$PWD/.bin:$PATH"
  cp "$LOGSCAN" scripts/log-scan.sh 2>/dev/null || true
}

# The live stages call the repo's own scripts: run security.sh from a copy inside the fixture.
security_in_fixture() {
  mkdir -p scripts/lib
  cp "$REPO_DIR/scripts/security.sh" "$REPO_DIR/scripts/log-scan.sh" scripts/
  cp "$REPO_DIR/scripts/lib/modules.sh" scripts/lib/
  OUT="$(MW_ROOT="$PWD" "$@" bash scripts/security.sh 2>&1)"
}

test_security_runs_zap_on_the_edge_and_the_log_scan_when_the_stack_is_up() {
  stub_live_docker '{"message":"to 0171****789"}'
  assert_ok security_in_fixture env
  grep -q 'zap-baseline.py' .tool-calls || fail "no ZAP baseline: $(cat .tool-calls)"
  grep -q -- '--network mfs-mw_dmz' .tool-calls || fail "ZAP not on the dmz network"
  grep -q 'https://apisix:9443/' .tool-calls || fail "ZAP not aimed at APISIX"
  grep -q 'logs --no-color' .tool-calls || fail "no log scan"
}

test_log_scan_covers_every_profile_so_the_seeder_is_scanned_too() {
  stub_live_docker 'ok'
  mkdir -p scripts/lib
  cp "$REPO_DIR/scripts/log-scan.sh" scripts/
  cp "$REPO_DIR/scripts/lib/modules.sh" scripts/lib/
  assert_ok env MW_ROOT="$PWD" bash scripts/log-scan.sh
  grep -q -- '--profile minimal --profile core --profile seed --profile tester ps -a' .tool-calls \
    || fail "log-scan does not list every profile's containers: $(cat .tool-calls)"
}

test_security_fails_on_a_leak_in_a_container_log() {
  stub_live_docker 'payer 01712345678'
  assert_fails security_in_fixture env
  assert_out_contains "logs"
  assert_out_lacks "01712345678"
}

test_security_fails_when_the_zap_gate_fails() {
  stub_live_docker 'ok'
  touch .zap-fail
  assert_fails security_in_fixture env
  assert_out_contains "zap:"
}

test_security_skips_live_stages_loudly_when_the_stack_is_down_unless_required() {
  stub_tools_down() {
    mkdir -p .bin
    for t in docker gitleaks trivy; do
      printf '#!/bin/sh\necho "%s $*" >> "%s/.tool-calls"\n' "$t" "$PWD" >".bin/$t"; chmod +x ".bin/$t"
    done
    PATH="$PWD/.bin:$PATH"
  }
  stub_tools_down
  mkdir -p deploy && touch deploy/docker-compose.yml
  assert_ok security_in_fixture env
  assert_out_contains "SKIPPED"
  assert_fails security_in_fixture env SECURITY_LIVE=1
  assert_out_contains "live:stack-down"
}

test_security_uses_scanner_images_when_the_binaries_are_missing() {
  mkdir -p .bin
  printf '#!/bin/sh\necho "docker $*" >> "%s/.tool-calls"\nexit 1\n' "$PWD" >.bin/docker; chmod +x .bin/docker
  for t in sh bash git tar sed sort basename dirname tr mkdir rm cat printf mktemp awk; do
    p="$(command -v "$t")" && ln -sf "$p" ".bin/$t"
  done
  OUT="$(PATH="$PWD/.bin" MW_ROOT="$PWD" SECURITY_LIVE=0 "$REPO_DIR/scripts/security.sh" 2>&1)"
  grep -q 'docker run .*gitleaks' .tool-calls || fail "gitleaks image not used: $(cat .tool-calls 2>/dev/null)"
}

test_zap_gate_fails_on_high_and_on_alerts_without_a_decision() {
  command -v python3 >/dev/null || return 0
  printf '10035\taccept\tHSTS set at the edge\n10021\taccept\tlisted\n' >decisions.tsv
  printf '{"site":[{"@name":"https://apisix:9443","alerts":[{"pluginid":"10035","alert":"HSTS","riskcode":"1","count":"2"}]}]}' >ok.json
  OUT="$(python3 "$REPO_DIR/scripts/lib/zap-gate.py" ok.json decisions.tsv 2>&1)" || fail "gate failed on an accepted low alert: $OUT"
  printf '{"site":[{"@name":"x","alerts":[{"pluginid":"10021","alert":"XCTO","riskcode":"3","count":"1"}]}]}' >high.json
  OUT="$(python3 "$REPO_DIR/scripts/lib/zap-gate.py" high.json decisions.tsv 2>&1)" && fail "gate passed a high alert"
  assert_out_contains "HIGH"
  printf '{"site":[{"@name":"x","alerts":[{"pluginid":"99999","alert":"New","riskcode":"2","count":"1"}]}]}' >new.json
  OUT="$(python3 "$REPO_DIR/scripts/lib/zap-gate.py" new.json decisions.tsv 2>&1)" && fail "gate passed an alert without a decision"
  assert_out_contains "no decision"
  OUT="$(python3 "$REPO_DIR/scripts/lib/zap-gate.py" missing.json decisions.tsv 2>&1)" && fail "gate passed without a report"
  return 0
}

test_every_zap_decision_and_trivy_ignore_has_a_reason() {
  local f="$REPO_DIR/deploy/zap/decisions.tsv"
  [ -f "$f" ] || fail "no deploy/zap/decisions.tsv"
  awk -F'\t' '!/^#/ && NF && (NF < 3 || $2 !~ /^(accept|fix)$/ || length($3) < 10) { print; bad = 1 } END { exit bad }' "$f" \
    || fail "decision lines need: pluginid<TAB>accept|fix<TAB>reason"
  local ign
  for ign in "$REPO_DIR/.trivyignore.yaml" "$REPO_DIR/deploy/trivyignore-third-party.yaml"; do
    [ -f "$ign" ] || fail "no $ign"
    awk '/id: (CVE|GHSA)-/ { n++ } /statement:/ { s++ } /expired_at:/ { e++ } END { exit !(n == s && n == e && n > 0) }' "$ign" \
      || fail "$ign: every accepted id needs a statement and an expiry"
  done
  grep -q 'docs/security-findings.md' "$REPO_DIR/scripts/security.sh" || fail "security.sh does not point at the findings register"
  [ -f "$REPO_DIR/docs/security-findings.md" ] || fail "no findings register"
}

# Spec 20.2 / WP29: every long-running container built from this repo (mw-*, the admin UI) runs as
# a non-root user on a read-only root FS, with no capabilities and no privilege escalation.
# Third-party platform images and the dev-only one-shot inits: docs/security-findings.md.
test_own_long_running_containers_are_non_root_and_read_only() {
  python3 -c 'import yaml' 2>/dev/null || return 0
  OUT="$(python3 "$REPO_DIR/scripts/test/hardening_check.py" "$REPO_DIR/deploy/docker-compose.yml" 2>&1)" \
    || fail "hardening missing: $OUT"
  printf 'services:\n  mw-x:\n    image: x\n' >c.yml
  OUT="$(python3 "$REPO_DIR/scripts/test/hardening_check.py" c.yml 2>&1)" && fail "an unhardened mw-x passed"
  assert_out_contains "mw-x: read_only"
}
