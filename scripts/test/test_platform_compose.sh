# Platform Compose stack (WP02): static checks on the resolved Compose model and on the
# topics-as-code plan. They need the docker CLI (compose config works without a daemon)
# and jq. The live checks are in scripts/smoke.sh (make smoke).

MVP_FILE="$REPO_DIR/deploy/compose/mvp/compose.yml"
PLATFORM_SERVICES="kafka kafka-init postgres valkey keycloak openbao etcd etcd-init apisix apisix-init greenmail"
ONE_SHOT="kafka-init etcd-init apisix-init"
SECRET_VARS="APISIX_ADMIN_KEY ETCD_ROOT_PASSWORD ETCD_APISIX_PASSWORD ETCD_HEALTH_PASSWORD
  POSTGRES_PASSWORD VALKEY_PASSWORD KC_BOOTSTRAP_ADMIN_PASSWORD KC_PARTNER_SIM_SECRET
  KC_CUSTOMERS_TEST_PASSWORD KC_OPS_TEST_PASSWORD KC_PORTALS_TEST_PASSWORD"
REALMS_DIR="$REPO_DIR/deploy/compose/platform/keycloak/realms"

# placeholder_env: VAR=t for every generated secret (for `env`).
placeholder_env() { local v; for v in $SECRET_VARS; do printf '%s=t\n' "$v"; done; }

# copy_deploy: a copy of deploy/ in the fixture root without the generated (gitignored)
# secrets file, so the tests never depend on or read a developer's local secrets.
copy_deploy() {
  [ -d deploy ] && return 0
  cp -R "$REPO_DIR/deploy" deploy
  rm -f deploy/compose/platform/.env
}

# compose_json: the resolved MVP project as JSON (default env, so MW_HTTPS_PORT=443),
# with placeholder values for the generated secrets. Args: extra VAR=value settings.
compose_json() {
  copy_deploy
  env -u MW_HTTPS_PORT -u MW_OPS_SUBNET $(placeholder_env) "$@" \
    docker compose -f deploy/compose/mvp/compose.yml config --format json 2>/dev/null
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
  published="$(compose_json MW_HTTPS_PORT=18443 | jq -r '.services.apisix.ports[0].published')"
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
  # Credentials come from the generated .env;
  # the Compose files themselves only reference variables.
  if grep -nE '^ *[A-Z_]*(PASSWORD|SECRET|_KEY|TOKEN)[A-Z_]*: *[^$ {]' "$REPO_DIR"/deploy/compose/platform/compose.yml "$MVP_FILE"; then
    fail "inline secret value in a compose file"
  fi
}

test_env_files_and_realms_hold_no_literal_secrets() {
  local bad f
  # env/*.env: identities and settings only; credentials come from the generated .env
  bad="$(grep -nE '^[[:space:]]*[A-Za-z_]*(PASS|SECRET|AUTH|KEY|TOKEN)[A-Za-z_]*=' "$REPO_DIR"/deploy/compose/platform/env/*.env 2>/dev/null || true)"
  [ -z "$bad" ] || fail "credential in an env file: $bad"
  # realms: every client secret and user credential is a Keycloak ${ENV} placeholder
  for f in "$REALMS_DIR"/*-realm.json; do
    bad="$(jq -r '[(.clients // [])[] | .secret // empty, ((.users // [])[] | (.credentials // [])[] | .value // empty)]
      | map(select(test("^[$][{][A-Z][A-Z0-9_]*[}]$") | not)) | .[]' "$f")" || fail "cannot parse $f"
    [ -z "$bad" ] || fail "literal secret in $(basename "$f"): $bad"
  done
  if grep -rnE 'dev-only-[a-z]' "$REPO_DIR/deploy"; then fail "dev-only literal credential left in deploy/"; fi
  return 0
}

test_keycloak_gets_every_realm_placeholder_from_the_generated_env() {
  local j vars v
  j="$(compose_json)"
  vars="$(cat "$REALMS_DIR"/*-realm.json | grep -oE '[$][{][A-Z][A-Z0-9_]*[}]' | tr -d '${}' | sort -u)"
  [ -n "$vars" ] || fail "realms use no placeholders"
  for v in $vars; do
    case " $(echo $SECRET_VARS) " in *" $v "*) ;; *) fail "$v is not generated by gen-dev-env.sh" ;; esac
    printf '%s' "$j" | jq -e --arg v "$v" '.services.keycloak.environment[$v] == "t"' >/dev/null || fail "keycloak lacks $v"
  done
  printf '%s' "$j" | jq -e '.services.postgres.environment.POSTGRES_PASSWORD == "t"' >/dev/null || fail "postgres password not from .env"
  printf '%s' "$j" | jq -e '.services.valkey.environment.REDISCLI_AUTH == "t"' >/dev/null || fail "valkey password not from .env"
  printf '%s' "$j" | jq -e '.services.keycloak.environment.KC_BOOTSTRAP_ADMIN_PASSWORD == "t"' >/dev/null || fail "keycloak admin password not from .env"
}

test_compose_config_fails_fast_without_the_generated_secrets() {
  copy_deploy
  local v
  for v in $SECRET_VARS; do
    OUT="$(env $(placeholder_env) "$v=" \
      docker compose -f deploy/compose/mvp/compose.yml config -q 2>&1)" && fail "config passed without $v"
    assert_out_contains "$v"
  done
}

test_gen_dev_env_generates_random_values_once_and_keeps_them() {
  mkdir -p deploy/compose/platform
  assert_ok env MW_ROOT="$PWD" "$REPO_DIR/scripts/gen-dev-env.sh"
  local f=deploy/compose/platform/.env v first
  [ -f "$f" ] || fail "no $f"
  for v in $SECRET_VARS; do
    grep -qE "^$v=[A-Za-z0-9]{32,}$" "$f" || fail "$v missing or weak in $f"
  done
  [ "$(ls -l "$f" | cut -c1-10)" = "-rw-------" ] || fail "$f must be mode 600"
  first="$(cat "$f")"
  env MW_ROOT="$PWD" "$REPO_DIR/scripts/gen-dev-env.sh" >/dev/null
  [ "$(cat "$f")" = "$first" ] || fail "re-run changed existing secrets"
  grep -qx '/deploy/compose/platform/.env' "$REPO_DIR/.gitignore" || fail ".env is not gitignored"
}

test_etcd_is_on_a_dedicated_internal_network_shared_only_with_apisix() {
  local j members
  j="$(compose_json)"
  [ "$(printf '%s' "$j" | jq -c '.services.etcd.networks | keys')" = '["etcd"]' ] || fail "etcd must join only the etcd network"
  members="$(printf '%s' "$j" | jq -r '[.services | to_entries[] | select(.value.networks | has("etcd")) | .key] | sort | join(" ")')"
  [ "$members" = "apisix etcd etcd-init" ] || fail "etcd network members: $members"
  printf '%s' "$j" | jq -e '.networks.etcd.internal == true' >/dev/null || fail "etcd network must be internal"
}

test_admin_api_only_allows_the_fixed_ops_subnet() {
  local j cfg="$REPO_DIR/deploy/apisix/config.yaml"
  j="$(compose_json)"
  [ "$(printf '%s' "$j" | jq -r '.networks.ops.ipam.config[0].subnet')" = "172.31.250.0/24" ] || fail "ops network has no fixed default subnet"
  [ "$(compose_json MW_OPS_SUBNET=10.99.0.0/24 | jq -r '.networks.ops.ipam.config[0].subnet')" = "10.99.0.0/24" ] || fail "MW_OPS_SUBNET not honoured"
  [ "$(printf '%s' "$j" | jq -r '.services.apisix.environment.MW_OPS_SUBNET')" = "172.31.250.0/24" ] || fail "apisix does not get the ops subnet"
  awk '/allow_admin:/{f=1;next} f&&/^ *- /{print;next} f{exit}' "$cfg" >allow.txt
  grep -q 'MW_OPS_SUBNET' allow.txt || fail "allow_admin lacks the ops subnet"
  grep -q '127.0.0.0/8' allow.txt || fail "allow_admin lacks loopback"
  grep -q '0.0.0.0/0' allow.txt && fail "allow_admin must not allow everyone"
  return 0
}

test_apisix_config_reads_admin_key_and_etcd_credentials_from_env() {
  local cfg="$REPO_DIR/deploy/apisix/config.yaml"
  grep -qE '^ *key: [$][{][{]APISIX_ADMIN_KEY[}][}]' "$cfg" || fail "admin key is not read from APISIX_ADMIN_KEY"
  grep -qE '^ *password: [$][{][{]ETCD_APISIX_PASSWORD[}][}]' "$cfg" || fail "etcd password is not read from env"
  grep -qE '^ *user: ' "$cfg" || fail "no etcd user"
  if grep -rn 'dev-only-apisix' "$REPO_DIR/deploy"; then fail "fixed admin key still committed"; fi
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

test_make_up_generates_the_dev_env_before_starting_the_mvp_project() {
  mkdir -p .bin deploy/compose/mvp && touch deploy/compose/mvp/compose.yml
  printf '#!/bin/sh\necho "docker $*" >> "%s/.tool-calls"\n' "$PWD" >.bin/docker; chmod +x .bin/docker
  PATH="$PWD/.bin:$PATH" mw_make "$PWD" up || fail "make up failed"
  grep -q '^APISIX_ADMIN_KEY=' deploy/compose/platform/.env || fail "make up did not generate deploy/compose/platform/.env"
}

# ---- Keycloak ops realm MFA (spec 18.2) ----

test_ops_realm_enforces_totp_for_every_user_and_in_the_browser_flow() {
  local f="$REALMS_DIR/ops-realm.json" flow bad
  bad="$(jq -r '.users[] | select((.requiredActions // []) | index("CONFIGURE_TOTP") | not) | .username' "$f")"
  [ -z "$bad" ] || fail "ops users without the CONFIGURE_TOTP required action: $bad"
  flow="$(jq -r '.browserFlow // empty' "$f")"
  [ -n "$flow" ] && [ "$flow" != "browser" ] || fail "ops realm keeps the built-in browser flow (OTP only conditional)"
  # executions reachable from the browser flow: OTP and password forms REQUIRED, and
  # the sub-flows holding them REQUIRED or ALTERNATIVE (never CONDITIONAL/DISABLED)
  jq -e --arg top "$flow" '
    def flows: .authenticationFlows // [] | map({key: .alias, value: .authenticationExecutions}) | from_entries;
    def walk_exec($fl; $alias): $fl[$alias][] | if .authenticatorFlow then
        (select(.requirement == "REQUIRED" or .requirement == "ALTERNATIVE") | walk_exec($fl; .flowAlias)) else . end;
    flows as $fl | [walk_exec($fl; $top)] as $ex
    | ($ex | any(.authenticator == "auth-otp-form" and .requirement == "REQUIRED"))
      and ($ex | any(.authenticator == "auth-username-password-form" and .requirement == "REQUIRED"))
      and ($ex | all(.requirement != "CONDITIONAL"))' "$f" >/dev/null \
    || fail "browser flow '$flow' does not require auth-otp-form after the password form"
  jq -e '.otpPolicyType == "totp"' "$f" >/dev/null || fail "ops realm OTP policy is not TOTP"
}
