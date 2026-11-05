#!/usr/bin/env bash
# Security pass (spec 18.5, WP29, ticket 46). Usage: scripts/security.sh [MODULE]
#
# Offline stages (always):
#   - secrets:  gitleaks over the tracked and untracked, not ignored files (gitignored build
#               output, node_modules and the generated deploy/compose/platform/.env are left
#               out), with the repo's .gitleaks.toml (default rules + fixture allow-list)
#   - deps:     per Gradle module ./gradlew cyclonedxBom, then trivy sbom on the CycloneDX BOM;
#               per module also trivy fs (lockfiles: the admin UI's pnpm-lock.yaml)
#   - images:   trivy image on every locally built mw-x:$MW_IMAGE_TAG and mw-x-init:$MW_IMAGE_TAG
#               (default tag dev, what compose builds); without M= also every third-party image
#               the root compose.yml pins
#   HIGH and CRITICAL fail, minus the justified entries of .trivyignore.yaml; image scans skip
#   OS packages without a fix (--ignore-unfixed, decision in docs/security-findings.md).
#   Findings below high are written to build/security/*-below-high.json (report only); their
#   decisions are in docs/security-findings.md.
# Live stages (need the running MVP stack, `make up` then `make e2e`):
#   - zap:      ZAP baseline (passive) on the APISIX edge from inside the dmz network, gated by
#               scripts/lib/zap-gate.py against deploy/zap/decisions.tsv (high fails, and so
#               does any alert without a decision)
#   - logs:     scripts/log-scan.sh over every container log of the project (no PIN, PIN
#               ciphertext, unmasked MSISDN or NID)
#   SECURITY_LIVE=auto (default) runs them when APISIX is running and skips them loudly
#   otherwise; SECURITY_LIVE=1 makes them mandatory (the milestone gate); 0 skips them.
#
# Scanners: gitleaks / trivy on PATH, else their pinned images through docker. Fails when
# neither exists.
set -eu
. "$(dirname "$0")/lib/modules.sh"

GITLEAKS_IMAGE="${MW_GITLEAKS_IMAGE:-ghcr.io/gitleaks/gitleaks:v8.28.0}"
TRIVY_IMAGE="${MW_TRIVY_IMAGE:-aquasec/trivy:0.67.2}"
ZAP_IMAGE="${MW_ZAP_IMAGE:-ghcr.io/zaproxy/zaproxy:2.16.1}"
IMAGE_TAG="${MW_IMAGE_TAG:-dev}"
LIVE="${SECURITY_LIVE:-auto}"
OUT="$MW_ROOT/build/security"

m="${1:-}"
# UI modules (ui/*, a package.json, no Gradle build) are scanned too; M= may name one.
ui_modules() {
  local d
  for d in "$MW_ROOT"/ui/*/; do
    [ -f "$d/package.json" ] && printf '%s\n' "$(basename "$(dirname "$d")")/$(basename "$d")"
  done
  return 0
}
modules=""
ui_m="$(ui_modules | while read -r u; do [ "$u" = "${m%/}" ] || [ "$(basename "$u")" = "${m%/}" ] && echo "$u"; done || true)"
if [ -n "$m" ] && [ -n "$ui_m" ]; then
  modules="$ui_m"
else
  modules="$(selected_modules "$m")"
  [ -n "$m" ] || modules="$(printf '%s\n%s\n' "$modules" "$(ui_modules)" | sed '/^$/d')"
fi

have() { command -v "$1" >/dev/null 2>&1; }
missing=""
for t in gitleaks trivy; do have "$t" || have docker || missing="$missing $t"; done
[ -z "$missing" ] || die "security: missing scanner(s):$missing (install them, e.g. brew install gitleaks trivy, or install docker to use their images)"
mkdir -p "$OUT"

# Scanners run as the local binary, else as a container that sees the repo at the same path.
gitleaks_run() {
  if have gitleaks; then gitleaks "$@"; return; fi
  docker run --rm -v "$MW_ROOT:$MW_ROOT:ro" -v "$OUT:$OUT" "$GITLEAKS_IMAGE" "$@"
}
trivy_run() {
  if have trivy; then trivy "$@"; return; fi
  docker run --rm -v mw-trivy-cache:/root/.cache -v /var/run/docker.sock:/var/run/docker.sock \
    -v "$MW_ROOT:$MW_ROOT:ro" -v "$OUT:$OUT" "$TRIVY_IMAGE" "$@"
}
# Accepted findings, each with its statement and expiry: .trivyignore.yaml for what this repo
# builds, deploy/trivyignore-third-party.yaml for the pinned upstream images (kept apart so an
# upstream acceptance never hides the same CVE in an image we build).
OWN_IGNORE="$MW_ROOT/.trivyignore.yaml"
THIRD_PARTY_IGNORE="$MW_ROOT/deploy/trivyignore-third-party.yaml"
ignore_args=()
[ -f "$OWN_IGNORE" ] && ignore_args=(--ignorefile "$OWN_IGNORE")
slug() { printf '%s' "$1" | tr -c 'A-Za-z0-9._-' '_'; }

# trivy_gate KIND TARGET NAME: fail on HIGH/CRITICAL, write the findings below high to a report.
failed=""
image_extra=()
trivy_gate() {
  local kind="$1" target="$2" name="$3" extra=()
  case "$kind" in image) extra=(--ignore-unfixed ${image_extra[@]+"${image_extra[@]}"}) ;; fs) extra=(--scanners vuln) ;; esac
  info "security: trivy $kind $target"
  local high="$OUT/$(slug "$kind-$name")-high.json"
  if ! trivy_run "$kind" --quiet --exit-code 1 --severity HIGH,CRITICAL ${ignore_args[@]+"${ignore_args[@]}"} \
    ${extra[@]+"${extra[@]}"} --format json --output "$high" "$target"; then
    failed="$failed $kind:$name"
    # one line per finding: package version id severity fixed-in
    if have jq && [ -s "$high" ]; then
      jq -r '.Results[]? | .Target as $t | ((.Vulnerabilities[]? | "    \(.Severity) \(.VulnerabilityID) \(.PkgName) \(.InstalledVersion) -> \(.FixedVersion // "no fix") (\($t))"), (.Secrets[]? | "    SECRET \(.RuleID) \($t)"))' "$high" | sort -u
    else
      printf '    findings in %s\n' "$high"
    fi
  fi
  trivy_run "$kind" --quiet --exit-code 0 --severity UNKNOWN,LOW,MEDIUM ${extra[@]+"${extra[@]}"} \
    --format json --output "$OUT/$(slug "$kind-$name")-below-high.json" "$target" || true
}

# --- secrets -----------------------------------------------------------------------------
# tree PATH: a copy of the files git would commit under PATH (tracked + untracked, not ignored);
# outside a git checkout, PATH itself.
secret_tree() {
  local p="$1" snap="$OUT/secrets-tree"
  if git -C "$MW_ROOT" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    rm -rf "$snap"; mkdir -p "$snap"
    (cd "$MW_ROOT" && git ls-files -co --exclude-standard -z -- "$p" | tar --null -T - -cf - 2>/dev/null) \
      | (cd "$snap" && tar -xf -)
    printf '%s\n' "$snap"
  else
    printf '%s\n' "$MW_ROOT/$p"
  fi
}
gl_config=()
[ -f "$MW_ROOT/.gitleaks.toml" ] && gl_config=(--config "$MW_ROOT/.gitleaks.toml")
if [ -n "$m" ]; then targets="$modules"; else targets="."; fi
for t in $targets; do
  info "security: gitleaks on $t"
  tree="$(secret_tree "$t")"
  gitleaks_run dir --no-banner --redact ${gl_config[@]+"${gl_config[@]}"} \
    --report-format json --report-path "$OUT/$(slug "secrets-$t").json" "$tree" || failed="$failed secrets:$t"
done

# --- dependencies and images -------------------------------------------------------------
for mod in $modules; do
  name="$(basename "$mod")"
  if [ -x "$MW_ROOT/$mod/gradlew" ]; then
    info "security: $mod SBOM (cyclonedxBom)"
    if (cd "$MW_ROOT/$mod" && ./gradlew -q cyclonedxBom); then
      bom="$MW_ROOT/$mod/build/reports/cyclonedx/application.cdx.json"
      [ -f "$bom" ] && trivy_gate sbom "$bom" "$name"
    else
      failed="$failed sbom:$mod"
    fi
  fi
  trivy_gate fs "$MW_ROOT/$mod" "$name"
  for img in "$name:$IMAGE_TAG" "$name-init:$IMAGE_TAG"; do
    docker image inspect "$img" >/dev/null 2>&1 && trivy_gate image "$img" "$img"
  done
done
if [ -z "$m" ] && [ -f "$MW_ROOT/compose.yml" ]; then
  ignore_args=()
  [ -f "$THIRD_PARTY_IGNORE" ] && ignore_args=(--ignorefile "$THIRD_PARTY_IGNORE")
  # Upstream sample keys the secret scanner finds in the APISIX image: the placeholder cert nginx
  # needs before an SSL object matches (the edge serves its own cert, fallback_sni localhost) and
  # a key in the lua-resty-rsa docs. Decision in docs/security-findings.md.
  image_extra=(--skip-files usr/local/apisix/conf/cert/ssl_PLACE_HOLDER.key
    --skip-files usr/local/openresty/pod/lua-resty-rsa-1.1.1/lua-resty-rsa-1.1.1.pod)
  for img in $(sed -n 's/^ *image: *\([^ $]*@sha256:[0-9a-f]*\).*/\1/p' "$MW_ROOT/compose.yml" | sort -u); do
    trivy_gate image "$img" "${img%@*}"
  done
fi

# --- live: ZAP baseline on the edge and the container log scan ---------------------------
stack_up() {
  local file="$MW_ROOT/deploy/compose/mvp/compose.yml"
  [ -f "$file" ] || return 1
  compose_files "$file"
  [ -n "$(docker compose "${DC_F[@]}" ps -q --status running apisix 2>/dev/null)" ]
}
if [ "$LIVE" = 0 ]; then
  info "security: live stages (zap, logs) skipped (SECURITY_LIVE=0)"
elif stack_up; then
  project="${COMPOSE_PROJECT_NAME:-mw}"
  net="${project}_dmz"
  zap_dir="$OUT/zap"; rm -rf "$zap_dir"; mkdir -p "$zap_dir"; chmod 777 "$zap_dir"
  for target in ${MW_ZAP_TARGETS:-https://apisix:9443/ https://apisix:9443/console/}; do
    s="$(slug "$target")"
    info "security: ZAP baseline on $target (network $net)"
    docker run --rm --network "$net" -v "$zap_dir:/zap/wrk:rw" \
      -v "$MW_ROOT/scripts/lib/zap-gate.py:/zap/gate.py:ro" \
      -v "$MW_ROOT/deploy/zap/decisions.tsv:/zap/decisions.tsv:ro" \
      --entrypoint /bin/bash "$ZAP_IMAGE" -c \
      'zap-baseline.py -t "$1" -J "$2.json" -r "$2.html" -I >/dev/null 2>&1; python3 /zap/gate.py "/zap/wrk/$2.json" /zap/decisions.tsv' \
      _ "$target" "$s" || failed="$failed zap:$target"
  done
  info "security: log scan over every container of the project"
  "$(dirname "$0")/log-scan.sh" || failed="$failed logs"
elif [ "$LIVE" = 1 ]; then
  failed="$failed live:stack-down"
  printf 'security: SECURITY_LIVE=1 but the MVP stack is not running (make up && make e2e first)\n' >&2
else
  printf '==> WARNING security: MVP stack not running: ZAP baseline and log scan SKIPPED (the gate runs them with SECURITY_LIVE=1)\n' >&2
fi

if [ -n "$failed" ]; then
  printf 'security FAILED:%s\n' "$failed" >&2
  exit 1
fi
info "security passed (reports in build/security)"
