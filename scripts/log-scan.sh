#!/usr/bin/env bash
# Container log scan (spec 18.3, 21.1, WP29, ticket 46): no PIN (plain or ciphertext), no
# unmasked MSISDN and no unmasked NID in any log line.
# Usage: scripts/log-scan.sh            every container log of the MVP project (run after make e2e)
#        scripts/log-scan.sh FILE...    the given files ('-' = stdin), e.g. saved logs
# Exit 1 on a finding. A finding is printed with its source and line number and the matched
# value masked, so this script never repeats what it found.
#
# Rules (what masking leaves is never matched: 0171****789, NID ***123, PIN ****):
#   msisdn     BD MSISDN 01[3-9]XXXXXXXX or +880/880 1[3-9]XXXXXXXX, not part of a longer
#              digit run
#   nid        a field named nid / nationalId / national_id / nidNumber holding 10-17 digits
#   pin        a field named pin / newPin / oldPin / pinBlock holding anything but stars or
#              [REDACTED]; also a field named encryptedPin / pinCiphertext / ciphertext with any value
#   ciphertext a base64 run of 340+ characters (RSA-2048 OAEP ciphertext is 344): PIN-shaped
# False positives that are justified go into scripts/log-scan.allow (one extended regex per
# line, matched against the whole line; '#' comments), each with its reason.
set -uo pipefail
. "$(dirname "$0")/lib/modules.sh"

allow="$MW_ROOT/scripts/log-scan.allow"

scan() { # scan SOURCE-LABEL < lines
  awk -v src="$1" -v allowfile="$allow" '
    BEGIN {
      n = 0
      while ((getline l < allowfile) > 0) { if (l !~ /^[ \t]*(#|$)/) allowed[n++] = l }
      found = 0
    }
    function mask(s) { gsub(/[0-9]/, "#", s); if (length(s) > 24) s = substr(s, 1, 12) "..." substr(s, length(s) - 7); return s }
    function report(rule, m) { printf "FINDING %s %s:%d %s\n", rule, src, NR, mask(m); found++ }
    {
      line = $0
      for (i = 0; i < n; i++) if (line ~ allowed[i]) next
      lower = tolower(line)
      if (match(line, /(^|[^0-9])(\+?880|0)1[3-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]([^0-9]|$)/)) report("msisdn", substr(line, RSTART, RLENGTH))
      if (match(lower, /(^|[^a-z])"?(nid|nationalid|national_id|nidnumber)"?[ ]*[:=][ ]*"?[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]/)) report("nid", substr(line, RSTART, RLENGTH))
      if (match(lower, /(^|[^a-z])"?(pin|newpin|oldpin|pinblock)"?[ ]*[:=][ ]*"?[^"*,} \[]+/)) {
        v = substr(lower, RSTART, RLENGTH); if (v !~ /(null|redacted)$/) report("pin", substr(line, RSTART, RLENGTH))
      }
      if (match(lower, /(^|[^a-z])"?(encryptedpin|pinciphertext|ciphertext)"?[ ]*[:=][ ]*("[^"*\[]|\{)/)) report("pin", substr(line, RSTART, RLENGTH))
      t = line
      gsub(/[^A-Za-z0-9+\/]/, " ", t)
      k = split(t, parts, " ")
      for (j = 1; j <= k; j++) if (length(parts[j]) >= 340) { report("ciphertext", parts[j]); break }
    }
    END { exit(found > 0 ? 1 : 0) }
  '
}

rc=0
if [ $# -gt 0 ]; then
  for f in "$@"; do
    if [ "$f" = - ]; then scan stdin || rc=1; else scan "$f" <"$f" || rc=1; fi
  done
else
  file="$MW_ROOT/deploy/compose/mvp/compose.yml"
  [ -f "$file" ] || die "log-scan: deploy/compose/mvp/compose.yml does not exist"
  compose_files "$file"
  services="$(docker compose "${DC_F[@]}" ps -a --format '{{.Service}}' 2>/dev/null | sort -u)"
  [ -n "$services" ] || die "log-scan: no containers in the MVP project (make up && make e2e first)"
  for s in $services; do
    docker compose "${DC_F[@]}" logs --no-color --no-log-prefix "$s" 2>&1 | scan "$s" || rc=1
  done
fi
if [ "$rc" -ne 0 ]; then
  printf 'log-scan FAILED: PIN, ciphertext or unmasked MSISDN/NID in the logs above\n' >&2
else
  info "log-scan: no PIN, PIN ciphertext, unmasked MSISDN or NID found"
fi
exit "$rc"
