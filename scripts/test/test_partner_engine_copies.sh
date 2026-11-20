# The adapter framework (root CLAUDE.md "partner/"): the three copies in mw-adapter-telco,
# mw-adapter-billpay, mw-adapter-bank and mw-adapter-merchant (ticket 115) stay identical apart from the package (ticket 97).

# engine_tree ADAPTER: every file of the adapter's engine (main partner/ tree, Kamelets, partner
# resources) with the package name replaced by a placeholder, one "== path" header per file.
engine_tree() {
  local a="$1" dir="$REPO_DIR/services/mw-adapter-$1/src/main" f
  for f in $( (cd "$dir/java/mfs/mw/adapter/$a/partner" && find . -type f | sort | sed 's#^#java/#');
              (cd "$dir/resources/kamelets" && find . -type f | sort | sed 's#^#kamelets/#');
              (cd "$dir/resources/partner" && find . -type f | sort | sed 's#^#partner/#') ); do
    printf '== %s\n' "$f"
    case "$f" in
      java/*) sed "s/mfs\.mw\.adapter\.$a/mfs.mw.adapter.X/g" "$dir/java/mfs/mw/adapter/$a/partner/${f#java/}" ;;
      kamelets/*) cat "$dir/resources/kamelets/${f#kamelets/}" ;;
      partner/*) cat "$dir/resources/partner/${f#partner/}" ;;
    esac
  done
}

test_partner_engine_copies_are_identical_apart_from_the_package() {
  local ref other
  ref="$(engine_tree billpay)"
  [ -n "$ref" ] || fail "no billpay engine found"
  for a in telco bank merchant; do
    other="$(engine_tree "$a")"
    if [ "$ref" != "$other" ]; then
      OUT="$(diff <(printf '%s\n' "$ref") <(printf '%s\n' "$other") | head -40)"
      fail "mw-adapter-$a partner engine differs from mw-adapter-billpay's"
    fi
  done
}
