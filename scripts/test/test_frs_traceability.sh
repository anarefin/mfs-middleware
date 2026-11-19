# FRS traceability (ticket 89): every requirement ID of spec/FRS-Upay-Middleware.md has exactly
# one row in docs/frs-traceability.md, with a status: done, ticket(s) NN, or deviation.

FRS="$REPO_DIR/spec/FRS-Upay-Middleware.md"
TRACE="$REPO_DIR/docs/frs-traceability.md"

# The requirement ID patterns of the FRS tables (error codes such as EXT-503 or CMP-001 and the
# use-case and module ids are not requirements: their own tables in the doc are name maps).
FRS_ID_RE='(PLT-[0-9]{3}|CH-(APP|USSD|WEB|POR)-[0-9]{2}|FL-[0-9]{2}|CON-[0-9]{2}|EXT-[0-9]{2}|MCK-[0-9]{2}|MCT-[0-9]{2}|WAL-[0-9]{2}|NOT-[0-9]{2}|REC-[0-9]{2}|CMP-[0-9]{2}|AUD-[0-9]{2}|DAT-[0-9]{2}|ADM-[0-9]{2}|T[0-9]{1,2})'

# row_ids FILE: the requirement IDs that head a table row of FILE, one per line, repeats kept.
row_ids() {
  grep -oE "^\| $FRS_ID_RE \|" "$1" | sed -E 's/^\| //; s/ \|$//'
}

test_frs_has_the_requirement_tables() {
  [ -f "$FRS" ] || fail "no spec/FRS-Upay-Middleware.md"
  local n
  n="$(row_ids "$FRS" | sort -u | wc -l | tr -d ' ')"
  [ "$n" -ge 190 ] || fail "found only $n FRS requirement ids: did the FRS table format change?"
}

test_traceability_doc_exists_and_is_linked() {
  [ -f "$TRACE" ] || fail "no docs/frs-traceability.md"
  local f
  for f in README.md CLAUDE.md docs/adr/README.md; do
    grep -qF 'frs-traceability.md' "$REPO_DIR/$f" || fail "$f does not link docs/frs-traceability.md"
  done
}

test_every_frs_id_appears_exactly_once_with_a_status() {
  [ -f "$TRACE" ] || fail "no docs/frs-traceability.md"
  local id n row status bad=""
  for id in $(row_ids "$FRS" | sort -u); do
    n="$(row_ids "$TRACE" | grep -cxF -- "$id")"
    if [ "$n" -ne 1 ]; then bad="$bad $id($n)"; continue; fi
    row="$(grep -E "^\| $id \|" "$TRACE")"
    status="$(printf '%s\n' "$row" | awk -F'|' '{gsub(/^ +| +$/, "", $3); print $3}')"
    printf '%s\n' "$status" | grep -qE '^(done|deviation|tickets? [0-9]+(, [0-9]+)*)$' \
      || bad="$bad $id(status '$status')"
  done
  [ -z "$bad" ] || fail "docs/frs-traceability.md: ids not listed exactly once with a status (done / ticket NN / deviation):$bad"
}

test_traceability_names_no_id_the_frs_lacks() {
  [ -f "$TRACE" ] || fail "no docs/frs-traceability.md"
  local id extra=""
  for id in $(row_ids "$TRACE" | sort -u); do
    row_ids "$FRS" | grep -qxF -- "$id" || extra="$extra $id"
  done
  [ -z "$extra" ] || fail "docs/frs-traceability.md lists ids the FRS has no row for:$extra"
}

test_traceability_tickets_exist() {
  [ -f "$TRACE" ] || fail "no docs/frs-traceability.md"
  local t missing=""
  for t in $(grep -E "^\| $FRS_ID_RE \|" "$TRACE" | awk -F'|' '{print $3}' | grep -oE '[0-9]+' | sort -u); do
    ls "$REPO_DIR/.scratch/bits-mfs-middleware/issues/$t-"*.md >/dev/null 2>&1 || missing="$missing $t"
  done
  [ -z "$missing" ] || fail "docs/frs-traceability.md names tickets with no issue file:$missing"
}
