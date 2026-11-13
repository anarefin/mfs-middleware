# scripts/chaos.sh ( spec WP27, acceptance criterion 3): kill each container mid-flow,
# then check that no txn is left non-terminal and the core-mock ledger is consistent.
# docker is a stub: `compose ... ps` prints .ps, `compose ... exec ... postgres psql` answers the
# SQL tagged `-- chaos:<tag>` from .sql-<tag>.<n> on the n-th call of that tag (the highest file
# once they run out, empty when there is none); every call is recorded in .tool-calls.

L='com.docker.compose.project=mvp'
O="$L,mw.oneshot=true"

stub_chaos() {
  mkdir -p .bin deploy
  touch deploy/docker-compose.yml
  cat >.bin/docker <<EOF
#!/bin/bash
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  *" ps "*|*" ps") cat "$PWD/.ps" ;;
  *" exec "*postgres*)
    sql="\$(cat)"
    tag="\$(printf '%s\n' "\$sql" | sed -n 's/.*-- chaos:\([a-z_]*\).*/\1/p' | head -1)"
    printf '%s\n' "\$tag" >> "$PWD/.sql-calls"; printf '%s\n' "\$sql" >> "$PWD/.sql-log"
    n=\$(( \$(cat "$PWD/.sqln-\$tag" 2>/dev/null || echo 0) + 1 )); echo \$n > "$PWD/.sqln-\$tag"
    f="$PWD/.sql-\$tag.\$n"
    [ -f "\$f" ] || f="\$(ls "$PWD"/.sql-\$tag.* 2>/dev/null | sort -t. -k3 -n | tail -1)"
    [ -n "\$f" ] && cat "\$f"
    ;;
esac
exit 0
EOF
  chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH"
  # the stack: two middleware services, their one-shots, the platform
  printf 'mw-orchestrator\trunning\thealthy\t0\t%s\nmw-orchestrator-init\texited\t\t0\t%s\nmw-core-mock\trunning\thealthy\t0\t%s\nkafka\trunning\thealthy\t0\t%s\npostgres\trunning\thealthy\t0\t%s\nkeycloak\trunning\thealthy\t0\t%s\n' \
    "$L" "$O" "$L" "$L" "$L" "$L" >.ps
  printf '2026-11-06 10:00:00+00\n' >.sql-now.1
  printf '1000000000000\n' >.sql-ledger_sum.1
}

# chaos [VAR=VAL...]: run scripts/chaos.sh against the stub with zero waits; output in OUT.
chaos() {
  OUT="$(env CHAOS_WARMUP=0 CHAOS_DOWN=0 CHAOS_POLL=0 CHAOS_LOAD_PAUSE=0 CHAOS_SETTLE_TIMEOUT=5 \
    CHAOS_RECOVER_TIMEOUT=5 CHAOS_LOAD_CMD="echo load >> $PWD/.load-calls" CHAOS_REPORT="$PWD/report.md" "$@" \
    MW_ROOT="$PWD" "$REPO_DIR/scripts/chaos.sh" 2>&1)"
}

test_chaos_kills_and_restarts_every_mw_service_then_kafka_and_postgres_in_turn() {
  stub_chaos
  chaos || fail "chaos failed on a clean stack"
  # one-shots and platform services other than kafka/postgres are never killed
  grep -q 'kill.*mw-orchestrator-init' .tool-calls && fail "killed a one-shot"
  grep -q 'kill.*keycloak' .tool-calls && fail "killed keycloak"
  got="$(awk '/ kill /{print $NF}' .tool-calls | tr '\n' ' ')"
  [ "$got" = "mw-core-mock mw-orchestrator kafka postgres " ] || fail "kill order: '$got' $(cat .tool-calls)"
  for s in mw-core-mock mw-orchestrator kafka postgres; do
    grep -q "compose .* start $s\$" .tool-calls || fail "$s not restarted"
  done
  # every kill is followed by its own start before the next kill
  order="$(awk '/ kill /{print "kill"} / start /{print "start"}' .tool-calls | tr '\n' ' ')"
  [ "$order" = "kill start kill start kill start kill start " ] || fail "kill/start interleaving: $order"
  [ -s .load-calls ] || fail "no load ran"
  assert_out_contains "PASS"
}

test_chaos_targets_can_be_chosen() {
  stub_chaos
  chaos CHAOS_TARGETS="kafka" || fail "chaos failed"
  got="$(grep -c ' kill ' .tool-calls)"
  [ "$got" = 1 ] || fail "killed $got containers, want 1"
  grep -q ' kill .*kafka$' .tool-calls || fail "kafka not killed"
}

test_chaos_waits_for_recovery_and_fails_when_a_target_does_not_come_back() {
  stub_chaos
  printf 'mw-orchestrator\texited\t\t137\t%s\nkafka\trunning\thealthy\t0\t%s\n' "$L" "$L" >.ps
  if chaos CHAOS_TARGETS="mw-orchestrator" CHAOS_RECOVER_TIMEOUT=0; then fail "passed with mw-orchestrator down"; fi
  assert_out_contains "mw-orchestrator"
  grep -qi 'not recovered\|did not recover' report.md || fail "report lacks the recovery failure: $(cat report.md)"
}

test_chaos_polls_until_no_txn_is_non_terminal() {
  stub_chaos
  printf 'UNKNOWN|2\nCOMMITTING|1\n' >.sql-nonterminal.1
  printf 'UNKNOWN|1\n' >.sql-nonterminal.2
  : >.sql-nonterminal.3
  chaos CHAOS_TARGETS="mw-orchestrator" || fail "chaos failed although the txns settled"
  [ "$(cat .sqln-nonterminal)" -ge 3 ] || fail "did not poll until settled ($(cat .sqln-nonterminal) polls)"
  grep -q '| mw-orchestrator |.*| PASS |' report.md || fail "report: $(cat report.md)"
}

test_chaos_fails_and_reports_txns_still_non_terminal_after_the_settle_timeout() {
  stub_chaos
  printf 'UNKNOWN|2\n' >.sql-nonterminal.1
  if chaos CHAOS_TARGETS="kafka" CHAOS_SETTLE_TIMEOUT=0; then fail "passed with UNKNOWN txns left"; fi
  assert_out_contains "UNKNOWN"
  grep -q '| kafka |.*UNKNOWN=2.*| FAIL |' report.md || fail "report: $(cat report.md)"
}

test_manual_review_counts_as_stuck_unless_allowed() {
  stub_chaos
  printf 'MANUAL_REVIEW|1\n' >.sql-nonterminal.1
  if chaos CHAOS_TARGETS="kafka" CHAOS_SETTLE_TIMEOUT=0; then fail "passed with a MANUAL_REVIEW txn"; fi
  grep -A3 'chaos:nonterminal' .sql-log | grep -q "'MANUAL_REVIEW'" && fail "MANUAL_REVIEW excluded by default"
  # allowed: the query leaves MANUAL_REVIEW out of the non-terminal states
  rm -f .sql-calls .sql-log .sqln-* .tool-calls .sql-nonterminal.*
  chaos CHAOS_TARGETS="kafka" CHAOS_ALLOW_MANUAL_REVIEW=1 || fail "failed with MANUAL_REVIEW allowed"
  grep -A3 'chaos:nonterminal' .sql-log | grep -q "'MANUAL_REVIEW'" || fail "MANUAL_REVIEW not excluded when allowed: $(cat .sql-log)"
}

test_chaos_fails_on_a_double_debit_or_credit_in_the_core_mock_ledger() {
  stub_chaos
  printf '0190a1b2-0000-7000-8000-000000000001|TRANSFER|2\n' >.sql-double.1
  if chaos CHAOS_TARGETS="mw-core-mock"; then fail "passed with a double posting"; fi
  assert_out_contains "0190a1b2-0000-7000-8000-000000000001"
  grep -q '| mw-core-mock |.*double.*| FAIL |' report.md || fail "report: $(cat report.md)"
}

test_chaos_fails_when_the_sum_of_balances_changed() {
  stub_chaos
  printf '1000000000000\n' >.sql-ledger_sum.1
  printf '999999990000\n' >.sql-ledger_sum.2
  if chaos CHAOS_TARGETS="postgres"; then fail "passed with money created or destroyed"; fi
  grep -q '| postgres |.*sum -10000.*| FAIL |' report.md || fail "report: $(cat report.md)"
}

test_chaos_fails_on_unbalanced_entries_leaked_holds_or_saga_ledger_mismatch() {
  for tag in unbalanced held mismatch; do
    rm -rf .bin .ps .sql* .tool-calls report.md
    stub_chaos
    printf '0190a1b2-0000-7000-8000-00000000000a|x\n' >".sql-$tag.1"
    if chaos CHAOS_TARGETS="kafka"; then fail "passed with a $tag finding"; fi
    grep -q "| kafka |.*$tag.*| FAIL |" report.md || fail "$tag: report: $(cat report.md)"
  done
  return 0
}

test_chaos_only_counts_txns_created_since_the_run_started() {
  stub_chaos
  chaos CHAOS_TARGETS="kafka" || fail "chaos failed"
  grep -q 'nonterminal' .sql-calls || fail "never asked for non-terminal txns"
  grep -A3 'chaos:nonterminal' .sql-log | grep -q "2026-11-06 10:00:00+00" || fail "non-terminal query not limited to the run: $(cat .sql-log)"
}

test_chaos_report_lists_every_round_and_the_verdict() {
  stub_chaos
  chaos CHAOS_TARGETS="mw-orchestrator kafka" || fail "chaos failed"
  [ -f report.md ] || fail "no report written"
  grep -q '| mw-orchestrator |' report.md || fail "no mw-orchestrator row"
  grep -q '| kafka |' report.md || fail "no kafka row"
  grep -qi 'verdict.*PASS' report.md || fail "no overall verdict: $(cat report.md)"
  assert_out_contains "report.md"
}

test_chaos_stops_the_load_before_it_waits_for_the_sweeps() {
  # settling is checked with no new traffic: the load loop is gone once polling starts
  stub_chaos
  printf 'UNKNOWN|1\n' >.sql-nonterminal.1
  : >.sql-nonterminal.2
  chaos CHAOS_TARGETS="kafka" CHAOS_LOAD_CMD="sleep 30" || fail "chaos failed: load not stopped?"
  pgrep -f "sleep 30" >/dev/null && { pkill -f "sleep 30"; fail "load still running after the run"; }
  return 0
}

test_chaos_fails_without_the_mvp_compose_file() {
  mkdir -p .bin
  if chaos; then fail "chaos passed without a stack"; fi
  assert_out_contains "compose.yml"
}
