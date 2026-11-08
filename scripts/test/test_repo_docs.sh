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
  grep -qE -- "$1" "$REPO_DIR/spec/bits-mfs-middleware-spec.md" || fail "spec lacks: $1"
}

test_spec_records_the_m0_review_decisions() {
  # Ticket 49 (M0 review, 2026-11-03).
  local f="$REPO_DIR/spec/bits-mfs-middleware-spec.md"
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
  local f="$REPO_DIR/spec/bits-mfs-middleware-spec.md" hits
  hits=$(grep -niE 'static[- ]analysis|\bSAST\b|SpotBugs|FindSecBugs' "$f") \
    && fail "spec still requires static analysis or SAST: $hits"
  spec_has 'gitleaks|[Ss]ecret scan'
  spec_has '[Tt]rivy|[Cc]ontainer scan'
}

test_spec_records_keycloak_on_the_data_network_as_an_mvp_deviation() {
  spec_has 'Keycloak \(Operation zone\) also joins the `data` network.*PostgreSQL.*MVP single-host deviation'
  grep -A4 '^  keycloak:' "$REPO_DIR/deploy/docker-compose.yml" | grep -qE 'networks: \[ops, data\]' \
    || fail "keycloak compose networks changed; update spec 18.1"
}

test_spec_kafka_contract_matches_the_contract_schemas() {
  # Spec 6.2/6.3 must describe the envelope + payload shape in contracts/events.
  local f="$REPO_DIR/spec/bits-mfs-middleware-spec.md" c="$REPO_DIR/contracts/events"
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

# ---- Operator docs (ticket 47, spec WP30, MVP acceptance criterion 6) ----
# README quick start, docs/runbook.md, docs/demo.md and docs/adr/: every command, path, folder and
# knob they name must be real, so a person not on the team can follow them on a clean host.

op_docs() { # the operator docs, repo-relative
  local f
  # globs expand under REPO_DIR: the runner calls the tests from a scratch directory
  for f in "$REPO_DIR"/README.md "$REPO_DIR"/docs/runbook.md "$REPO_DIR"/docs/demo.md \
           "$REPO_DIR"/docs/testing-guide.html "$REPO_DIR"/docs/tutorial/*.html "$REPO_DIR"/docs/adr/*.md; do
    [ -f "$f" ] && printf '%s\n' "${f#"$REPO_DIR"/}"
  done
  return 0
}

# doc_text FILE: the doc as the checks read it. Markdown as is; HTML with <code> turned into
# backticks, href="x" into a markdown link ](x), every other tag dropped and entities decoded.
doc_text() {
  case "$1" in
    *.html) sed -E -e 's#</?code>#`#g' -e 's#href="([^"]*)"#>](\1)<#g' -e 's#<[^>]*>##g' \
                   -e 's#&lt;#<#g; s#&gt;#>#g; s#&nbsp;# #g; s#&amp;#\&#g' "$1" ;;
    *) cat "$1" ;;
  esac
}

makefile_targets() {
  sed -n 's/^\.PHONY: *//p' "$REPO_DIR/Makefile" | tr ' ' '\n' | grep -v '^$'
}

test_readme_has_a_clean_host_quick_start() {
  local r="$REPO_DIR/README.md" d
  [ -f "$r" ] || fail "no root README.md"
  grep -qiE '^#+ .*quick start' "$r" || fail "README has no quick start section"
  grep -qiE '^#+ .*prerequisites' "$r" || fail "README lists no prerequisites"
  grep -qE 'JDK 25' "$r" || fail "README does not name JDK 25 (bootJar runs on the host)"
  grep -qE 'Docker' "$r" || fail "README does not name Docker"
  grep -qF 'make up' "$r" || fail "README quick start lacks make up"
  grep -qF 'make smoke' "$r" || fail "README quick start lacks make smoke"
  grep -qF 'https://localhost/console' "$r" || fail "README does not point at the admin console"
  grep -qiE '15 min' "$r" || fail "README does not state the 15 minute bring-up target"
  for d in docs/runbook.md docs/demo.md docs/adr/README.md; do
    grep -qF "($d)" "$r" || fail "README does not link $d"
  done
}

test_readme_lists_every_makefile_target() {
  local t
  for t in $(makefile_targets); do
    [ "$t" = help ] && continue
    grep -qE "make $t([^a-z-]|\$)" "$REPO_DIR/README.md" || fail "README does not mention make $t"
  done
}

test_every_make_target_in_the_docs_exists() {
  local f t targets
  targets=" $(makefile_targets | tr '\n' ' ') "
  for f in $(op_docs); do
    for t in $(doc_text "$REPO_DIR/$f" | grep -oE '(^|[^a-z0-9-])make [a-z][a-z0-9-]*' | sed 's/.*make //' | sort -u); do
      case "$targets" in *" $t "*) ;; *) fail "$f names make $t, which the Makefile has no target for" ;; esac
    done
  done
}

test_every_repo_path_in_the_docs_exists() {
  local f p
  for f in $(op_docs); do
    # backticked repo paths (placeholders such as <x> or globs end the match and are skipped)
    for p in $(doc_text "$REPO_DIR/$f" | grep -oE '`(scripts|deploy|services|tests|docs|contracts|ui|spec|templates)/[A-Za-z0-9._/-]*`' \
               | tr -d '`' | sed 's#/$##' | sort -u); do
      # generated output (build/, the dev .env) is gitignored and appears only after a run
      git -C "$REPO_DIR" check-ignore -q --no-index "$p" 2>/dev/null && continue
      [ -e "$REPO_DIR/$p" ] || fail "$f names $p, which does not exist"
    done
    # relative markdown links
    for p in $(doc_text "$REPO_DIR/$f" | grep -oE '\]\([^)#:]+(#[^)]*)?\)' | sed -E 's/^\]\(//; s/\)$//; s/#.*//' | sort -u); do
      [ -e "$(dirname "$REPO_DIR/$f")/$p" ] || fail "$f links $p, which does not exist"
    done
  done
}

test_every_api_test_folder_in_the_docs_exists() {
  # FOLDER= of make api-test: folder numbers (tests/api/src/NN-*) or full folder names, comma-separated
  local f v part n names
  names="$(sed -n 's/^ *"name": *"\(.*\)",\{0,1\}$/\1/p' "$REPO_DIR"/tests/api/src/*/folder.json 2>/dev/null)"
  for f in $(op_docs); do
    while IFS= read -r v; do
      # placeholders ([FOLDER=], FOLDER=<folders>) are not folders
      case "$v" in '' | ']'* | '<'*) continue ;; esac
      IFS=',' read -r -a parts <<<"$v"
      for part in "${parts[@]}"; do
        part="$(printf '%s' "$part" | sed -e 's/^ *//' -e 's/ *$//')"
        [ -z "$part" ] && continue
        if printf '%s' "$part" | grep -qE '^[0-9 ]+$'; then
          for n in $part; do
            ls -d "$REPO_DIR/tests/api/src/$n-"* >/dev/null 2>&1 || fail "$f uses FOLDER $n, which tests/api/src has no folder for"
          done
        else
          printf '%s\n' "$names" | grep -qxF -- "$part" || fail "$f uses FOLDER '$part', which is no folder name of the API suite"
        fi
      done
    done < <(doc_text "$REPO_DIR/$f" | grep -oE 'FOLDER=("[^"]*"|[^ `]*)' | sed -E 's/^FOLDER=//; s/^"//; s/"$//' | sort -u)
  done
}

test_every_knob_in_the_docs_is_read_by_the_tooling() {
  # a VAR=value setting shown in a command line must be read by the Makefile, a script or deploy/
  local f v
  for f in $(op_docs); do
    for v in $(doc_text "$REPO_DIR/$f" | grep -E '(make [a-z]|scripts/|docker compose)' | grep -oE '(^|[ `(])[A-Z][A-Z0-9_]+=' \
               | sed -E 's/^[ `(]//; s/=$//' | sort -u); do
      grep -rqw -- "$v" "$REPO_DIR/Makefile" "$REPO_DIR/scripts" "$REPO_DIR/deploy" "$REPO_DIR/tests/api/run.sh" \
        || fail "$f sets $v, which neither the Makefile, scripts/, deploy/ nor tests/api/run.sh reads"
    done
  done
}

test_adrs_cover_every_decision_in_the_spec_tables() {
  local id file ids s idx="$REPO_DIR/docs/adr/README.md"
  ids="$(grep -oE '^\| (D|M)[0-9]+ \|' "$REPO_DIR/spec/bits-mfs-middleware-spec.md" | grep -oE '(D|M)[0-9]+')"
  [ "$(printf '%s\n' "$ids" | wc -l | tr -d ' ')" -ge 28 ] || fail "spec decision tables not found"
  [ -f "$idx" ] || fail "no docs/adr/README.md index"
  for id in $ids; do
    file="$(ls "$REPO_DIR/docs/adr/$id-"*.md 2>/dev/null | head -1)"
    [ -n "$file" ] || fail "no ADR for $id (docs/adr/$id-<slug>.md)"
    head -1 "$file" | grep -qE "^# $id: " || fail "$(basename "$file") does not start with '# $id: '"
    grep -qE '^Status: ' "$file" || fail "$(basename "$file") has no Status line"
    for s in Context Decision Consequences; do
      grep -qE "^## $s" "$file" || fail "$(basename "$file") has no $s section"
    done
    grep -qF "($(basename "$file"))" "$idx" || fail "docs/adr/README.md does not link $(basename "$file")"
  done
}

test_demo_script_has_a_section_per_milestone() {
  local d="$REPO_DIR/docs/demo.md" m
  [ -f "$d" ] || fail "no docs/demo.md"
  for m in $(grep -oE '^\| WP[0-9]+ \| M[0-9]' "$REPO_DIR/spec/bits-mfs-middleware-spec.md" | grep -oE 'M[0-9]$' | sort -u); do
    grep -qE "^## $m " "$d" || fail "docs/demo.md has no section for milestone $m"
  done
}

test_spec_names_the_next_version_the_admin_ui_runs() {
  local v s="$REPO_DIR/spec/bits-mfs-middleware-spec.md"
  v="$(sed -nE 's/^ *"next": *"([0-9.]+)".*/\1/p' "$REPO_DIR/ui/mw-admin-ui/package.json")"
  [ -n "$v" ] || fail "no next version in ui/mw-admin-ui/package.json"
  grep -qF "| D6 | Admin console | Next.js $v " "$s" || fail "spec D6 does not say Next.js $v"
  grep -E '^\| Admin UI \|' "$s" | grep -qF "| $v |" || fail "spec section 3 does not say $v"
  if grep -oE 'Next\.js [0-9]+\.[0-9]+\.[0-9]+' "$s" | grep -vqxF "Next.js $v"; then
    fail "spec names a Next.js version other than $v"
  fi
}
