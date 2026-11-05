#!/usr/bin/env bash
# Chaos run against the running MVP stack (spec WP27, MVP acceptance criterion 3): "killing any
# one middleware container mid-flow leaves no transaction in a non-terminal state after restart
# and sweeps". Run on a fresh, healthy stack: make down DOWN_ARGS=-v && make up && make chaos.
#
# For every target in turn (default: every long-running mw-* service, then kafka, then postgres):
#   1. start mixed load (a loop of the e2e suite, CHAOS_LOAD_CMD) in the background
#   2. after CHAOS_WARMUP s (txns in flight) SIGKILL the target (docker compose kill), keep it
#      down CHAOS_DOWN s, start it again, wait until every long-running service is healthy
#   3. stop the load (lets the running suite pass finish, CHAOS_LOAD_STOP_TIMEOUT), then wait up
#      to CHAOS_SETTLE_TIMEOUT s for the timer sweeps and TSQ to bring every txn created since
#      the run started into a terminal state (COMPLETED, FAILED, REJECTED, REVERSED;
#      MANUAL_REVIEW counts as stuck unless CHAOS_ALLOW_MANUAL_REVIEW=1)
#   4. check the core-mock ledger: the sum of all balances is unchanged (money is never created
#      or destroyed), no txn is posted twice (two commands with entries of one kind: a double
#      debit or credit), every txn's debits equal its credits, every wallet's held amount equals
#      its HELD holds and no hold stays HELD for a terminal txn, no FAILED/REJECTED txn moved money
#      (unless reversed) and every COMPLETED txn did
# Writes a Markdown report (CHAOS_REPORT, default build/chaos/chaos-report-<UTC>.md) to attach to
# the PR; exits 1 when any round failed.
#
# Knobs (environment): CHAOS_TARGETS="mw-orchestrator kafka", CHAOS_LOAD_CMD, CHAOS_LOAD_TAGS,
# CHAOS_WARMUP (60), CHAOS_DOWN (15), CHAOS_RECOVER_TIMEOUT (300), CHAOS_SETTLE_TIMEOUT (900: the
# TSQ schedule's 30 s, 1, 2, 5 min slots), CHAOS_POLL (10), CHAOS_LOAD_PAUSE (2),
# CHAOS_LOAD_STOP_TIMEOUT (300), CHAOS_ALLOW_MANUAL_REVIEW (0). Reads the databases with the
# postgres superuser through docker compose exec, read-only queries (as the e2e suite does).
set -uo pipefail
. "$(dirname "$0")/lib/modules.sh"

file="$MW_ROOT/deploy/compose/mvp/compose.yml"
[ -f "$file" ] || die "chaos: deploy/compose/mvp/compose.yml does not exist"
compose_files "$file"
env_file="$MW_ROOT/deploy/compose/platform/.env"
if [ -f "$env_file" ]; then set -a; . "$env_file"; set +a; fi

warmup="${CHAOS_WARMUP:-60}"
down_for="${CHAOS_DOWN:-15}"
recover_timeout="${CHAOS_RECOVER_TIMEOUT:-300}"
settle_timeout="${CHAOS_SETTLE_TIMEOUT:-900}"
poll="${CHAOS_POLL:-10}"
load_pause="${CHAOS_LOAD_PAUSE:-2}"
load_stop_timeout="${CHAOS_LOAD_STOP_TIMEOUT:-300}"
load_tags="${CHAOS_LOAD_TAGS:-(@p2p or @cash or @topup or @billpay or @addmoney) and not @tsq}"
load_cmd="${CHAOS_LOAD_CMD:-\"$MW_ROOT/scripts/suite.sh\" e2e \"\" \"$load_tags\"}"
report="${CHAOS_REPORT:-$MW_ROOT/build/chaos/chaos-report-$(date -u +%Y%m%dT%H%M%SZ).md}"
work="$(mktemp -d "${TMPDIR:-/tmp}/mwchaos.XXXXXX")"
mkdir -p "$(dirname "$report")"

terminal="'COMPLETED','FAILED','REJECTED','REVERSED'"
[ "${CHAOS_ALLOW_MANUAL_REVIEW:-0}" = 1 ] && terminal="$terminal,'MANUAL_REVIEW'"

dc() { docker compose "${DC_F[@]}" "$@"; }

# ---- stack state (the same reading of `compose ps` as scripts/up.sh) ----
ps_lines() {
  dc ps -a --format '{{.Service}}	{{.State}}	{{.Health}}	{{.ExitCode}}	{{.Labels}}' | tr '\t' '|'
}
# not_ready: "<svc>(<state>)" for every long-running service that is not healthy (or running
# without a healthcheck); one-shots are ignored (they ran at make up and stay exited).
not_ready() {
  ps_lines | while IFS='|' read -r svc state health _ labels; do
    [ -n "$svc" ] || continue
    case ",$labels," in *,mw.oneshot=true,*) continue ;; esac
    case "$state/$health" in
      */healthy | running/) ;;
      *) printf '%s(%s) ' "$svc" "${health:-$state}" ;;
    esac
  done
}
default_targets() {
  ps_lines | while IFS='|' read -r svc _ _ _ labels; do
    case ",$labels," in *,mw.oneshot=true,*) continue ;; esac
    case "$svc" in mw-*) printf '%s\n' "$svc" ;; esac
  done | sort -u
  printf 'kafka\npostgres\n'
}

# ---- SQL (tagged "-- chaos:<tag>" so a run log shows which check asked) ----
psql_q() {
  printf '%s\n' "$1" | dc exec -T -e PGTZ=UTC postgres \
    sh -c 'psql -X -q -At -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$POSTGRES_DB"'
}
q_now() { psql_q "-- chaos:now
select now();"; }
q_ledger_sum() { psql_q "-- chaos:ledger_sum
select coalesce(sum(balance_minor), 0) from coremock.wallet;"; }
q_nonterminal() { psql_q "-- chaos:nonterminal
select status, count(*) from txn.transaction where created_at >= '$start_ts'::timestamptz
  and status not in ($terminal) group by status order by status;"; }
q_double() { psql_q "-- chaos:double
select txn_id, kind, count(distinct command_id) from coremock.entry
where created_at >= '$start_ts'::timestamptz and kind in ('TRANSFER', 'FEE')
group by txn_id, kind having count(distinct command_id) > 1 order by txn_id;"; }
q_unbalanced() { psql_q "-- chaos:unbalanced
select txn_id, 'debits ' || sum(case direction when 'DEBIT' then amount_minor else 0 end)
  || ' credits ' || sum(case direction when 'CREDIT' then amount_minor else 0 end)
from coremock.entry where created_at >= '$start_ts'::timestamptz group by txn_id
having sum(case direction when 'DEBIT' then amount_minor else -amount_minor end) <> 0 order by txn_id;"; }
q_held() { psql_q "-- chaos:held
select w.id, 'held ' || w.held_minor || ' but HELD holds ' || coalesce(h.s, 0) from coremock.wallet w
  left join (select wallet_id, sum(amount_minor + fee_minor) s from coremock.hold where status = 'HELD'
             group by wallet_id) h on h.wallet_id = w.id
  where w.held_minor <> coalesce(h.s, 0)
union all
select h.txn_id, 'hold ' || h.id || ' still HELD, txn ' || t.status from coremock.hold h
  join txn.transaction t on t.txn_id::text = h.txn_id
  where h.status = 'HELD' and t.status in ($terminal);"; }
q_mismatch() { psql_q "-- chaos:mismatch
select t.txn_id, t.status || ' but the core moved money' from txn.transaction t
  where t.created_at >= '$start_ts'::timestamptz and t.status in ('FAILED', 'REJECTED')
    and exists (select 1 from coremock.entry e where e.txn_id = t.txn_id::text and e.kind <> 'REVERSAL')
    and not exists (select 1 from coremock.reversal r where r.original_txn_id = t.txn_id::text)
union all
select t.txn_id, 'COMPLETED but the core has no entries' from txn.transaction t
  where t.created_at >= '$start_ts'::timestamptz and t.status = 'COMPLETED'
    and not exists (select 1 from coremock.entry e where e.txn_id = t.txn_id::text);"; }

# ---- load ----
load_pid=""
kill_tree() {
  local p c
  for p in "$@"; do
    for c in $(pgrep -P "$p" 2>/dev/null); do kill_tree "$c"; done
    kill "$p" 2>/dev/null
  done
}
start_load() {
  rm -f "$work/stop"
  (
    while :; do
      eval "$load_cmd" >>"$work/load.log" 2>&1 || true
      [ -f "$work/stop" ] && break
      sleep "$load_pause"
      [ -f "$work/stop" ] && break
    done
  ) &
  load_pid=$!
}
stop_load() {
  [ -n "$load_pid" ] || return 0
  touch "$work/stop"
  local t0=$SECONDS
  while kill -0 "$load_pid" 2>/dev/null && [ $((SECONDS - t0)) -lt "$load_stop_timeout" ]; do sleep 1; done
  kill -0 "$load_pid" 2>/dev/null && kill_tree "$load_pid"
  wait "$load_pid" 2>/dev/null
  load_pid=""
}
trap 'stop_load; rm -rf "$work"' EXIT

# cell TEXT: one Markdown table cell (no pipes, no newlines, at most 3 rows of detail)
cell() { printf '%s\n' "$1" | sed '/^$/d' | head -3 | tr '|' ' ' | tr '\n' ';' | sed 's/;$//; s/;/; /g'; }

# ---- run ----
start_ts="$(q_now)" || die "chaos: cannot reach postgres (is the stack up? make up)"
[ -n "$start_ts" ] || die "chaos: postgres returned no time (is the stack up? make up)"
baseline="$(q_ledger_sum)" || die "chaos: cannot read the core-mock ledger"
targets="${CHAOS_TARGETS:-$(default_targets | tr '\n' ' ')}"
info "chaos: targets: $targets"
info "chaos: run starts at $start_ts, ledger sum $baseline"

rows=""
failed_rounds=0
for t in $targets; do
  info "chaos: [$t] load for ${warmup}s, then kill"
  start_load
  sleep "$warmup"
  dc kill "$t" >/dev/null 2>&1
  info "chaos: [$t] killed; down for ${down_for}s"
  sleep "$down_for"
  dc start "$t" >/dev/null 2>&1
  r0=$SECONDS
  findings=""
  while pending="$(not_ready)" && [ -n "$pending" ] && [ $((SECONDS - r0)) -lt "$recover_timeout" ]; do sleep "$poll"; done
  recovered=$((SECONDS - r0))
  if [ -n "$pending" ]; then
    findings="did not recover in ${recover_timeout}s: ${pending% }"
    recovered="-"
    info "chaos: [$t] not recovered: ${pending% }"
  else
    info "chaos: [$t] stack healthy again after ${recovered}s"
  fi
  stop_load

  s0=$SECONDS
  while :; do
    nonterminal="$(q_nonterminal)" && [ -z "$nonterminal" ] && break
    [ $((SECONDS - s0)) -ge "$settle_timeout" ] && break
    sleep "$poll"
  done
  settled=$((SECONDS - s0))
  stuck="0"
  if [ -n "$nonterminal" ]; then
    stuck="$(printf '%s\n' "$nonterminal" | sed '/^$/d' | tr '|' '=' | tr '\n' ' ')"; stuck="${stuck% }"
    settled="-"
    findings="${findings:+$findings; }non-terminal after ${settle_timeout}s: $stuck"
    info "chaos: [$t] txns still non-terminal: $stuck"
  fi

  sum="$(q_ledger_sum)" || sum=""
  if [ "$sum" != "$baseline" ]; then
    if [ -n "$sum" ] && [ -n "$baseline" ]; then delta=$((sum - baseline)); else delta="unreadable"; fi
    findings="${findings:+$findings; }sum $delta"
    info "chaos: [$t] ledger sum changed by $delta"
  fi
  for check in double unbalanced held mismatch; do
    out="$("q_$check" 2>&1)" || out="query failed: $out"
    if [ -n "$out" ]; then
      findings="${findings:+$findings; }$check: $(cell "$out")"
      info "chaos: [$t] $check:"; printf '%s\n' "$out" | head -10 | sed 's/^/     | /'
    fi
  done

  verdict=PASS
  if [ -n "$findings" ]; then verdict=FAIL; failed_rounds=$((failed_rounds + 1)); fi
  info "chaos: [$t] $verdict"
  rows="$rows| $t | $recovered | $settled | $stuck | ${findings:--} | $verdict |
"
done

overall=PASS; [ "$failed_rounds" -eq 0 ] || overall=FAIL
{
  printf '# Chaos report (spec WP27, acceptance criterion 3)\n\n'
  printf -- '- Run started: %s (txns created since then are checked)\n' "$start_ts"
  printf -- '- Load: `%s`\n' "$load_cmd"
  printf -- '- Warm-up %ss, down %ss, recovery limit %ss, settle limit %ss; MANUAL_REVIEW allowed: %s\n' \
    "$warmup" "$down_for" "$recover_timeout" "$settle_timeout" "${CHAOS_ALLOW_MANUAL_REVIEW:-0}"
  printf -- '- Ledger sum at start: %s\n\n' "$baseline"
  printf '| Killed | Recovered (s) | Settled (s) | Non-terminal | Findings | Verdict |\n'
  printf '| --- | --- | --- | --- | --- | --- |\n'
  printf '%s' "$rows"
  printf '\n**Verdict: %s** (%s of %s rounds failed)\n' "$overall" "$failed_rounds" "$(set -- $targets; echo $#)"
} >"$report"

info "chaos: report $report"
info "chaos: $overall"
[ "$overall" = PASS ]
