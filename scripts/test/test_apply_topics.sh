# deploy/kafka/apply-topics.sh (ticket 55): the kafka-init one-shot. Dry-run plan with the
# partition count from MW_KAFKA_PARTITIONS, and the live pass against a stub Kafka CLI: one
# describe of the whole cluster, then only the missing topics are created and only the configs
# that differ are altered; a done marker with a hash of the plan makes a second run a no-op.

APPLY="$REPO_DIR/deploy/kafka/apply-topics.sh"

topics_fixture() {
  cat >topics.txt <<'EOF'
# comment
a.v1   48 7d
b.v1   24 3d
c.v1    6 compact retry=mw-x
EOF
}

# stub_kafka: KAFKA_BIN with kafka-topics.sh / kafka-configs.sh that log each call (one line per
# JVM start) and answer --describe from describe.txt. A file .fail-create makes --create fail.
stub_kafka() {
  mkdir -p bin
  cat >bin/kafka-topics.sh <<EOF
#!/bin/sh
echo "kafka-topics \$*" >>"$PWD/calls"
case "\$*" in
  *--describe*) cat "$PWD/describe.txt" ;;
  *--create*) [ -f "$PWD/.fail-create" ] && exit 1 ;;
esac
exit 0
EOF
  cat >bin/kafka-configs.sh <<EOF
#!/bin/sh
echo "kafka-configs \$*" >>"$PWD/calls"
exit 0
EOF
  chmod +x bin/*.sh
  : >calls
  : >describe.txt
  mkdir -p state
}

apply_live() { OUT="$(KAFKA_BIN="$PWD/bin" BOOTSTRAP=k:9092 STATE_DIR="$PWD/state" PARALLEL=2 "$APPLY" "$@" topics.txt 2>&1)"; }

calls_matching() { grep -c -- "$1" calls || true; }

test_dry_run_takes_the_partition_count_from_MW_KAFKA_PARTITIONS() {
  topics_fixture
  OUT="$(MW_KAFKA_PARTITIONS=3 "$APPLY" --dry-run topics.txt 2>&1)" || fail "dry-run failed"
  printf '%s\n' "$OUT" | grep -qx "a.v1 partitions=3 retention.ms=604800000" || fail "a.v1 not at 3 partitions"
  printf '%s\n' "$OUT" | grep -qx "c.v1.mw-x.retry.2 partitions=3 retention.ms=1209600000" || fail "retry tier not at 3 partitions"
  [ "$(printf '%s\n' "$OUT" | grep -vc ' partitions=3 ')" = 0 ] || fail "a topic kept its topics.txt partition count"
}

test_dry_run_without_MW_KAFKA_PARTITIONS_keeps_the_topics_file_values() {
  topics_fixture
  OUT="$(env -u MW_KAFKA_PARTITIONS "$APPLY" --dry-run topics.txt 2>&1)" || fail "dry-run failed"
  printf '%s\n' "$OUT" | grep -qx "a.v1 partitions=48 retention.ms=604800000" || fail "a.v1 not at 48"
  printf '%s\n' "$OUT" | grep -qx "b.v1 partitions=24 retention.ms=259200000" || fail "b.v1 not at 24"
}

test_rejects_a_partition_count_that_is_not_a_positive_number() {
  topics_fixture
  OUT="$(MW_KAFKA_PARTITIONS=three "$APPLY" --dry-run topics.txt 2>&1)" && fail "accepted MW_KAFKA_PARTITIONS=three"
  assert_out_contains "MW_KAFKA_PARTITIONS"
  OUT="$(MW_KAFKA_PARTITIONS=0 "$APPLY" --dry-run topics.txt 2>&1)" && fail "accepted MW_KAFKA_PARTITIONS=0"
  return 0
}

test_live_pass_describes_once_creates_only_missing_topics_and_alters_only_differing_configs() {
  topics_fixture; stub_kafka
  # a.v1 exists as planned; b.v1 exists with another retention; c.v1 and its tiers are missing.
  printf 'Topic: a.v1\tTopicId: x1\tPartitionCount: 3\tReplicationFactor: 1\tConfigs: min.insync.replicas=1,retention.ms=604800000\n' >describe.txt
  printf '\tTopic: a.v1\tPartition: 0\tLeader: 1\tReplicas: 1\tIsr: 1\n' >>describe.txt
  printf 'Topic: b.v1\tTopicId: x2\tPartitionCount: 3\tReplicationFactor: 1\tConfigs: min.insync.replicas=1,retention.ms=86400000\n' >>describe.txt
  MW_KAFKA_PARTITIONS=3 apply_live || fail "apply failed"
  [ "$(calls_matching '--describe')" = 1 ] || fail "expected one describe: $(cat calls)"
  [ "$(calls_matching '--list')" = 0 ] || fail "listed topics separately: $(cat calls)"
  [ "$(calls_matching '--create')" = 5 ] || fail "expected c.v1 + 4 tiers created: $(cat calls)"
  grep -q -- '--create --if-not-exists --topic c.v1 --partitions 3 .*cleanup.policy=compact' calls || fail "c.v1 not created compacted at 3: $(cat calls)"
  grep -q -- '--topic a.v1 ' calls && fail "touched a.v1, which is already as planned: $(cat calls)"
  [ "$(calls_matching 'kafka-configs')" = 1 ] || fail "expected one alter (b.v1): $(cat calls)"
  grep -q -- '--entity-name b.v1 --add-config retention.ms=259200000,min.insync.replicas=1' calls || fail "b.v1 retention not altered: $(cat calls)"
}

test_second_run_with_an_unchanged_plan_exits_at_once_without_kafka() {
  topics_fixture; stub_kafka
  MW_KAFKA_PARTITIONS=3 apply_live || fail "first apply failed"
  ls state | grep -q . || fail "no done marker in STATE_DIR"
  : >calls
  MW_KAFKA_PARTITIONS=3 apply_live || fail "second apply failed"
  [ ! -s calls ] || fail "second run started Kafka CLIs: $(cat calls)"
  assert_out_contains "up to date"
}

test_a_changed_plan_runs_the_pass_again() {
  topics_fixture; stub_kafka
  MW_KAFKA_PARTITIONS=3 apply_live || fail "first apply failed"
  : >calls
  printf 'd.v1 6 7d\n' >>topics.txt
  MW_KAFKA_PARTITIONS=3 apply_live || fail "apply after change failed"
  grep -q -- '--describe' calls || fail "changed topics.txt did not run the pass"
  : >calls
  MW_KAFKA_PARTITIONS=4 apply_live || fail "apply with other partitions failed"
  grep -q -- '--describe' calls || fail "changed MW_KAFKA_PARTITIONS did not run the pass"
}

test_a_failed_pass_fails_and_writes_no_marker() {
  topics_fixture; stub_kafka
  touch .fail-create
  MW_KAFKA_PARTITIONS=3 apply_live && fail "apply succeeded although a create failed"
  assert_out_contains "FAILED"
  [ -z "$(ls state)" ] || fail "marker written after a failed pass"
}

test_kafka_init_keeps_its_marker_in_a_volume_and_gets_dev_partitions() {
  local f="$REPO_DIR/deploy/docker-compose.yml"
  OUT="$(env $(for v in $(grep -o '\${[A-Z0-9_]*:?' "$f" | tr -d '${:?' | sort -u); do printf '%s=t ' "$v"; done) \
    docker compose -f "$f" config --format json 2>&1)" || fail "compose config failed"
  printf '%s' "$OUT" | jq -e '.services["kafka-init"].environment.MW_KAFKA_PARTITIONS == "3"' >/dev/null \
    || fail "kafka-init does not default MW_KAFKA_PARTITIONS to 3"
  printf '%s' "$OUT" | jq -e --arg d "$(printf '%s' "$OUT" | jq -r '.services["kafka-init"].environment.STATE_DIR')" \
    '[.services["kafka-init"].volumes[] | select(.type == "volume" and .target == $d)] | length == 1' >/dev/null \
    || fail "kafka-init STATE_DIR is not a named volume"
}
