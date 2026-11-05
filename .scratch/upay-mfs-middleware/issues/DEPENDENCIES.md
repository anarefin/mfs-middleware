# Dependency graph — upay-mfs-middleware (run from 04875ae, 2026-11-05)
# Execution order is the ROW order below (user decision: prep ticket 53 runs first), not filename order.
# Status here is authoritative, not in the tickets. Earlier tickets 01-35, 48-50: done.
# ticket                                    blocked-by        status   elapsed  diffstat   commits

53-fast-gate-contract-manifests             —                 done     75m      161 files, +8515 −743  b807db7..d7d7916
36-admin-api-rbac-search                    35,53             done     29m       132 files 10090  36  3bc49e3..4792c86
37-maker-checker-engine-tsq-reversal        36,18,19          done     30m      68 files + 2855 − 43  50b8ef6..9c419f5
52-ops-wallet-unlock                        37                todo     —        —          —
38-partner-config-maker-checker             37,20             todo     —        —          —
39-ussd-menu-sms-template-management        37,26,24          todo     —        —          —
40-admin-ui-shell-search                    36                todo     —        —          —
41-admin-ui-approvals-reversal              40,37,52          todo     —        —          —
42-admin-ui-partner-ussd-template-editors   41,38,39          todo     —        —          —
51-m2-gate-spec-sync                        —                 done     5m       spec only              55957cc..f8a90cc
# --- M5 gate: make verify-changed BASE=04875ae JOBS=3 (stack down), fresh-stack e2e
43-e2e-acceptance-suite                     23,25,27,28,32,34,37  todo —        —          —
44-chaos-kill-mid-flow                      43                todo     —        —          —
45-gatling-100-tps                          43                todo     —        —          —
46-security-pass                            43,42             todo     —        —          —
47-docs-clean-host-bringup                  44,45,46          todo     —        —          —
# --- M6 gate: verify-changed + fresh-stack e2e, then ONE full make verify
