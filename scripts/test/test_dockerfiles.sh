# Service Dockerfiles (spec 20.1, root CLAUDE.md "Never put secrets in code"): each copies the one
# boot jar with the fixed name bootJar writes (build/libs/<module>.jar, so a stale jar is never
# picked up), has no AOT training stage, no SBOM copy and no secret, and starts with plain
# `java -jar`. .dockerignore lets in only that jar.

dockerfiles() {
  ls "$REPO_DIR"/services/*/Dockerfile "$REPO_DIR"/templates/mw-service-template/Dockerfile
}

test_dockerfiles_set_no_env_beyond_the_jvm_flags() {
  local f bad
  for f in $(dockerfiles); do
    bad="$(grep -nE '^[[:space:]]*(ENV|ARG)[[:space:]]' "$f" | grep -vE '(ENV JAVA_TOOL_OPTIONS=|ARG JRE_IMAGE=|ARG JVM_OPTS=)')"
    [ -z "$bad" ] || fail "$f sets image env/args beyond JVM flags: $bad"
  done
}

test_dockerfiles_copy_the_fixed_name_boot_jar_only() {
  local f name copies
  for f in $(dockerfiles); do
    name="$(basename "$(dirname "$f")")"
    copies="$(grep -E '^[[:space:]]*(COPY|ADD)[[:space:]]' "$f" | grep -v -- '--from=')"
    [ "$copies" = "COPY build/libs/$name.jar app.jar" ] || fail "$f copies more or other than build/libs/$name.jar: $copies"
    grep -q '\*\.jar' "$f" && fail "$f still globs *.jar"
    [ "$(grep -vE '^[[:space:]]*(#|$)' "$(dirname "$f")/.dockerignore" | tr '\n' ' ')" = "* !build/libs/$name.jar " ] \
      || fail "$(dirname "$f")/.dockerignore does not let in exactly build/libs/$name.jar"
  done
}

test_the_template_jar_name_follows_the_module_name_after_scaffolding() {
  # scaffold.sh renames mw-service-template everywhere, so the copy's Dockerfile names mw-<x>.jar
  grep -q 'COPY build/libs/mw-service-template.jar app.jar' "$REPO_DIR/templates/mw-service-template/Dockerfile" \
    || fail "template Dockerfile does not copy its own fixed-name jar"
}

test_dockerfiles_have_no_aot_stage_or_sbom() {
  local f bad
  for f in $(dockerfiles); do
    bad="$(grep -nEi 'MW_AOT|AOTCache|AOTMode|aot-training|\.aot|bom\.json|cyclonedx' "$f" || true)"
    [ -z "$bad" ] || fail "$f still has an AOT stage or SBOM copy: $bad"
  done
}

test_dockerfiles_start_with_plain_java_jar() {
  local f
  for f in $(dockerfiles); do
    [ "$(grep -E '^ENTRYPOINT' "$f")" = 'ENTRYPOINT ["java", "-jar", "/app/app.jar"]' ] \
      || fail "$f ENTRYPOINT is not plain java -jar /app/app.jar: $(grep -E '^ENTRYPOINT' "$f")"
  done
}

test_dockerfiles_copy_no_key_into_the_image() {
  local f bad
  for f in $(dockerfiles); do
    bad="$(grep -nE '^[[:space:]]*(COPY|ADD)[[:space:]].*\.(pem|key)' "$f")"
    [ -z "$bad" ] || fail "$f copies a key file into the image: $bad"
    bad="$(grep -nE '[A-Z_]*(KEY|SECRET|PASSWORD|TOKEN)[A-Z_]*=' "$f" || true)"
    [ -z "$bad" ] || fail "$f sets a secret-like value: $bad"
  done
}

test_dockerfiles_run_as_non_root_with_a_readiness_healthcheck() {
  local f user
  for f in $(dockerfiles); do
    user="$(grep -E '^USER ' "$f" | tail -1 | awk '{print $2}')"
    case "$user" in ''|root|0|0:*) fail "$f runs as root: '$user'" ;; esac
    # mw-seeder is a one-shot (exits when done): no healthcheck
    case "$f" in */mw-seeder/Dockerfile) continue ;; esac
    grep -q '/actuator/health/readiness' "$f" || fail "$f healthcheck not on readiness"
  done
}

test_service_images_skip_the_os_package_upgrade() {
  # Laptop footprint (deploy/RUNNING.html): no apt-get upgrade per image; OS fixes come with the
  # pinned JRE_IMAGE (bump the pin), accepted findings in .trivyignore.yaml (docs/security-findings.md).
  local f
  for f in $(dockerfiles); do
    ! grep -qE 'apt-get[^#]*upgrade' "$f" || fail "$f still runs apt-get upgrade"
  done
}

test_compose_builds_pass_no_aot_build_arg() {
  ! grep -q 'MW_AOT' "$REPO_DIR/deploy/docker-compose.yml" || fail "deploy/docker-compose.yml still passes MW_AOT"
}
