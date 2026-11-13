# Architecture decision records

One record per decision in the spec's two decision tables: D1–D19 ("Decisions confirmed"; D17 has a record but no table row) and
M1–M13 ("MVP decisions"). The spec stays the source of the decision text; these records add the
context, the consequences and where the decision lives in the repo. Later refinements (milestone
gate decisions) are noted in each record's Status line; the spec marks them **Decision
<date>** where it was changed.

Format: `# <id>: <title>`, a `Status:` line, then Context, Decision, Consequences and "Where in
the repo". A new decision gets the next free id in its table (spec first, then a record here);
`scripts/test/test_repo_docs.sh` fails when a table row has no record.

| Id | Decision | Status |
| --- | --- | --- |
| D1 | [Apache Camel on Spring Boot 4, JDK 25 with virtual threads](D1-integration-engine.md) | Accepted, 2026-11-03 (spec revision 1) |
| D2 | [Apache Kafka in KRaft mode](D2-messaging-backbone.md) | Accepted, 2026-11-03 (spec revision 1) |
| D3 | [Apache APISIX at the edge](D3-api-gateway.md) | Accepted, 2026-11-03 (spec revision 1) |
| D4 | [PostgreSQL 18, only where a service needs its own state](D4-middleware-database.md) | Accepted, 2026-11-03 (spec revision 1) |
| D5 | [Asynchronous Kafka commands and events to the core](D5-core-wallet-link.md) | Accepted, 2026-11-03 (spec revision 1) |
| D6 | [Next.js admin console](D6-admin-console.md) | Accepted, 2026-11-03 (spec revision 1) |
| D7 | [One USSD adapter, SMPP or HTTP per MNO by configuration](D7-ussd-transport.md) | Accepted, 2026-11-03 (spec revision 1) |
| D8 | [Docker images for every component](D8-runtime.md) | Accepted, 2026-11-03 (spec revision 1) |
| D9 | [Keycloak, OpenBao and Resilience4j](D9-platform-services.md) | Accepted, 2026-11-03 (spec revision 1) |
| D10 | [Out of this spec: orchestration, CI/CD and observability tooling](D10-excluded-tooling.md) | Accepted, 2026-11-03 (spec revision 1) |
| D11 | [No high availability, anywhere](D11-no-high-availability.md) | Accepted, 2026-11-03 (spec revision 2) |
| D12 | [Docker Compose for everything](D12-deployment.md) | Accepted, 2026-11-03 (spec revision 2); file layout superseded by D16, 2026-11-06 |
| D13 | [One Git repo, Gradle Kotlin DSL, one self-contained build file per module](D13-build.md) | Accepted, 2026-11-03 (spec revisions 2 and 3: Gradle replaces Maven); amended 2026-11-13: multi-module root instead of a composite |
| D14 | [No shared code: contracts only, cross-cutting code copied from a template](D14-code-sharing.md) | Accepted, 2026-11-03 (spec revision 2); amended 2026-11-13: versions inline per module |
| D15 | [Layered packages under mfs.mw.<service>](D15-code-structure.md) | Accepted, 2026-11-03 (spec revision 3) |
| D16 | [One hand-written Compose file with profiles](D16-single-compose-file.md) | Accepted, 2026-11-06 (ticket 56; supersedes the per-module `compose.yml` of D12); amended 2026-11-13 by D18 |
| D17 | [A dev/test-only test console for manual testing](D17-test-console.md) | Accepted, 2026-11-07 |
| D18 | [No Makefile: `./gradlew clean build` is the gate](D18-no-makefile.md) | Accepted, 2026-11-13 (amends M13 and D16) |
| D19 | [One test task per module, no coverage gate](D19-single-test-task.md) | Accepted, 2026-11-13 (amends spec 22.1 and the ticket-53 test tiers) |
| M1 | [MVP flows](M1-mvp-flows.md) | Accepted, 2026-11-03 |
| M2 | [MVP channels](M2-mvp-channels.md) | Accepted, 2026-11-03 |
| M3 | [USSD: SMPP and HTTP both built, GP live over SMPP](M3-mvp-ussd-transport.md) | Accepted, 2026-11-03 |
| M4 | [A core mock with a real ledger](M4-mvp-core.md) | Accepted, 2026-11-03 |
| M5 | [Partners are simulators](M5-mvp-partners.md) | Accepted, 2026-11-03 |
| M6 | [Admin console: operations essentials](M6-mvp-admin-console.md) | Accepted, 2026-11-03 |
| M7 | [MVP platform components](M7-mvp-platform.md) | Accepted, 2026-11-03 |
| M8 | [Single host, Docker Compose, no HA](M8-mvp-footprint.md) | Accepted, 2026-11-03 |
| M9 | [JSON on Kafka with JSON Schema in Git](M9-mvp-serialisation.md) | Accepted, 2026-11-03 |
| M10 | [Built mainly by coding agents](M10-mvp-delivery.md) | Accepted, 2026-11-03; gate amended 2026-11-13 (D18, D19) |
| M11 | [PIN: encrypted with the core's key from day one](M11-mvp-pin.md) | Accepted, 2026-11-03 |
| M12 | [Human review of every pull request, milestone demos](M12-mvp-code-review.md) | Accepted, 2026-11-03 |
| M13 | [GitHub repo, no CI/CD: the local build is the pipeline](M13-mvp-repo-and-ci.md) | Accepted, 2026-11-03; amended 2026-11-13 by D18 |
