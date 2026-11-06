# Service Dockerfiles (spec 20.1, root CLAUDE.md "Never put secrets in code"): the AOT cache
# training run may need placeholder values for start-up validation; they go on that RUN line
# only, never into the image (ENV, ARG, COPY), and must be obviously fake.

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

test_dockerfiles_copy_no_training_key_into_the_image() {
  local f bad
  for f in $(dockerfiles); do
    bad="$(grep -nE '^[[:space:]]*(COPY|ADD)[[:space:]].*\.(pem|key)' "$f")"
    [ -z "$bad" ] || fail "$f copies a key file into the image: $bad"
  done
}

test_training_run_placeholders_are_obviously_fake() {
  local f line
  for f in $(dockerfiles); do
    # Inline VAR=value assignments on the training RUN that look like secrets.
    line="$(grep -nE '^(RUN|[[:space:]]).*[A-Z_]*(KEY|SECRET|PASSWORD|TOKEN)[A-Z_]*=' "$f" \
      | grep -vE '[A-Z_]*(KEY|SECRET|PASSWORD|TOKEN)[A-Z_]*=("\$\(cat /tmp/aot-|[^ ]*fake)')"
    [ -z "$line" ] || fail "$f training RUN sets a secret-like value that is not obviously fake: $line"
  done
}
