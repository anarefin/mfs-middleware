#!/bin/sh
# Applies deploy/kafka/topics.txt to the broker (run by kafka-init; idempotent).
# Usage: apply-topics.sh [--dry-run] TOPICS_FILE
#   --dry-run  print the resolved plan, one "<topic> partitions=<n> <config>" per line
# Env: BOOTSTRAP (default kafka:9092), KAFKA_BIN (default /opt/kafka/bin)
set -eu

dry=0
if [ "${1:-}" = "--dry-run" ]; then dry=1; shift; fi
file="${1:?usage: apply-topics.sh [--dry-run] TOPICS_FILE}"
[ -r "$file" ] || { echo "error: cannot read $file" >&2; exit 1; }

DAY_MS=86400000
RETRY_TIERS="1 2 3"
RETRY_RETENTION_MS=$((14 * DAY_MS))

plan() {
  while read -r name parts retention flag rest; do
    case "$name" in ''|'#'*) continue ;; esac
    case "$parts" in ''|*[!0-9]*) echo "error: $name: partitions '$parts' is not a number" >&2; return 1 ;; esac
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

bin="${KAFKA_BIN:-/opt/kafka/bin}"
bs="${BOOTSTRAP:-kafka:9092}"
existing="$("$bin/kafka-topics.sh" --bootstrap-server "$bs" --list)"

# One JVM per topic is slow, so run PARALLEL (default 8) at a time.
# New topics are created with their config; existing ones get their config re-applied
# (partition counts are never lowered; raising them is a manual, reviewed step).
printf '%s\n' "$resolved" | while read -r topic parts cfg; do
  if printf '%s\n' "$existing" | grep -qx "$topic"; then
    echo "$bin/kafka-configs.sh --bootstrap-server $bs --alter --entity-type topics --entity-name $topic --add-config $cfg,min.insync.replicas=1"
  else
    echo "$bin/kafka-topics.sh --bootstrap-server $bs --create --if-not-exists --topic $topic --${parts%%=*} ${parts#*=} --replication-factor 1 --config $cfg --config min.insync.replicas=1"
  fi
done | xargs -P "${PARALLEL:-8}" -I{} sh -c '{} >/dev/null || { echo "kafka-init: FAILED: {}" >&2; exit 1; }'
echo "kafka-init: $(printf '%s\n' "$resolved" | wc -l | tr -d ' ') topics applied"
