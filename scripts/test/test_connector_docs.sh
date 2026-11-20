# Connector developer docs (ticket 136, FRS CON-06, MCK-03, 10.4 criteria 4 and 5): the mock trigger docs
# carry the rendering of mw-simulators' trigger registry (MockTriggerDocsTest keeps that file in step with
# the code), the API catalogue lists every contract under contracts/api/ and every published WSDL, and the
# developer guide names only Kamelets that ship. Paths, scripts and ./gradlew tasks of these docs are
# checked by test_repo_docs.sh (op_docs).

MOCKS_README=docs/mocks/README.md
TRIGGERS=services/mw-simulators/src/main/resources/mock-triggers.md
CATALOGUE=docs/api-catalogue.md
GUIDE=docs/connectors/developer-guide.md

test_mock_readme_carries_the_trigger_registry() {
  local r="$REPO_DIR/$MOCKS_README" block
  [ -f "$r" ] || fail "no $MOCKS_README"
  [ -f "$REPO_DIR/$TRIGGERS" ] || fail "no $TRIGGERS"
  block="$(sed -n '/^<!-- mock-triggers:begin -->$/,/^<!-- mock-triggers:end -->$/p' "$r" | sed '1d;$d')"
  [ -n "$block" ] || fail "$MOCKS_README has no <!-- mock-triggers:begin --> ... <!-- mock-triggers:end --> block"
  [ "$block" = "$(cat "$REPO_DIR/$TRIGGERS")" ] \
    || fail "$MOCKS_README is stale: paste $TRIGGERS between its mock-triggers markers"
}

test_mock_readme_has_a_section_per_frs_party() {
  local r="$REPO_DIR/$MOCKS_README" n
  for n in 01 02 03 04 05 06 07 08 09 10 11 12 13 14 15 16 17 18; do
    grep -qE "^## .*EXT-$n[,)]" "$r" || fail "$MOCKS_README has no section for EXT-$n"
  done
}

test_api_catalogue_lists_every_api_contract() {
  local c="$REPO_DIR/$CATALOGUE" f
  [ -f "$c" ] || fail "no $CATALOGUE"
  while IFS= read -r f; do
    grep -qF "\`${f#"$REPO_DIR"/}\`" "$c" || fail "$CATALOGUE does not list ${f#"$REPO_DIR"/}"
  done < <(find "$REPO_DIR/contracts/api" -type f \( -name '*.yaml' -o -name '*.xsd' \) | sort)
}

test_api_catalogue_lists_every_published_wsdl() {
  local c="$REPO_DIR/$CATALOGUE" n w
  # a SOAP facade publishes its WSDL through a DefaultWsdl11Definition bean, one per facade
  n="$(grep -rlF 'DefaultWsdl11Definition' "$REPO_DIR"/services/*/src/main/java | wc -l | tr -d ' ')"
  w="$(grep -oE '`/[A-Za-z0-9/_.-]+\.wsdl`' "$c" | sort -u | wc -l | tr -d ' ')"
  [ "$w" -ge "$n" ] || fail "$CATALOGUE lists $w WSDLs, the services publish $n"
  for w in $(grep -oE '`/[A-Za-z0-9/_.-]+\.wsdl`' "$c" | tr -d '`' | sort -u); do
    grep -rqF "$(basename "$w")" "$REPO_DIR"/services/*/src/main/java || fail "$CATALOGUE names $w, which no service publishes"
  done
}

test_developer_guide_names_only_shipped_kamelets() {
  local g="$REPO_DIR/$GUIDE" k
  [ -f "$g" ] || fail "no $GUIDE"
  for k in $(grep -oE '`mw-(map|http-call|soap-call|classify|audit|recon|auth-[a-z0-9-]+|sftp-[a-z]+)`' "$g" | tr -d '`' | sort -u); do
    [ -f "$REPO_DIR/services/mw-adapter-billpay/src/main/resources/kamelets/$k.kamelet.yaml" ] \
      || fail "$GUIDE names Kamelet $k, which mw-adapter-billpay does not ship"
  done
  for k in mw-map mw-http-call mw-soap-call mw-classify mw-audit mw-sftp-source mw-sftp-sink; do
    grep -qF "\`$k\`" "$g" || fail "$GUIDE does not describe Kamelet $k"
  done
}

test_sample_connector_ships_with_its_simulator() {
  [ -f "$REPO_DIR/deploy/partner-routes/examples/sample-rest-biller.yaml" ] || fail "no sample connector"
  [ -f "$REPO_DIR/services/mw-simulators/src/main/resources/seed/v1/bills/samplebiller.csv" ] \
    || fail "no samplebiller seed bills in mw-simulators"
  grep -qF '/samplebiller/v1/' "$REPO_DIR/deploy/partner-routes/examples/sample-rest-biller.yaml" \
    || fail "the sample connector does not call the samplebiller simulator"
}

test_billpay_tests_the_sample_connector_as_shipped() {
  cmp -s "$REPO_DIR/deploy/partner-routes/examples/sample-rest-biller.yaml" \
    "$REPO_DIR/services/mw-adapter-billpay/src/test/resources/partner-routes/sample-rest-biller.yaml" \
    || fail "mw-adapter-billpay's copy of sample-rest-biller.yaml differs: cp deploy/partner-routes/examples/sample-rest-biller.yaml services/mw-adapter-billpay/src/test/resources/partner-routes/"
}
