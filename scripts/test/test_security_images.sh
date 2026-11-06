# scripts/security.sh image stage: every image a module builds (Dockerfile -> mw-x:$MW_IMAGE_TAG,
# init/Dockerfile -> mw-x-init:$MW_IMAGE_TAG) is scanned; one that is not built fails the stage
# (NOT SCANNED), never "security passed". Docker, trivy and gitleaks are stubbed.

# images_fixture BUILT...: module services/mw-x with both Dockerfiles; docker knows only BUILT images.
images_fixture() {
  new_module "$PWD" services/mw-x
  mkdir -p services/mw-x/init .bin
  touch services/mw-x/Dockerfile services/mw-x/init/Dockerfile
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
  images_fixture mw-x:dev mw-x-init:dev
  assert_ok run_security
  grep -q 'trivy image .* mw-x:dev$' .tool-calls || fail "mw-x:dev not scanned: $(cat .tool-calls)"
  grep -q 'trivy image .* mw-x-init:dev$' .tool-calls || fail "mw-x-init:dev not scanned"
  assert_out_contains "security passed"
}

test_security_fails_when_a_module_image_is_not_built() {
  images_fixture mw-x-init:dev
  assert_fails run_security
  assert_out_contains "mw-x:dev NOT SCANNED"
  assert_out_contains "security FAILED: image-missing:mw-x:dev"
  assert_out_lacks "security passed"
  grep -q 'trivy image .* mw-x-init:dev$' .tool-calls || fail "the built init image was not scanned"
}

test_security_fails_when_an_init_image_is_not_built() {
  images_fixture mw-x:dev
  assert_fails run_security mw-x
  assert_out_contains "image-missing:mw-x-init:dev"
}
