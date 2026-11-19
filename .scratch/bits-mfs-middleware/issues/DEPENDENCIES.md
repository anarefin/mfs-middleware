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
55-cheaper-stack-startup                    54                done     ~45m     see de4ddbca            5978052..de4ddbca
56-single-compose-file                      55                done     66m      see a7c45de5            15f6529c..a7c45de5
57-mw-seeder                                56                done     48m      see a5962796            31210399..a5962796
58-newman-api-suite                         57                done     217m     see 0762cdf3            040c26d2..bc4f222e
59-admin-ui-playwright-live                 56,57             done     27m+fix  see 2ad973ce,d614ac37   040c26d2..d614ac37
# --- Security group run (plan spec-mobile-money-middleware-rfp-md-spe-shiny-sparrow, decided 2026-11-14). Row order = run order.
60-security-spec-adrs                       —                 done     -        spec + 5 ADRs          -
61-softhsm-container                        60                done     13m      26 files +579 −18      4acf2a67..d86b16ab
62-mw-crypto-scaffold                       61                done     46m      140 files +10402 −51   d86b16ab..a3cbd979
63-mw-crypto-operations                     62                done     40m      63 files +4147 −33     d8032171..d5b7757f
64-payshield-provider-simulator             63                done     42m      70 files +4528 −37     db46af73,911f7f4f (+merges)
65-mw-crypto-xmldsig-http-signatures        63                done     27m      37 files +3007 −20     c5facff6, merge dccaa1c9
66-openbao-pki-mtls                         62                done     24m      40 files +2975 −70     d8032171..596a1e05
67-mw-auth-scaffold                         63,66             done     39m      145 files +10665 −58   d50edf8d..510b25f4 (+merges)
68-move-device-otp-sessions-to-mw-auth      67                done     37m      114 files +4805 −1035  88428dfa (+merge d1bda1e0)
69-mw-auth-login-tokens                     68                done     53m      151 files +5335 −930   7db63d47..1ebf8b47
70-pin-lifecycle-lockout-revocation         69                done     50m      120 files +4170 −195   eeaa7a1f..3a6994ca
71-txn-authorisation-assertion              69                done     32m      78+ files ~+2700       dc3d4dc5 (contracts), 1faaa4ec
72-channels-switch-to-assertion             71,70             done     71m      ~313 files +7.6k −2.6k 9f029b84,37f3d8a9 (+merges)
73-keycloak-realm-hardening                 69                done     22m      69 files +850 −551     6d5f5379..2766ca17
74-audit-anchor-signing                     63,66             done     19m      49 files +1681 −51     1f1a8a6f..9aec2426 (+review fix)
75-admin-key-management-ceremony            64,70             done     78m      90+ files +4782 −65    dafbc2d2..5f6e7c6b (+race fix)
76-security-tests-docs                      72,73,75          done     37m      77 files +3108 −71     8295b874..a58842af
# --- Run 77-87 (planned 2026-11-18): Camel only for external integration (plan we-were-supposed-to-mellow-mccarthy). Spring Kafka for messaging; partner routes as config (Camel YAML DSL + Kamelets). Catalogue + secrets stay static (user). Row order = run order; 79/80 and 82 can run in parallel after 78; 85/86/87 in parallel after 84.
77-camel-scope-adr-spec                     —                 done     6m       12 files +245 −44   65dac07e
78-template-spring-kafka                    77                done     ~40m     see 921c44ac,29009b74
79-spring-kafka-support-services            78                done     ~45m     see 46900c3b
80-spring-kafka-channels                    78                done     ~25m     see d010a0f6
81-spring-kafka-orchestrator-notify         78,79,80          done     ~19m     see 7fade216,5b646f5b
82-billpay-route-as-config-engine           78                done     57m      see 8f9f6a97..f52bcc11
83-partner-route-maker-checker-dry-run      82                done     ~90m     see fb639d47..9f4c0680
84-route-engine-telco-bank                  83                done     ~2h      see 89b8dac6..3ce2acd0
85-soap-partner-kamelet                     84                done     ~49m     see 0926e161,0fd772a6
86-sftp-batch-partner-kamelets              84                done     ~28m     see 1ef30ff6
87-auth-scheme-kamelets                     84                done     ~40m     see b4d2b1c1,7677e0fa
88-mw-recon-matching                        86                superseded by 126,127 (FRS run, 2026-11-19)
# --- Run 77-87 gate DONE (2026-11-18, 375bee59): review fix pass ccb8a6a5 (incl. 84 security S1/S2); gate fixes e74c2cfa (partner HttpClient auto-retry resent a PAY after 503) and c17e72f6 (admin-ui forms lost input on a refused submit). clean build (no slow-wait, user) green 3m53s; smoke 50/50; edge-test 53/53; API suite 717 assertions, 1 fail fixed by e74c2cfa (re-run 05/06/08 green); ui-e2e 18/18 after fix; tester-e2e 12/12 (after rebuilding the stale test-console image); log-scan clean. NOT RUN: slow-wait tests, perf, chaos. Open: partner audit dropped when txnId is null (bill fetch, dry-run; also on main); Next 16.4.1 security check fails local pnpm build.
# --- Run 89-138 (planned 2026-11-19): FRS gap closure, spec/FRS-Upay-Middleware.md (plan we-have-to-implement-harmonic-puzzle). Mocks only. Decisions: Redis, HashiCorp Vault, mw-auth keeps app tokens, repo names + FRS map, core wallet over REST (+assertion, events on Kafka), one service per party (12 GB budget), unified /mock-control. Row order = run order. Tickets written only; no implementation started (user).
# --- M7 Foundation (FRS phase 1)
89-frs-decisions-adr-spec                   —                 open
90-redis-swap                               89                open
91-vault-swap                               89                open
92-contracts-canonical-additions            89                open
93-core-mock-rest-api                       92                open
94-core-mock-model                          93                open
95-orchestrator-core-rest-client            93                open
97-adapter-reverse-health                   89                open
96-orchestrator-saga-frs                    95,97             open
98-routing-module                           96,97             open
99-idempotency-duplicates-replay            89                open
100-edge-client-registry                    90,91,99          open
101-openapi-swagger-wsdl                    89                open
102-mock-control-engine                     89                open
103-mock-control-core-and-reset             102,94            open
104-frs-e2e-harness                         96,98,103         open
# --- M7 gate: P2P T1-T12 via the app API; clean build; fresh-stack API suite
105-app-preview-confirm                     95                open
106-app-types-b2b                           94,105            open
107-app-nonfinancial                        94,105            open
108-app-soap-facade                         105               open
109-ussd-frs                                94,105            open
110-notify-push                             102               open
111-notify-campaign-optout-log              110               open
# --- M8 gate: UC-02..07 on app and USSD
112-telco-four-ocs                          97,98,102         open
113-biller-catalogue                        98                open
114-bill-aggregators                        113,97,102        open
115-mw-adapter-merchant                     97,102            open
116-checkout-refund-list                    95,96             open
# --- M9 gate: UC-08..12
117-bank-avl-w2b                            97,98,102         open
118-mw-adapter-card                         95,97,102         open
119-mw-adapter-remit                        95,97,102         open
120-mw-adapter-atm                          95,97,102         open
# --- M10 gate: UC-13..17
121-mw-adapter-interop                      92,97,102         open
122-banglaqr                                121               open
# --- M11 gate: UC-18, UC-19
123-file-mocks                              102,94            open
124-mw-compliance-ekyc                      94,102,106        open
125-aml-feed-actions                        124,123,96        open
126-mw-recon-matching                       123,95            open
127-mw-recon-reports                        126               open
128-mw-data-export                          123               open
129-portal-bulk-sp                          95,116            open
130-audit-frs                               97                open
# --- M12 gate: UC-01, UC-20..22
131-admin-partner-credentials               91,97             open
132-admin-routing-billers-clients-ui        98,100,113        open
133-admin-ussd-editor-templates             109,111           open
134-admin-actions-compliance-recon-ui       125,127,111       open
135-admin-monitoring-users-audit-mock       103,130           open
136-developer-guide-sample-connector        114,131           open
137-frs-e2e-completion                      104-129(flows)    open
138-frs-acceptance-gate                     137               open
# --- M13 gate: FRS 10.4 acceptance criteria 1-7
