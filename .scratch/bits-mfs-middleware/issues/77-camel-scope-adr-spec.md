# 77: Camel only for external integration: ADRs and spec amendment

**What to build:** A docs-only change that records the decision the user made on 2026-11-18 (plan
`we-were-supposed-to-mellow-mccarthy`):

- **Apache Camel is for external partner integration only**, with partner routes held as config.
- **Internal Kafka messaging moves to Spring Kafka.**

Today ADR D1 and the spec prescribe Camel for both messaging and partners. Tickets 78–87 implement the change, and this ticket sets the rules they follow.

- **`docs/adr/D1-integration-engine.md`:** add a dated amendment.
  - Camel 4 is used **only** in `mw-adapter-*`, for partner HTTP/REST, SOAP (camel-cxf) and SFTP (camel-sftp).
  - Each partner is a Camel YAML DSL route stored as config and built from shipped Kamelets.
  - No other module depends on `org.apache.camel`.
  - Reason: Camel was chosen to onboard billers without code. Using it as a Kafka client added a hand-written retry chain and `Exchange`-coupled listeners for no integration benefit.
- **New `docs/adr/D27-kafka-client.md`:** Spring Kafka (`spring-kafka`, the version managed by Boot).
  - Consumers use `@KafkaListener` with record-level manual ack; producers use `KafkaTemplate` (idempotent, `acks=all`).
  - Non-blocking retry topics follow D2 (10 s / 1 min / 10 min), then a DLQ. **The topic names stay `<topic>.<group>.retry.<n>` / `<topic>.<group>.dlq`.**
  - The outbox and inbox are unchanged (D2).
  - The outbox relay runs as `@Scheduled`.
  - Consumers extract the trace and correlation into MDC.
- **New `docs/adr/D28-partner-routes-as-config.md`:**
  - **One route per partner:** a Camel YAML DSL route, `from: direct:partner-<id>`. Its input is the `PartnerCall` JSON and its output is `{outcome, partnerRef, code, message, reason}`.
  - **Kamelet library** (`mw-auth-*`, `mw-http-call`, `mw-soap-call`, `mw-sftp-*`, `mw-classify`, `mw-audit`): shipped as code and reviewed. Raw `http`/`cxf`/`sftp` are allowed only inside these Kamelets.
  - **The four guardrails:**
    - maker-checker in mw-admin-api;
    - a component allowlist checked on the parsed route model;
    - a dry-run against mw-simulators, with rollback to the last good version;
    - the host allowlist carried in the approved record.
  - **Static on purpose** (user decision): the biller catalogue `mw.billers` and the OpenBao secrets. A new catalogue entry or secret is config plus a billpay restart.
  - **New code is needed only** for a new protocol or auth scheme (a new Kamelet).
  - **No automatic redelivery of a non-idempotent partner call** (PAY). Redelivery applies only to INQUIRY/STATUS. A PAY with an unknown outcome
    is resolved by the saga through STATUS, never by sending it again.
  - **Kamelet versioning:** a breaking change ships under a new name (`mw-<name>-v2`). The old Kamelet stays until no LIVE route uses it.
  - **Secrets:** read through Camel's HashiCorp Vault properties function (`camel-hashicorp-vault`, `{{hashicorp:secret:…}}`) pointed at
    OpenBao, and loaded at startup.
  - **Shared error handling:** one `routeConfiguration` owned by the loader; partner routes may not declare their own `onException` or `errorHandler`.
  - **Route authoring:** Camel JBang (`camel run`) with the `mw-*` Kamelets against mw-simulators, Kaoto optional; the admin UI holds the YAML text.
- **`spec/bits-mfs-middleware-spec.md`:**
  - **D1 row** (`:23`): amend it.
  - **Section 3 stack table:**
    - `camel-spring-boot` applies to the adapters only;
    - add spring-kafka;
    - add `camel-yaml-dsl`, `camel-kamelet`, `camel-http`, `camel-jsonata`, `camel-resilience4j`, `camel-cxf-soap`, `camel-sftp`.
  - **4.4 packages:** `route/` holds Camel routes and exists in adapters only; `messaging/` holds Spring Kafka listeners and publishers.
  - **6.2 retry/DLQ producer:** the Spring Kafka error handler, not the Camel error handler (`:573`).
  - **6.4:** the outbox relay becomes a scheduler (`:599`).
  - **WP05** (`:141`): change it.
  - **12.1:** templates become Kamelets plus route-as-config. Add the onboarding procedure from D28.
  - **22.1 test strategy** (`:1270`):
    - Camel route tests apply to the adapters only;
    - add Spring Kafka listener ITs;
    - add the route loader's allowlist and dry-run tests;
    - keep the `AdviceWith` / `MockEndpoint` / `NotifyBuilder` row for adapter routes and Kamelets.
  - **Decision table:** add the D27 and D28 rows.
  - **Superseded camel-smpp / camel-mail mentions** (`:344`, `:728`, `:781-782`, `:788`): mw-notify and mw-ussd already use jSMPP and Spring Mail. Mark them as superseded by D1's amendment.
- **Root `CLAUDE.md`:**
  - the package table's `route/` line;
  - the test note "Camel's Kafka consumers 100 ms shutdown timeout", which becomes the listener container's shutdown timeout;
  - a new Never: "Never use Camel outside `mw-adapter-*`".
- **Map and docs:**
  - `.scratch/bits-mfs-middleware/MAP.md`: update the Kafka line.
  - `docs/concepts/` and `docs/tutorial/`: list the pages that describe Camel for Kafka. Each one is fixed in the ticket that changes the code (78–81); this ticket only adds a "superseded by D27" note.
- **`docs/runbook.md`:** add an "Onboard a biller" section stub (filled in by 83).

**Blocked by:** —

**Status:** done

- [x] ADR D1 is amended; D27 and D28 exist; `docs/adr/README.md` lists them
- [x] The spec decision table has rows D27 and D28; every Camel-for-Kafka statement in the spec is amended or marked superseded
- [x] `./gradlew :scriptTests` passes (`test_repo_docs.sh`: every decision row has an ADR, no dangling names)
- [x] No code changes in this ticket
