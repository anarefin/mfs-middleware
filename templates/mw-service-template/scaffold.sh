#!/usr/bin/env bash
# Copies this template to a new standalone service module (spec 4.2, 4.3, WP03).
# Usage: scaffold.sh mw-<name> DEST_DIR      (normally via `make new-service NAME=mw-<name>`)
#
# mw-adapter-telco -> package mfs.mw.adapter.telco, AdapterTelcoApplication,
# rootProject.name "mw-adapter-telco", DB schema adapter_telco. The copy keeps no reference back to the template:
# build output, this script and the template's CLAUDE.md are not copied, and the run
# fails if any template name survives the rename. bash 3.2 compatible.
set -eu

name="${1:-}"; dest="${2:-}"
die() { printf 'error: %s\n' "$*" >&2; exit 2; }
[ -n "$name" ] && [ -n "$dest" ] || die "usage: scaffold.sh mw-<name> DEST_DIR"
printf '%s' "$name" | grep -Eq '^mw-[a-z][a-z0-9]*(-[a-z0-9]+)*$' \
  || die "NAME must look like mw-<name> (lowercase letters, digits, single hyphens): '$name'"
[ ! -e "$dest" ] || die "$dest already exists"

src="$(cd "$(dirname "$0")" && pwd)"
suffix="${name#mw-}"

# Each hyphen-separated part becomes a package segment, so it must be a Java identifier.
keywords=" abstract assert boolean break byte case catch char class const continue default do
double else enum extends final finally float for goto if implements import instanceof int
interface long native new package private protected public return short static strictfp super
switch synchronized this throw throws transient try void volatile while true false null var
record yield sealed permits "
pascal=""
IFS=- read -r -a parts <<<"$suffix"
for p in "${parts[@]}"; do
  printf '%s' "$p" | grep -Eq '^[a-z][a-z0-9]*$' \
    || die "'$p' in $name is not a valid Java package segment (must start with a letter)"
  case "$keywords" in *[[:space:]]"$p"[[:space:]]*) die "'$p' in $name is a reserved Java word" ;; esac
  first="$(printf '%s' "${p:0:1}" | tr '[:lower:]' '[:upper:]')"
  pascal="$pascal$first${p:1}"
done
pkg="mfs.mw.$(printf '%s' "$suffix" | tr - .)"
pkg_path="$(printf '%s' "$pkg" | tr . /)"
upper="$(printf '%s' "$name" | tr '[:lower:]-' '[:upper:]_')"
snake="$(printf '%s' "$suffix" | tr - _)"   # DB schema and role prefix (16.2, 16.4)

tmp="$(mktemp -d "${TMPDIR:-/tmp}/scaffold.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
out="$tmp/$name"
mkdir -p "$out"
(cd "$src" && tar cf - --exclude ./build --exclude ./.gradle --exclude ./.idea \
  --exclude ./scaffold.sh --exclude ./CLAUDE.md .) | (cd "$out" && tar xf -)

# Move the package folders and rename the application class.
for set in main test; do
  old="$out/src/$set/java/mfs/mw/service/template"
  [ -d "$old" ] || continue
  mkdir -p "$out/src/$set/java/$pkg_path"
  (cd "$old" && tar cf - .) | (cd "$out/src/$set/java/$pkg_path" && tar xf -)
  rm -rf "$out/src/$set/java/mfs/mw/service"
  for f in "$out/src/$set/java/$pkg_path"/ServiceTemplateApplication*.java; do
    [ -e "$f" ] || continue
    mv "$f" "$(dirname "$f")/$(basename "$f" | sed "s/^ServiceTemplate/$pascal/")"
  done
done

# Rewrite names in every text file (most specific first).
grep -rIlE 'service-template|service_template|service\.template|service/template|ServiceTemplate|SERVICE_TEMPLATE' "$out" \
  | while IFS= read -r f; do
      sed -e "s#mfs\.mw\.service\.template#$pkg#g" \
          -e "s#mfs/mw/service/template#$pkg_path#g" \
          -e "s#MW_SERVICE_TEMPLATE#$upper#g" \
          -e "s#ServiceTemplate#$pascal#g" \
          -e "s#mw-service-template#$name#g" \
          -e "s#service_template#$snake#g" \
          "$f" >"$f.scaffold" && mv "$f.scaffold" "$f"
    done
chmod +x "$out/gradlew"

cat >"$out/CLAUDE.md" <<EOF
# $name

Standalone service module (spec 4.2, 4.3). Package \`$pkg\`. Follow the root
\`CLAUDE.md\` conventions; this file says what is specific to this service.

## Responsibilities

- TODO: what this service owns (spec section).

## Contracts owned

- TODO: files under \`contracts/\` this service produces or serves (see \`contracts/OWNERS.yml\`).

## Contracts used

- TODO: files under \`contracts/\` this service consumes. Write your own types for them.
- \`contracts.yml\` is the machine-readable list (produces/consumes, events and apis, paths relative to
  \`contracts/\`, \`#<Msg>\` for one message type), checked by \`make contracts-lint\`; keep it in sync and
  add \`contracts/ProducedContractsTest\` / \`ConsumedContractsTest\` once the service has events.

## Chassis (copied on purpose, spec 4.3 rule 5; keep aligned with 5.3, 18.3, 21)

- \`filter/CorrelationIdFilter\`: \`X-Correlation-Id\` in, generated if absent, MDC \`correlationId\`.
- \`filter/SecurityHeadersFilter\`: security headers on every response (18.6).
- \`exception/\`: \`MwException\`, \`ErrorCategory\` (namespace -> HTTP status, retryable),
  \`ErrorCodes\`, \`GlobalExceptionHandler\` building \`dto/ErrorEnvelope\`. Logs show route
  templates, never raw URIs.
- \`util/MaskingPolicy\` + \`@Sensitive\`: masking rules of 18.3; mark every personal field.
- \`logging/JsonLogFormatter\`: JSON log lines with the 21.1 fields, masking before output.
  Add context with \`log.atInfo().addKeyValue("event", "...")\` or the MDC.
- \`config/MwServiceProperties\`: config binding; secrets from OpenBao when \`VAULT_ENABLED=true\`.
- \`messaging/EventPublisher\`: Kafka records with the 6.1 headers, idempotent \`acks=all\` producer.
- \`route/RetryTopicRouteBuilder\` + \`messaging/RetryDelayListener\`: subclass per consumed topic; retry
  topics 10 s / 1 min / 10 min then DLQ (19.2); per-record commit; no sleeps on Kafka threads.
- \`application.yml\`: Resilience4j defaults (19.2), own schema \`$snake\` migrated by Flyway on
  start from \`db/migration\` (16.4); rename the schema to the spec 16.2 name if it has one.

## Commands

| Command | What it does |
| --- | --- |
| \`./gradlew test\` | unit, slice and contract tests, no Docker |
| \`./gradlew integrationTest\` | Testcontainers \`*IT\` classes (Docker) |
| \`./gradlew conformance\` | the \`@Conformance\` suite |
| \`./gradlew check\` | all three plus the JaCoCo gate (80% lines on \`service\`/\`mapper\`/\`client\`) |
| \`./gradlew check bootJar && docker build -t $name:dev .\` | image, as spec 4.3 rule 1 |
| \`make verify M=$name\` | isolation + field-injection check + contracts lint + \`./gradlew check\` |
| \`make verify-changed [BASE=main]\` | the same gate for the modules changed since BASE only |
| \`make up M=$name\` | run this service's \`compose.yml\` (platform project must be up) |
EOF

refs="$(grep -rIlE 'service-template|service_template|service\.template|service/template|ServiceTemplate|SERVICE_TEMPLATE|templates/' "$out" || true)"
[ -z "$refs" ] || die "template names left in: $refs"

mkdir -p "$(dirname "$dest")"
mv "$out" "$dest"
printf '==> scaffold: %s (package %s)\n' "$dest" "$pkg"
