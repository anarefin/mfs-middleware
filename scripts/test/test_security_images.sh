# scripts/security.sh image stage: every module image (Dockerfile -> mw-x:$MW_IMAGE_TAG) and, without
# M=, the provisioning one-shot's (deploy/provision/Dockerfile -> provision:$MW_IMAGE_TAG) is scanned;
# one that is not built fails the stage (NOT SCANNED), never "security passed". Docker, trivy and
# gitleaks are stubbed.

# images_fixture BUILT...: module services/mw-x and deploy/provision with their Dockerfiles; docker
# knows only BUILT images.
images_fixture() {
  new_module "$PWD" services/mw-x
  mkdir -p deploy/provision .bin
  touch services/mw-x/Dockerfile deploy/provision/Dockerfile
  printf '%s\n' "$@" >.built
  cat >.bin/docker <<EOF
#!/bin/sh
echo "docker \$*" >> "$PWD/.tool-calls"
case "\$*" in
  "image inspect "*) grep -qx "\$3" "$PWD/.built"; exit \$? ;;
esac
exit 0
EOF
  chmod +x .bin/docker
  for t in gitleaks trivy; do
    printf '#!/bin/sh\necho "%s $*" >> "%s/.tool-calls"\n' "$t" "$PWD" >".bin/$t"; chmod +x ".bin/$t"
  done
  PATH="$PWD/.bin:$PATH"
}

run_security() {
  OUT="$(MW_ROOT="$PWD" SECURITY_LIVE=0 bash "$REPO_DIR/scripts/security.sh" "$@" 2>&1)"
}

test_security_scans_every_built_image() {
  images_fixture mfs-mw/mw-x:local mfs-mw/provision:local
  assert_ok run_security
  grep -q 'trivy image .* mfs-mw/mw-x:local$' .tool-calls || fail "mfs-mw/mw-x:local not scanned: $(cat .tool-calls)"
  grep -q 'trivy image .* mfs-mw/provision:local$' .tool-calls || fail "mfs-mw/provision:local not scanned"
  assert_out_contains "security passed"
}

test_security_fails_when_a_module_image_is_not_built() {
  images_fixture mfs-mw/provision:local
  assert_fails run_security
  assert_out_contains "mfs-mw/mw-x:local NOT SCANNED"
  assert_out_contains "security FAILED: image-missing:mfs-mw/mw-x:local"
  assert_out_lacks "security passed"
  grep -q 'trivy image .* mfs-mw/provision:local$' .tool-calls || fail "the built provision image was not scanned"
}

test_security_fails_when_the_provision_image_is_not_built() {
  images_fixture mfs-mw/mw-x:local
  assert_fails run_security
  assert_out_contains "image-missing:mfs-mw/provision:local"
}

test_security_with_M_scans_only_that_module_image() {
  images_fixture mfs-mw/mw-x:local
  assert_ok run_security mw-x
  if grep -q 'mfs-mw/provision' .tool-calls; then fail "provision scanned with M="; fi
}

# The platform image built from the repo (ticket 55: deploy/keycloak/Dockerfile -> mfs-mw/keycloak:local)
# is scanned like the upstream images, without M= only; one not built fails the stage.
test_security_scans_the_locally_built_keycloak_image() {
  images_fixture mfs-mw/mw-x:local mfs-mw/provision:local mfs-mw/keycloak:local
  mkdir -p deploy/keycloak && touch deploy/keycloak/Dockerfile
  assert_ok run_security
  grep -q 'trivy image .* mfs-mw/keycloak:local$' .tool-calls || fail "keycloak image not scanned: $(cat .tool-calls)"
  : >.tool-calls
  assert_ok run_security mw-x
  if grep -q 'mfs-mw/keycloak' .tool-calls; then fail "keycloak scanned with M="; fi
}

test_security_fails_when_the_keycloak_image_is_not_built() {
  images_fixture mfs-mw/mw-x:local mfs-mw/provision:local
  mkdir -p deploy/keycloak && touch deploy/keycloak/Dockerfile
  assert_fails run_security
  assert_out_contains "image-missing:mfs-mw/keycloak:local"
}
