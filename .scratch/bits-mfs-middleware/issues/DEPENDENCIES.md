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
89-frs-decisions-adr-spec                   —                 done     11m      16 files +812 −26      48641b1c
90-redis-swap                               89                done     40m      132 files +575 −531    3fb5ffca
91-vault-swap                               89                done     80m      350 files +1206 −3347  8e5cfb4f
92-contracts-canonical-additions            89                done     50m      103 files +1336 −133   a27c953a
93-core-mock-rest-api                       92                done     31m      43 files +2354 −36     cbfb273a
94-core-mock-model                          93                done     120m     115 files +2556 −614   7fb7429f
95-orchestrator-core-rest-client            93                split (95-split-decisions.md, 2026-11-20)
95a-orchestrator-core-rest                  93                done     60m      70 files +2856 −1135   b43b392f,53c83472
95b-auth-admin-core-rest-retire-topics      95a               done     -        177 files +1816 −2372  70758896,ff3780bc
95c-hold-expired-wallet-state               95a,94            done     -        10 files +496 −23      da3497c4
97-adapter-reverse-health                   89                done     110m     99 files +3635 −156    8921933e
96-orchestrator-saga-frs                    95,97             split (96-split-decisions.md, 2026-11-20)
96a-inquiry-schedule-ops-alert              95a,97            done     75m      26 files +384 −152     1f61e790
96b-partner-reverse-refused-commit          96a               done     -        22 files +718 −37      587f711b  (:mw-orchestrator:build passed; the live suite was not run)
96c-partner-leg-reversal-folder30           96b               done     -        20 files +548 −45      29cc15b8
98-routing-module                           96a,97            done     -        78 files +2755 −82     245a68ee
99-idempotency-duplicates-replay            89                done     110m     95 files +2601 −110    e3d9c7c6,45ffe6a3
100-edge-client-registry                    90,91,99          split (100-split-decisions.md, 2026-11-20)
100a-client-registry-apisix-sync            90,91,99          done     30m      61 files +2570 −36     cd3d58ea
100b-oauth2-cc-clients                      100a              done     -        26 files +1049 −22     c7043555  (before 118-121; inbound mTLS stays 100c)
100c-inbound-mtls-edge-tests                100a              open   (run by M13)
101-openapi-swagger-wsdl                    89                done     75m      143 files +6928 −31    09fb498a
102-mock-control-engine                     89                done (102a b8f2d843; 102b 33m 49 files +1774 −467 aacc96ef)
103-mock-control-core-and-reset             102,94            done     120m     90 files +3425 −249    8c4ed022
104-frs-e2e-harness                         96a,98,103        done     -        10 files +630 −15      524e1bb6  (folder 41-p2p-frs; 40 already taken by mock-reset. Live Newman and the M7 gate were not run)
# --- M7 gate FAIL (2026-11-20, base 3f68eb02; gate fixes 784285bb): clean build PASS; smoke 60/60; edge-test 65/65; folder 41 54/54; log-scan clean; ui-e2e 25/25; tester-e2e 40/40. Whole suite FAIL 2 of 1386, not re-run (folder 13 audit change-request id scrubbed; folder 44 AML journal first WRITE was another file). The live suite was not re-run after the 107 merge.
105-app-preview-confirm                     95                done     -        67 files +3346 −158    d735f224
106-app-types-b2b                           94,105            done     -        69 files, +1127 −120    ed9823a8
107-app-nonfinancial                        94,105            split (107-split-decisions.md, 2026-11-20)
107a-app-preferences                        94,105            done     -        65 files +2028 −21     96650ec1  (saved billers and favourites are not encrypted, user decision)
107b-app-core-reads                         107a              done     -        32 files +1201 −15     ab25b616  (app-gateway is a core client for profile, limits, and statement. The live suite was not re-run after this merge)
108-app-soap-facade                         105               done     -        46 files +2417 −61     2f199263
109-ussd-frs                                94,105            split (109-split-decisions.md, 2026-11-20)
109b-ussd-session                           94,105            done     -        52 files +1246 −92     34f92e26
109a-ussd-tlv                               109b              done     -        30 files +1237 −374    d23caa3c  (same 3GPP TLV tags for all four MNOs)
109c-ussd-menus                             109a              done     -        41 files +2360 −109    5a2445b0  (fee from core charges/calculate; change-PIN stays on maintenance text. Live suite not re-run)
110-notify-push                             102               done     37m      86 files +2628 −75     76ab0c4c,4757e709
110b-push-binding-epoch                     110               done     -        34 files +438 −131     480b1d76
111-notify-campaign-optout-log              110               done     -        79 files +2444 −36     0a3f6bf4
# --- M8 gate (2026-11-20, base d84fce30; code fixes 070c8217, 706941df, c95ec100, e9381fed): clean build PASS; stack up PASS; smoke 60/60; edge-test 66/66; new folders 18, 19, 20, 28, 29, 30, 43 USSD, 48, 60, 63: 216/0. Whole suite ONE run 1483 assertions, 3 failed, not re-run. Folder 25 fixed (customer first lock follows MW_AUTH_LOCK_LEVEL1) and folder 28 fixed (statement walks later pages); recheck of 25, 28, 36 was 179/0. Folder 36 replay HTTP 429 MW-SYS-RATE_LIMITED left (rate-limit window not waited out). Folders 13 and 44 passed. log-scan clean. ui-e2e 21 passed, 4 failed, then those 4 passed. tester-e2e 36 passed, 4 failed; the three USSD specs then passed. Leftover: tester top-up REJECTED MW-SYS-PARTNER_CIRCUIT_OPEN. 112+ stay open.
# --- M8 review fixes merged (2026-11-20, 680ad323, 8 files +356 −26): SOAP confirm and balance use the device gate; the USSD mini statement returns the latest movements; XSD boolean 1 is a confirmed duplicate. Partner BULK_DISBURSE still cannot post and is not fixed: the core wallet contract has no PARTNER wallet type. 112a stays open.
# --- 112a merged (2026-11-20, eba33d4b, implementer dc7c74e0, 38 files +2043 −58): four operator OCS profiles. 112b (postpaid amount from the minimum through the outstanding bill) is next on the orchestrator and waits while ticket/m8-partner-disburse is in flight.
# --- M8 partner disbursement merged (2026-11-20, d2407234, 34 files +615 −47; ccdcb713, 1bb02984, 9dd54f10): a partner disbursement posts as an organisation wallet. Wallet 01412000001 is seeded ORG. A seeder-marked organisation wallet logs in as a partner; an unmarked organisation wallet gets no app token. No PARTNER core wallet type. 112b is unblocked and is not done: postpaid confirm accepts any amount from the minimum due through the outstanding bill, stored on the quote.
# --- 112b merged (2026-11-20, c5dd4395, implementer b79ccbd9, 46 files +1372 −78): postpaid confirm accepts any amount from the stored minimum due through the outstanding bill. Preview returns both and confirm does not fetch again. An amount outside that range is refused before a hold. The four OCS profiles, organisation-wallet disbursement, and marked-partner login stay. 112c (T1–T12 for two operators, no timer sleep) is next and not started.
# --- M8 scope: UC-02..07 on app and USSD
112-telco-four-ocs                          97,98,102         split (112-split-decisions.md, 2026-11-20; pay from the minimum through the outstanding bill)
112a-telco-ocs-profiles                     97,98,102         done     -        38 files +2043 −58     dc7c74e0
112b-postpaid-amount                        112a              done     -        46 files +1372 −78     b79ccbd9
112c-topup-matrix                           112b              done     -        31 files +1005 −27     266d341a  (T5 skipped: it only passes by waiting out the inquiry schedule. T11 is ticket 125. T12 is ticket 127)
113-biller-catalogue                        98                done (split 113a,113b,113c; 113-split-decisions.md)
113a-biller-catalogue-owner                 98                done     ~45m     43 files +2020 −40     0d0f506d..a79d4435
113b-biller-channel-projections             113a              done     25m      49 files +860 −98      fd910ff4  (fetched amount attached as billOutstandingMinor via Redis 30 min; USSD bill month = current Asia/Dhaka month, assumption; Newman 06/19/49 changed, not run live)
113c-biller-amount-rule                     113a              done     29m      15 files +470 −9       8414700f  (orchestrator V17 biller_rule, MW-VAL-032, Newman folder 49-biller-catalogue; FIXED needs the channel to attach billOutstandingMinor -> 113b)
114-bill-aggregators                        113,97,102        done     -        47 files +3400 −15     06d5d599  (folder 51-bill-aggregators; Newman 51/06/07 not run live. T5/T11/T12 n/a. Orchestrator V19 bill_aggregator_routing)
115-mw-adapter-merchant                     97,102            split (115-split-decisions.md, 2026-11-20)
115a-merchant-adapter                       97,102            done     31m      270 files +25395 −31   a58834bd,679349b0 (admin-api migration renumbered V17 at merge)
115b-merchant-pay-flow                      115a,113          done     -        40 files +1363 −37     8e457021  (folder 52-merchant-soap; Newman not run live. Validate before pay, notify retry then audit dead letter)
139-dry-run-oauth2-isolation                115a              done     12m      20 files +648 −28      901630b7  (open: token cache entries not evicted on route discard)
116-checkout-refund-list                    95,96             done (split 116a,116b,116c; 116-split-decisions.md)
116a-core-partial-reversal-list             95,96             done     32m      ~32 files +500 −35     d42c5172,2bc20979
116b-orchestrator-refund-flow               116a              done     35m      17 files +640 −15      369d1af6,5eb639c1  (open: SYSTEM full reversal after a partial refund -> MANUAL_REVIEW)
116c-checkout-refund-api                    116b              done     24m      49 files +1864 −53     11611448  (Newman folder 50-checkout-refund, not run live; test-console app-users.test.ts fails 1, check at gate)
# --- M9 gate: UC-08..12
117-bank-avl-w2b                            97,98,102         split (117-split-decisions.md, 2026-11-20)
117a-bank-adapter-avl-w2b                   97,98,102         done     13m      34 files +2881 −27     692ab069
117b-avl-api-linked-accounts                117a              done     29m      44 files +1640 −9      343b4088,3cfc49cf  (no mTLS on bank adapter side; local AES key, not mw-crypto)
117c-w2b-flow-channels                      117b              done     39m      35 files +880 −46      ef2b5c5b,1953b95b  (orchestrator V18 bank routing; app.v1 1.15.0; USSD W2B menu waits for user security decision; status events carry full bank account id, pre-existing)
117d-bank-gateway-simulator                 117a              done     19m      44 files +1416 −98     36bea5a1
117e-bank-suite-regression                  117b,117c,117d    done     75m      19 files +837 −23      341eee66  (Newman folder 55, not run live; bank callback APISIX route + BANK_GATEWAY_HMAC_SECRET (re-copy .env); log-scan account rule; USSD "use the app" screen not built: seed menu change pending)
118-mw-adapter-card                         95,97,102         split (118-split-decisions.md, 2026-11-20)
118a-card-adapter                           95,97,102         done     27m      274 files +25935 −28   4f974e08,005a0983  (admin-api V18; postgres max_connections 100->120; 3DS webhook endpoint -> 118b)
118b-card-flow-channels                     118a              split (2026-11-20, 1 h cap)
118b1-card-3ds-webhook                      118a              done     15m      27 files +1096 −7      bd5c540e  (HMAC/nonce/PLT-014 dedup; family card on mw.partner.inbound.v1; APISIX callback route; admin-api cybersource seed)
118b2-add-money-card-flow                   118b1             done     30m      49 files +1944 −31     4ff19347  (orchestrator V20 card routing; app.v1 1.16.0; Newman 56 not run live; card refund of a COMPLETED add-money not built; hosted page has no edge route)
119-mw-adapter-remit                        95,97,102         split (119-split-decisions.md, 2026-11-20)
119a-remit-adapter                          95,97,102         done     -        286 files +26376 −36   bfa47ddf  (admin-api V19 remit; host port 8101. RemitInFlow, Newman 53, lookup -> 119b)
119b-remit-flow                             119a              done     45m      34 files +647 −57      2323f101  (REMIT_IN flow once per inbound id; adapter outbox; APISIX partner-remit-v1; HMAC fingerprint; core-mock REMIT_IN validate without assertion)
119c-remit-lookup-suite                     119b              done     60m      53 files +1446 −24     987406f0..37a9818b (merge 43ed7190; REMIT_IN without assertion only SETTLEMENT* -> customer; core account-clients: mw-adapter-remit GET accounts only; KC_MW_ADAPTER_REMIT_SECRET (re-copy .env); Newman 53 not run live)
120-mw-adapter-atm                          95,97,102         split (120-split-decisions.md, 2026-11-20)
120a-atm-adapter-inbound                    95,97,102         done     -        283 files +26354 −31   372dceb2  (admin-api V20 atm; host port 8100. Inbound SOAP recorded locally; publish, token, cash flows, Newman 54 -> 120b)
120b-atm-token-flows                        120a              split (120-split-decisions.md, 1 h cap)
120b1-atm-inbound-hardening                 120a              done     55m      22 files +755 −122     490f8531  (cash ops fail closed behind mw.atm.inbound.cash-enabled; state CAS + HMAC fingerprint, atm V9; APISIX partner-ucbatm-ws; PasswordDigest + nonce/Created)
120b2-atm-token-issue                       120b1             done     ~60m     71 files +2475 −158    dd71de9b,b896926e,ca777d53,f9de8c2d (merge 6bf20664; app.v1 1.18.0; mw-auth per-path caller rules, step-up compare-and-delete MW-AUTH-049; adapter DECLINED on bad token)
120b2b-atm-auth-wiring                      120b2             open   (Keycloak services client mw-adapter-atm + secret, Vault provision, mTLS client cert, shell tests; before 120b3; after 124b to avoid Keycloak/provision conflicts)
120b3-atm-cash-flows                        120b2             open   (atm branch of mw.partner.inbound.v1, ATM_CASH_OUT/IN flows, cash-enabled on, mock ATM page signs digest, AtmCashOutIT, Newman 54)
# --- M10 gate: UC-13..17
121-mw-adapter-interop                      92,97,102         split (121-split-decisions.md, 2026-11-20)
121a-interop-adapter-module                 92,97,102         done     90m      270 files +25086 −28   80896661  (admin-api V21 interop; host port 8102; NPSB participant mock; no npsb seed yet)
121b-npsb-iso20022-wire                     121a              split (1 h cap)
121b1-iso20022-mapping-xmldsig              121a              done     60m      31 files +8375 −16     662834c6  (Prowide SRU2025-10.4.1; mw-crypto XMLDSIG purpose for interop)
121b2-npsb-seed-inbound-mock                121b1             done     ~25m+    55 files +7060 −44     bee9a5e5..1d67e5bd,a6a599ec (merge 2ee17a9a; interop V8 npsb_inbound permanent dedup; CreDtTm 24 h/+5 min; mock signs with provisioned switch key; inbound kind stays NPSB_CREDIT with txnType NPSB_IN -> 121c keys on txnType; edge mTLS -> 100c)
121c-npsb-flows-api                         121b              open   (orchestrator flows, app preview/confirm, Newman 57-npsb)
122-banglaqr                                121               open
# --- M11 gate: UC-18, UC-19
123-file-mocks                              102,94            done     90m      72 files +3401 −39     57a73927
124-mw-compliance-ekyc                      94,102,106        split (124-split-decisions.md)
124a-compliance-ekyc-api                    94,102,106        done     ~60m     152 files +11689 −14   81f431f6,b984ba1b,3be57f1e  (3 security fixes: subject scoping, NID reuse -> MANUAL_REVIEW, unscoped read-only)
124b-compliance-wiring                      124a              done     53m      78 files +4688 −89     6cd5f1bd,838ea734,cca74793,47887e5f,f40ac999 (merge 7876361d; core PUT kyc-level mw-compliance only; DOB-only -> MANUAL_REVIEW; EC NID mock; compliance port 8103 + KC_MW_COMPLIANCE_SECRET (re-copy .env); app.v1 1.19.0 onboarding; open: retryable false after a failed core call, BASIC never issued)
124c-kyc-consumers-suite                    124b              open   (mw-notify SMS + mw-audit-dw row on mw.kyc.completed.v1, topics.txt retry groups, Newman 64-ekyc, log-scan; see 124-split-decisions.md)
125-aml-feed-actions                        124,123,96        open
126-mw-recon-matching                       123,95            done     -        150 files, +10903 −38  62f06496
127-mw-recon-reports                        126               done (split 127a,127b,127c; 127-split-decisions.md)
127a-recon-reports-queue                    126               done     18m      24 files +1281 −11     9ac2723a,aaf3660d
127b-recon-exception-actions                127a              done     34m      62 files +2139 −41     29d8922b,da80afa9  (recon /internal needs mw-admin-api service JWT; open: post-approval recon call not retried; FORCE_COMMIT that fails stays ACTIONED; data-export DW schema needs txnId)
127c-trust-job-suite                        127b              done     32m      37 files +991 −14      cf16db41,0f015d57  (core GET /core/v1/reports/emoney-total, report-clients mw-recon only; e-money total = every wallet type but SYSTEM; Newman 61 not run live; T12 + mock business day -> 137)
128-mw-data-export                          123               done     -        189 files +12102 −46   9a1ccad6
129-portal-bulk-sp                          95,116            split (129-split-decisions.md, 1 h cap)
129a-bulk-xlsx-upload                       95,116            done     75m      18 files +1013 −114    a1acfbd1,fba3ae6b  (poi-ooxml 6.0.0; bounded zip/shared strings/number reader; path stays /bulk/batches)
129b-bulk-preview                           129a              done     25m      48 files +1137 −236    dd3083e3,e2de20a5 (merge e7d1d038; portal-api V7; PREVIEW expires 72 h; MW-VAL-036/037; Newman 21/22/23/90 submit after upload, not run live)
129c-bulk-balance-retry                     129b              done     31m      40 files +1623 −63     bc7f1ff3,b577771c (merge 98290bf7; orchestrator replays FAILED for a reused key -> per-attempt key bulk-<batch>-<line>-a<n> only after proof nothing posted; BLOCKED_FUNDS; core balance-clients: mw-portal-api, ORG wallets only (contract change per ticket "via core REST", like report-clients); portal-org.v1 1.1.0. Open: retry round not balance-checked, pause/resume of APPROVED skips the dispatch check, BLOCKED_FUNDS never expires; test_chaos.sh stop-load test fails on clean upay-main)
129d-sp-portal                              129a              open
129e-bulk-suite                             129b,129c,129d    open   (Newman 65)
130-audit-frs                               97                done     90m      60 files +3231 −118    e8cfafea,ddb88b1f
130b-audit-hops-channels-core-notify        130               done     40m      56 files +1490 −49     3ca26066,9c0f49f8 (merge 4705cde3; AUD-01 done, AUD-04 with 135; no balance answer, checkout redirect or OTP text audited; NOTIFICATION and bulk-child hops carry no latency)
# --- M12 gate: UC-01, UC-20..22
131-admin-partner-credentials               91,97             split (131-split-decisions.md, 2026-11-20)
131a-admin-credentials-backend              91,97             done     135m     59 files +3307 −41     ce25fff7
131b-maintenance-endpoint-profile           97,131a           done     -        40 files +1912 −72     6a806373,1b154473  (before 115, 118-121; routing-as-MAINTENANCE waits for 98)
# --- Salvage DONE (2026-11-20, 2308d22e): 95b, 105, 110b, 131b fast-forwarded onto upay-main. Review fix 19 files +342 −57. NOT RUN: clean build, stack, api-test.sh, tester-e2e. 105 top-up operator resolution waits for 98.
131c-admin-ui-partner-tabs                  131a,131b         done     -        25 files +1329 −49     949689f8
# --- Wave DONE (2026-11-20, 4b5cd4af): 98, 100b, 111, 128, 131c on upay-main. 104 not started: its done check runs the live API suite. Live Newman, edge-test and ui-e2e were not run.
132-admin-routing-billers-clients-ui        98,100a,113        open
133-admin-ussd-editor-templates             109,111           split (133-split-decisions.md, 2026-11-20)
133a-ussd-tree-editor-preview               109,111           done     13m      9 files +641 −35       5022566d,3e420fed  (UI only; live Playwright not run)
133b-ussd-preview-api                       133a              done     20m      36 files +1254 −19     92295544 (merge 3619db28; short code -> root menu = mw.content.ussd.<mno> config copy in admin-api; operator regex never run in preview; folder 67 started, not run live)
133c-notify-rules                           133a              done     36m      71 files +1764 −49     248389b6,52abf59a,78251524,84a6c9d7 (merge 4cb78ed1; NOTIFY_RULE configType in all 23 enums; admin-api V23, notify V21; seeded rules not editable from console; Playwright 51 + folder 67 not run live)
133d-template-test-send                     133a              open
134-admin-actions-compliance-recon-ui       125,127,111       open
135-admin-monitoring-users-audit-mock       103,130           open
136-developer-guide-sample-connector        114,131a,131b     split (136-split-decisions.md)
136a-guide-sample-trigger-docs              114,131a,131b     done     32m      22 files +1931 −11     cef3880d (merge 906f72e9; docs/connectors, docs/mocks, docs/api-catalogue.md; samplebiller mock; MockTriggers registry + docs drift tests)
136b-console-walkthrough-suite              136a,132          open   (integration-maker/checker seeded persona, e2e/94-add-biller.spec.ts, Newman 70-sample-connector, CON-06)
137-frs-e2e-completion                      104-129(flows)    open
138-frs-acceptance-gate                     137               open
# --- M13 gate: FRS 10.4 acceptance criteria 1-7
# --- M9 review fix A merged (2026-11-20, fix/m9-review-a eb265a20, 37 files +509 −40): aggregator bare 404 -> UNKNOWN, billMonth sent, checkout refund kept PENDING + duplicate guard, full reversal refused after a live refund (MW-VAL-021), dry-run OAuth2 dummy secret (8 adapter copies; interop synced at merge). Open: merchant NOTIFY retry/DLQ, linked-account owner binding + AVL rate limit (user decision), AVL check in W2B preview, 7 Lows.
# --- M9 review fix recon merged (2026-11-20, fix/m9-review-recon, 30 files +745 −54; admin-api V22, recon V7): REC-05 reopen, REC-07 62F date, re-run dedup, ShedLock, :86: masking, DAT-01, REC-06 stale, stale approval on changed gap, admin-api action retry. Open: dispute/accept-as-explained maker-checker, missed trust days catch-up, names in narrative. Post-merge build of 10 modules + repo checks green.
# --- User decisions 2026-11-20 evening: (1) bank link = AVL holder name must match the wallet KYC name + AVL rate limit per wallet (fix B); (2) recon RAISE_DISPUTE and ACCEPT_AS_EXPLAINED always go through maker-checker (recon follow-up, with 134); (3) eKYC DOB-only match -> MANUAL_REVIEW, only FULL is auto-issued (124b); (4) core gets PUT /core/v1/accounts/{walletId}/kyc-level (open item for Upay's real core; 124b); (5) AML file tokenises parties with HMAC-SHA256 (125).
# --- M9 review fix B merged (2026-11-20, fix/m9-review-b fc6248e0, 71 files +1005 −104): bank link holder-name match in mw-adapter-bank (MW-VAL-ACCOUNT_HOLDER_MISMATCH) + AVL link attempts 5/h per wallet (dev 1000), W2B preview re-runs AVL, merchant NOTIFY UNKNOWN -> orchestrator backoff retry -> audit dead letter, bank/aggregator mock atomicity, biller UPSERT unknown keys refused. app.v1 1.17.0, bank.v1. Open: linked-account cap race, local AES.
