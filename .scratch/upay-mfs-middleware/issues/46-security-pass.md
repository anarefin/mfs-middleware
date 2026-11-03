# 46: Security pass

**What to build:** The MVP has no open critical or high security findings. `make security` runs a dependency scan, container image scan, secret scan and a ZAP baseline on the APISIX edge. A log check during a full e2e run proves no PIN (plain or ciphertext), unmasked MSISDN or NID appears in any container log. Containers run as non-root with read-only root FS. Spec WP29.

**Blocked by:**

- 43: E2E acceptance suite completion (criteria 1–5)
- 42: Admin UI partner config, USSD menu editor, template editor

**Status:** ready-for-agent

- [ ] `make security` passes with no open critical or high findings
- [ ] The log scan over a full e2e run finds no PIN and no unmasked MSISDN
- [ ] Every finding below high is listed with a decision
