# Platform Compose stack (WP02): static checks on the resolved Compose model and on the
# topics-as-code plan. They need the docker CLI (compose config works without a daemon)
# and jq. The live checks are in scripts/smoke.sh (make smoke).

MVP_FILE="$REPO_DIR/deploy/compose/mvp/compose.yml"
PLATFORM_SERVICES="kafka kafka-init postgres valkey keycloak openbao etcd apisix apisix-init greenmail"
ONE_SHOT="kafka-init apisix-init"

# compose_json: the fully resolved MVP project as JSON (default env, so MW_HTTPS_PORT=443).
compose_json() {
  env -u MW_HTTPS_PORT docker compose -f "$MVP_FILE" config --format json 2>/dev/null
}

test_mvp_project_includes_every_platform_service() {
  [ -f "$MVP_FILE" ] || fail "missing deploy/compose/mvp/compose.yml"
  grep -q 'platform/compose.yml' "$MVP_FILE" || fail "mvp project does not include the platform file"
  local services
  services="$(compose_json | jq -r '.services | keys[]')" || fail "compose config failed"
  for s in $PLATFORM_SERVICES; do
    printf '%s\n' "$services" | grep -qx "$s" || fail "service $s missing (have: $services)"
  done
}

test_zone_networks_exist_and_postgres_is_data_only() {
  local j
  j="$(compose_json)"
  for n in dmz app data ops; do
    printf '%s' "$j" | jq -e --arg n "$n" '.networks | has($n)' >/dev/null || fail "network $n missing"
  done
  [ "$(printf '%s' "$j" | jq -c '.services.postgres.networks | keys')" = '["data"]' ] || fail "postgres must join only data"
  [ "$(printf '%s' "$j" | jq -c '.services.apisix.networks | has("dmz")')" = 'true' ] || fail "apisix must join dmz"
  printf '%s' "$j" | jq -e '[.services[] | select(.networks | has("dmz"))] | length == 1' >/dev/null \
    || fail "only apisix may join dmz"
}

test_only_apisix_publishes_a_host_port_and_it_is_443() {
  local published
  published="$(compose_json | jq -r '.services | to_entries[] | select(.value.ports) | .key as $s | .value.ports[] | "\($s):\(.published)->\(.target)"')"
  [ "$published" = "apisix:443->9443" ] || fail "unexpected published ports: $published"
}

test_https_host_port_is_overridable_for_parallel_stacks() {
  local published
  published="$(MW_HTTPS_PORT=18443 docker compose -f "$MVP_FILE" config --format json | jq -r '.services.apisix.ports[0].published')"
  [ "$published" = "18443" ] || fail "MW_HTTPS_PORT not honoured: $published"
}

test_every_container_restarts_has_limits_log_rotation_and_a_healthcheck() {
  local j s
  j="$(compose_json)"
  for s in $PLATFORM_SERVICES; do
    printf '%s' "$j" | jq -e --arg s "$s" '.services[$s].logging.driver == "json-file" and (.services[$s].logging.options["max-size"] != null)' >/dev/null \
      || fail "$s: no json-file log rotation"
    printf '%s' "$j" | jq -e --arg s "$s" '.services[$s].deploy.resources.limits.memory != null' >/dev/null \
      || fail "$s: no memory limit"
    case " $ONE_SHOT " in
      *" $s "*)
        printf '%s' "$j" | jq -e --arg s "$s" '.services[$s].restart == "no" or .services[$s].restart == null' >/dev/null || fail "$s: one-shot must not restart" ;;
      *)
        printf '%s' "$j" | jq -e --arg s "$s" '.services[$s].restart == "unless-stopped"' >/dev/null || fail "$s: restart is not unless-stopped"
        printf '%s' "$j" | jq -e --arg s "$s" '.services[$s].healthcheck.test != null' >/dev/null || fail "$s: no healthcheck" ;;
    esac
  done
}

test_platform_images_are_pinned_by_digest() {
  local bad
  bad="$(compose_json | jq -r '.services | to_entries[] | select(.value.image | test("@sha256:[0-9a-f]{64}$") | not) | .key')"
  [ -z "$bad" ] || fail "not pinned by digest: $bad"
}

test_compose_files_hold_no_inline_secret_values() {
  # Dev credentials come from deploy/compose/platform/dev.env (obviously fake values);
  # the Compose files themselves only reference variables.
  if grep -nE '(PASSWORD|SECRET|_KEY|TOKEN)[A-Z_]*: *[^$ {]' "$REPO_DIR"/deploy/compose/platform/compose.yml "$MVP_FILE"; then
    fail "inline secret value in a compose file"
  fi
}

# ---- topics as code (deploy/kafka) ----

plan() { OUT="$("$REPO_DIR/deploy/kafka/apply-topics.sh" --dry-run "$REPO_DIR/deploy/kafka/topics.txt" 2>&1)"; }

assert_plan_has() { printf '%s\n' "$OUT" | grep -qx "$1" || fail "plan lacks: $1"; }

test_topic_plan_has_the_mvp_catalogue_with_spec_partitions_and_retention() {
  assert_ok plan
  # spec 6.2, values copied from the catalogue (7 d = 604800000 ms, 3 d = 259200000 ms)
  assert_plan_has "mw.txn.request.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "mw.txn.status.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "core.wallet.command.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "core.payment.command.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "core.wallet.event.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "core.payment.event.v1 partitions=48 retention.ms=604800000"
  assert_plan_has "mw.partner.request.telco.v1 partitions=24 retention.ms=259200000"
  assert_plan_has "mw.partner.request.billpay.v1 partitions=24 retention.ms=259200000"
  assert_plan_has "mw.partner.request.bank.v1 partitions=24 retention.ms=259200000"
  assert_plan_has "mw.partner.reply.v1 partitions=48 retention.ms=259200000"
  assert_plan_has "mw.partner.inbound.v1 partitions=24 retention.ms=604800000"
  assert_plan_has "mw.notify.request.v1 partitions=24 retention.ms=259200000"
  assert_plan_has "mw.notify.status.v1 partitions=12 retention.ms=604800000"
  assert_plan_has "mw.audit.event.v1 partitions=24 retention.ms=604800000"
  assert_plan_has "mw.config.changed.v1 partitions=6 cleanup.policy=compact"
}

test_topic_plan_adds_three_retry_tiers_and_a_dlq_with_source_partitions_and_14d() {
  assert_ok plan
  # 19.2: Kafka retry 10 s / 1 min / 10 min then DLQ; 6.2: same partitions, 14 d
  assert_plan_has "mw.txn.request.v1.retry.1 partitions=48 retention.ms=1209600000"
  assert_plan_has "mw.txn.request.v1.retry.2 partitions=48 retention.ms=1209600000"
  assert_plan_has "mw.txn.request.v1.retry.3 partitions=48 retention.ms=1209600000"
  assert_plan_has "mw.txn.request.v1.dlq partitions=48 retention.ms=1209600000"
  assert_plan_has "mw.notify.status.v1.dlq partitions=12 retention.ms=1209600000"
  assert_plan_has "mw.partner.request.bank.v1.retry.2 partitions=24 retention.ms=1209600000"
  [ "$(printf '%s\n' "$OUT" | grep -c '\.retry\.4 ')" = 0 ] || fail "unexpected 4th retry tier"
  printf '%s\n' "$OUT" | grep -q '^mw.config.changed.v1.retry' && fail "compacted config topic must not get retry topics"
  return 0
}

test_topic_plan_leaves_out_deferred_topics() {
  assert_ok plan
  assert_out_lacks "mw.recon.result.v1"
  assert_out_lacks "dw.txn.event.v1"
  assert_out_lacks "mw.compliance.instruction.v1"
  assert_out_lacks "mw.partner.request.interop.v1"
}

test_topic_plan_rejects_a_malformed_line() {
  printf 'bad.topic.v1 lots 7d\n' >topics.txt
  OUT="$("$REPO_DIR/deploy/kafka/apply-topics.sh" --dry-run topics.txt 2>&1)" && fail "accepted bad partitions"
  assert_out_contains "bad.topic.v1"
}

test_make_smoke_and_down_need_the_mvp_project() {
  assert_fails mw_make "$PWD" smoke
  assert_out_contains "deploy/compose/mvp/compose.yml"
  assert_fails mw_make "$PWD" down
  assert_out_contains "deploy/compose/mvp/compose.yml"
}
