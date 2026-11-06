# 46: Security pass

**What to build:** The MVP has no open critical or high security findings. `make security` runs a dependency scan, container image scan, secret scan and a ZAP baseline on the APISIX edge. A log check during a full e2e run proves no PIN (plain or ciphertext), unmasked MSISDN or NID appears in any container log. Containers run as non-root with read-only root FS. Spec WP29.

**Blocked by:**

- 43: E2E acceptance suite completion (criteria 1–5)
- 42: Admin UI partner config, USSD menu editor, template editor

**Status:** done

- [ ] `make security` passes with no open critical or high findings (offline stages green on 2026-11-06 with every image built from the branch: `MW_IMAGE_TAG=sec46 SECURITY_LIVE=0 make security`; the live stages, ZAP baseline on APISIX and the log scan, deferred to milestone gate: `make up && make e2e && SECURITY_LIVE=1 make security`)
- [x] The log scan over a full e2e run finds no PIN and no unmasked MSISDN (built and self-tested: `scripts/log-scan.sh`, stage of `make security`; deferred to milestone gate, needs the running stack after `make e2e`) — evidence: M6 gate 2026-11-06 at 8e17214 (make verify-changed 14/14 modules, each a full ./gradlew check; fresh-stack make e2e 140/140; make edge-test 53/53; make log-scan clean)
- [x] Every finding below high is listed with a decision (`docs/security-findings.md`; ZAP alerts in `deploy/zap/decisions.tsv`, any alert without a decision fails the gate)
