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
