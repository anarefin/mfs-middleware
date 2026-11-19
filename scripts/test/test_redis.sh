# Redis replaces Valkey (FRS 1.4, PLT-004, ADR D29, ticket 90): the name is gone from everything but
# the decision records, and the compose service and the Testcontainers images run the one pinned
# Redis release.

REDIS_TAG='redis:8.10.3-alpine'
REDIS_IMAGE_RE='redis:8\.10\.3-alpine@sha256:[0-9a-f]{64}'

# Decision records keep the history (docs/adr, the spec's decision tables and revision notes, the
# ticket notes, the FRS traceability); applied Flyway migrations never change (their checksum).
# Nothing else names Valkey.
test_no_valkey_left_outside_the_decision_records() {
  local hits
  hits="$(cd "$REPO_DIR" && git ls-files -co --exclude-standard \
    | grep -vE '^(docs/adr/|spec/|\.scratch/|\.claude/)|/db/migration/|^docs/frs-traceability\.md$|^scripts/test/test_redis\.sh$' \
    | while IFS= read -r f; do [ -f "$f" ] && printf '%s\n' "$f"; done \
    | tr '\n' '\0' | xargs -0 grep -Iin 'valkey' 2>/dev/null | head -20)"
  [ -z "$hits" ] || fail "Valkey still named:
$hits"
}

test_compose_runs_the_redis_image_pinned_by_digest() {
  local compose="$REPO_DIR/deploy/docker-compose.yml"
  grep -qE '^  redis:$' "$compose" || fail "compose has no service redis"
  grep -qE "image: $REDIS_IMAGE_RE$" "$compose" || fail "compose does not pin $REDIS_TAG by digest"
  grep -q 'redis-cli ping' "$compose" || fail "the redis healthcheck is not redis-cli ping"
}

# Every Testcontainers Redis of the modules runs the same release as the stack.
test_testcontainers_use_the_stack_redis_release() {
  local hits
  hits="$(cd "$REPO_DIR" && grep -rhoE '"redis:[^"]*"' services/*/src/test 2>/dev/null | sort -u)"
  [ -n "$hits" ] || fail "no Testcontainers Redis image found"
  [ "$hits" = "\"$REDIS_TAG\"" ] || fail "Testcontainers Redis images differ from $REDIS_TAG: $hits"
}
