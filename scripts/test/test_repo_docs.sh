# Repo memory and PR template (spec "Ground rules for agent work", WP01).

claude_md_has() {
  grep -qiE -- "$1" "$REPO_DIR/CLAUDE.md" || fail "CLAUDE.md lacks: $1"
}

test_claude_md_records_the_conventions() {
  [ -f "$REPO_DIR/CLAUDE.md" ] || fail "no root CLAUDE.md"
  claude_md_has '^#+ .*package layout'
  claude_md_has 'mfs\.mw\.<service>'
  for p in config controller dto service repository entity mapper exception messaging; do
    claude_md_has "^ +$p/ "
  done
  claude_md_has '^#+ .*error model'
  claude_md_has 'MW-VAL-' ; claude_md_has 'MW-PTR-' ; claude_md_has 'retryable'
  claude_md_has '^#+ .*masking'
  claude_md_has '0171\*\*\*\*789' ; claude_md_has '@Sensitive'
  claude_md_has '^#+ .*log fields'
  claude_md_has 'correlationId' ; claude_md_has 'durationMs' ; claude_md_has 'errorCode'
  claude_md_has '^#+ .*test commands'
  claude_md_has 'make verify' ; claude_md_has 'M=' ; claude_md_has 'make check-isolation'
}

test_claude_md_lists_the_never_rules() {
  claude_md_has '^#+ .*never'
  claude_md_has 'secrets? in code'
  claude_md_has 'System\.out'
  claude_md_has 'field injection'
  claude_md_has 'block.*Kafka (listener |consumer )?threads?'
}

test_pr_template_has_test_report_and_reviewer_checklist() {
  t="$REPO_DIR/.github/pull_request_template.md"
  [ -f "$t" ] || fail "no PR template"
  grep -qiE '^#+ .*test report' "$t" || fail "no test-report section"
  grep -qE '^- \[ \] .*make verify' "$t" || fail "no reviewer checklist item to re-run make verify"
  grep -qiE 'reviewer' "$t" || fail "no reviewer checklist"
}

test_pr_template_has_the_spec_definition_of_done() {
  t="$REPO_DIR/.github/pull_request_template.md"
  # spec 23.2 items the agent can tick on a PR
  grep -qiE '^- \[ \] .*coverage gate' "$t" || fail "no 'coverage gate met' item"
  grep -qiE '^- \[ \] .*masking verified for (any|every) new field' "$t" || fail "no 'masking verified' item"
  grep -qiE '^- \[ \] .*metrics.*log events.*alert rule' "$t" || fail "no 'metrics, log events, alert rule' item"
}

spec_has() {
  grep -qE -- "$1" "$REPO_DIR/spec/upay-mfs-middleware-spec.md" || fail "spec lacks: $1"
}

test_spec_records_the_m0_review_decisions() {
  # Ticket 49 (M0 review, 2026-11-03).
  local f="$REPO_DIR/spec/upay-mfs-middleware-spec.md"
  spec_has '^\| Framework \| Spring Boot \| 4\.1\.2 '
  spec_has '^\| Integration \| Apache Camel .*\| 4\.22\.2 LTS '
  spec_has '^\| .*Spring Cloud.* \| 2025\.1\.4 '
  spec_has '^\| Migrations \| Flyway \| 12'
  spec_has '^\| Scheduling \| ShedLock .*\| 7'
  spec_has '^\| Testing \| JUnit 6,'
  spec_has '^\| .*Kafka ACLs.*quotas'
  spec_has '^\| .*image digest pinning'
  spec_has '^\| .*OpenBao root token.*data volume.*dev only'
  spec_has 'effectively-once \(idempotent producer \+ outbox \+ inbox\)'
  spec_has 'minimum log field set; extra structured fields are allowed when masked'
  spec_has '^\| Circuit breaker \| 60 s time-based window, minimum 50 calls'
  if grep -q 'idempotent producers + transactions' "$f"; then fail "spec still says Kafka transactions"; fi
  if grep 'make verify' "$f" | grep -qi 'static analysis'; then fail "make verify still lists static analysis"; fi
  if grep -qE 'JUnit 5|junit5' "$f"; then fail "spec still says JUnit 5"; fi
}

test_spec_has_no_static_analysis_or_sast_requirement() {
  # M0 decision: no static analysis and no SAST; review, gitleaks and trivy only.
  local f="$REPO_DIR/spec/upay-mfs-middleware-spec.md" hits
  hits=$(grep -niE 'static[- ]analysis|\bSAST\b|SpotBugs|FindSecBugs' "$f") \
    && fail "spec still requires static analysis or SAST: $hits"
  spec_has 'gitleaks|[Ss]ecret scan'
  spec_has '[Tt]rivy|[Cc]ontainer scan'
}

test_spec_records_keycloak_on_the_data_network_as_an_mvp_deviation() {
  spec_has 'Keycloak \(Operation zone\) also joins the `data` network.*PostgreSQL.*MVP single-host deviation'
  grep -A4 '^  keycloak:' "$REPO_DIR/deploy/compose/platform/compose.yml" | grep -qE 'networks: \[ops, data\]' \
    || fail "keycloak compose networks changed; update spec 18.1"
}

test_spec_kafka_contract_matches_the_contract_schemas() {
  # Spec 6.2/6.3 must describe the envelope + payload shape in contracts/events.
  local f="$REPO_DIR/spec/upay-mfs-middleware-spec.md" c="$REPO_DIR/contracts/events"
  spec_has 'Every event carries the envelope `\{ schemaVersion, eventType, eventId, commandId, txnId, coreRef, occurredAt, outcome, reasonCode\?, reasonMessage\?, balancesAfter\?, payload \}`'
  spec_has '`payload\.holdId`' ; spec_has '`payload\.txnStatus`' ; spec_has '`payload\.fee`' ; spec_has '`payload\.limitsLeft`'
  spec_has 'non-negative integer minor units'
  spec_has '^\| `mw\.partner\.reply\.v1` \|.*`family`'
  spec_has '^\| `mw\.partner\.inbound\.v1` \|.*`family`'
  if grep -qF 'Every event carries `{ eventId, commandId' "$f"; then fail "spec still shows the flat event shape"; fi
  # the facts the spec states, checked against the schemas themselves
  grep -q '"holdId"' "$c/mw-core-mock/core.event.v1/FundsHeld.payload.schema.json" || fail "FundsHeld payload lost holdId"
  grep -q '"txnStatus"' "$c/mw-core-mock/core.event.v1/TransactionStatus.payload.schema.json" || fail "TransactionStatus payload lost txnStatus"
  grep -q '"fee"' "$c/mw-core-mock/core.event.v1/TransactionValidated.payload.schema.json" || fail "TransactionValidated payload lost fee"
  grep -q '"limitsLeft"' "$c/mw-core-mock/core.event.v1/TransactionValidated.payload.schema.json" || fail "TransactionValidated payload lost limitsLeft"
  grep -qF '"valueMinor": { "type": "integer", "minimum": 0 }' "$REPO_DIR/contracts/common/defs.v1.schema.json" \
    || fail "Amount is no longer non-negative minor units"
  for t in reply inbound; do
    grep -qF '"family": { "enum": ["telco", "billpay", "bank", "interop", "compliance"] }' \
      "$c/mw-orchestrator/mw.partner.$t.v1.schema.json" || fail "mw.partner.$t.v1 has no family enum"
  done
}
