#!/bin/sh
# Applies deploy/kafka/topics.txt to the broker (run by kafka-init; idempotent).
# Usage: apply-topics.sh [--dry-run] TOPICS_FILE
#   --dry-run  print the resolved plan, one "<topic> partitions=<n> <config>" per line
# Env:
#   BOOTSTRAP            broker (default kafka:9092)
#   KAFKA_BIN            Kafka CLI folder (default /opt/kafka/bin)
#   MW_KAFKA_PARTITIONS  partition count for every topic (the dev stack sets 3); unset: the
#                        topics.txt values, which stay the production reference
#   STATE_DIR            folder for the done marker (a volume in compose); unset: no marker
#   PARALLEL             Kafka CLI JVMs at once for the creates and alters (default 4)
#
# Every Kafka CLI call is a JVM start (1-2 s), so a pass is one --describe of the whole cluster,
# then a create for each missing topic and an alter for each topic whose config differs. After a
# successful pass the plan's checksum goes to $STATE_DIR/topics.done; while it matches, the next
# run exits at once without starting a JVM.
set -eu

dry=0
if [ "${1:-}" = "--dry-run" ]; then dry=1; shift; fi
file="${1:?usage: apply-topics.sh [--dry-run] TOPICS_FILE}"
[ -r "$file" ] || { echo "error: cannot read $file" >&2; exit 1; }

override="${MW_KAFKA_PARTITIONS:-}"
case "$override" in
  '') ;;
  *[!0-9]* | 0*) echo "error: MW_KAFKA_PARTITIONS '$override' is not a positive number" >&2; exit 1 ;;
esac

DAY_MS=86400000
RETRY_TIERS="1 2 3"
RETRY_RETENTION_MS=$((14 * DAY_MS))

plan() {
  while read -r name parts retention flag rest; do
    case "$name" in ''|'#'*) continue ;; esac
    case "$parts" in ''|*[!0-9]*) echo "error: $name: partitions '$parts' is not a number" >&2; return 1 ;; esac
    [ -z "$override" ] || parts="$override"
    case "$retention" in
      compact) cfg="cleanup.policy=compact" ;;
      *d) days="${retention%d}"
          case "$days" in ''|*[!0-9]*) echo "error: $name: bad retention '$retention'" >&2; return 1 ;; esac
          cfg="retention.ms=$((days * DAY_MS))" ;;
      *) echo "error: $name: bad retention '$retention'" >&2; return 1 ;;
    esac
    groups=""
    case "${flag:-}" in
      '') ;;
      retry=*) groups="$(printf '%s' "${flag#retry=}" | tr ',' ' ')"
               case "${flag#retry=}" in ''|,*|*,|*,,*|*[!a-z0-9,-]*) echo "error: $name: bad consumer group list '$flag'" >&2; return 1 ;; esac ;;
      *) echo "error: $name: unknown flag '$flag' (retry needs its consumer groups: retry=<group>[,<group>])" >&2; return 1 ;;
    esac
    [ -z "${rest:-}" ] || { echo "error: $name: trailing fields '$rest'" >&2; return 1; }
    echo "$name partitions=$parts $cfg"
    for g in $groups; do
      for n in $RETRY_TIERS; do echo "$name.$g.retry.$n partitions=$parts retention.ms=$RETRY_RETENTION_MS"; done
      echo "$name.$g.dlq partitions=$parts retention.ms=$RETRY_RETENTION_MS"
    done
  done <"$file"
}

resolved="$(plan)"
if [ "$dry" = 1 ]; then printf '%s\n' "$resolved"; exit 0; fi

count="$(printf '%s\n' "$resolved" | wc -l | tr -d ' ')"
sum="$(printf '%s\n' "$resolved" | cksum | cut -d' ' -f1-2)"
marker=""
[ -z "${STATE_DIR:-}" ] || marker="$STATE_DIR/topics.done"
if [ -n "$marker" ] && [ "$(cat "$marker" 2>/dev/null || true)" = "$sum" ]; then
  echo "kafka-init: $count topics up to date (plan checksum matches $marker)"
  exit 0
fi

bin="${KAFKA_BIN:-/opt/kafka/bin}"
bs="${BOOTSTRAP:-kafka:9092}"
# One line per topic, "Topic: <name><TAB>...<TAB>Configs: k=v,k=v" (partition lines start with a
# tab). Configs lists the topic-level overrides, which is what this script sets. -> "<name> ,k=v,"
described="$("$bin/kafka-topics.sh" --bootstrap-server "$bs" --describe)" || {
  echo "kafka-init: FAILED: describe" >&2; exit 1; }
existing="$(printf '%s\n' "$described" | awk -F'\t' '/^Topic: / {
  name = substr($1, 8); cfg = ""
  for (i = 2; i <= NF; i++) if ($i ~ /^Configs: /) cfg = substr($i, 10)
  print name " ," cfg "," }')"

# One command per line: a create for a missing topic, an alter when a planned config is not set.
# Partition counts are never changed on an existing topic (raising them is a manual, reviewed step).
changes_for() {
  while read -r topic parts cfg; do
    want="$cfg,min.insync.replicas=1"
    have="$(printf '%s\n' "$existing" | awk -v t="$topic" '$1 == t { print $2; exit }')"
    if [ -z "$have" ]; then
      echo "$bin/kafka-topics.sh --bootstrap-server $bs --create --if-not-exists --topic $topic --${parts%%=*} ${parts#*=} --replication-factor 1 --config $cfg --config min.insync.replicas=1"
      continue
    fi
    for kv in $(printf '%s' "$want" | tr ',' ' '); do
      case "$have" in
        *",$kv,"*) ;;
        *) echo "$bin/kafka-configs.sh --bootstrap-server $bs --alter --entity-type topics --entity-name $topic --add-config $want"
           break ;;
      esac
    done
  done
}
cmds="$(printf '%s\n' "$resolved" | changes_for)"

changes=0
if [ -n "$cmds" ]; then
  changes="$(printf '%s\n' "$cmds" | wc -l | tr -d ' ')"
  # NUL-separated, one command per sh: BSD xargs -I cannot take lines this long
  printf '%s\n' "$cmds" | tr '\n' '\0' |
    xargs -0 -n 1 -P "${PARALLEL:-4}" sh -c '$1 >/dev/null || { echo "kafka-init: FAILED: $1" >&2; exit 255; }' _ ||
    { echo "kafka-init: FAILED, no marker written" >&2; exit 1; }
fi
[ -z "$marker" ] || printf '%s\n' "$sum" >"$marker"
echo "kafka-init: $count topics applied ($changes created or altered)"
