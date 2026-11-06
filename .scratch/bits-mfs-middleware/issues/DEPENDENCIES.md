# Dependency graph — bits-mfs-middleware (run from 04875ae, 2026-11-05)
# Execution order is the ROW order below (user decision: prep ticket 53 runs first), not filename order.
# Status here is authoritative, not in the tickets. Earlier tickets 01-35, 48-50: done.
# ticket                                    blocked-by        status   elapsed  diffstat   commits

53-fast-gate-contract-manifests             —                 done     75m      161 files, +8515 −743  b807db7..d7d7916
36-admin-api-rbac-search                    35,53             done     29m       132 files 10090  36  3bc49e3..4792c86
37-maker-checker-engine-tsq-reversal        36,18,19          done     30m      68 files + 2855 − 43  50b8ef6..9c419f5
52-ops-wallet-unlock                        37                done     17m      52 files + 1001 − 48  1260c19..2e8a6f6
38-partner-config-maker-checker             37,20             done     27m      46 files + 2553 − 62  6e58ae4..b09e3c9
39-ussd-menu-sms-template-management        37,26,24          done     39m      82 files + 4432 − 54  91e15b3..0d41ba1
40-admin-ui-shell-search                    36                done     40m      58 files + 3837 − 13  863f494..9bd4d32
41-admin-ui-approvals-reversal              40,37,52          done     9m       21 files + 1041 − 31  983998c..fcc58df
42-admin-ui-partner-ussd-template-editors   41,38,39          done     18m      29 files + 2392 − 26  fa6a799..b5a718a
51-m2-gate-spec-sync                        —                 done     5m       spec only              55957cc..f8a90cc
# --- M5 gate DONE (2026-11-06): verify-changed 14 modules 9 min (1 flaky IT fixed 0a9e33f); make up fixes cfa5cf9 (AOT fake secret), 2e49e02 (configtree); e2e 138/140, the 2 @bulk fails were Docker contention (MW-TMO-001 in 2 minutes), @bulk re-run green
43-e2e-acceptance-suite                     23,25,27,28,32,34,37  done 12m  20 files + 846 − 3  0f63e38..25bb2de
44-chaos-kill-mid-flow                      43                done     12m      5 files + 413 − 5  0a9e33f..9adf0f3
45-gatling-100-tps                          43                done     34m      39 files + 3088 − 16  2e49e02..ebfc691
46-security-pass                            43,42             done     73m      96 files + 1308 − 90  8ef342a..27b26da
47-docs-clean-host-bringup                  44,45,46          done     17m      37 files + 1294 − 11  527066d..23b166e
# --- M6 gate DONE (2026-11-06, 8e17214): verify-changed 14/14 (= full check, deploy changed); fresh-stack e2e 140/140 twice; edge-test 53/53; log-scan clean after the APISIX access-log masking fix (3c084a0); review fixes 8e17214. NOT RUN (user: no load tests): make chaos, make perf; NOT RUN: SECURITY_LIVE ZAP baseline
# --- Run 54-59 (2026-11-06): BitsPay-style deploy + test (plan check-the-project-deployment-ancient-possum). Row order = run order.
54-faster-module-gate                       —                 done     24m      67 files + 3011 − 103  3799fd9..860d578
55-cheaper-stack-startup                    54                todo
56-single-compose-file                      55                todo
57-mw-seeder                                56                todo
58-newman-api-suite                         57                todo
59-admin-ui-playwright-live                 56,57             todo
