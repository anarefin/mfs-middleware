package mfs.mw.portal.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.portal.api.entity.Batch;
import mfs.mw.portal.api.entity.BatchStatus;
import mfs.mw.portal.api.entity.PendingChild;
import mfs.mw.portal.api.mapper.BatchAuditMapper;
import mfs.mw.portal.api.mapper.ChildRequestMapper;
import mfs.mw.portal.api.util.BulkIds;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code produces.events} entry of {@code contracts.yml} (ticket 53): the message built by the
 * real mapper from a fixture, serialised as {@code messaging/OutboxWriter} does (the Spring Boot
 * {@code JsonMapper}: Boot 4 with no {@code spring.jackson.*} settings is {@code findAndAddModules()}
 * on the Jackson 3 defaults), validates against its schema. An entry without a fixture fails.
 */
class ProducedContractsTest {

  /** As the Boot-built JsonMapper bean the outbox writer gets (no spring.jackson.* in application.yml). */
  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");
  static final UUID BATCH_ID = UUID.fromString("019a5b2e-1c00-7000-8000-000000000001");

  static Batch pending() {
    return new Batch(
        BATCH_ID, "ORG001", BatchStatus.PENDING_APPROVAL, 2, 275050L, "BDT", "ab".repeat(32), "sub-maker",
        "org-maker01", null, null, null, AT, null);
  }

  static Batch approved() {
    return new Batch(
        BATCH_ID, "ORG001", BatchStatus.APPROVED, 2, 275050L, "BDT", "ab".repeat(32), "sub-maker", "org-maker01",
        "sub-checker", "org-checker01", null, AT, AT.plusSeconds(60), BulkIds.parentTxnId(BATCH_ID), "01311000001",
        null);
  }

  /** Fixtures keyed by manifest entry: every message the module publishes on that topic. */
  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-audit-dw/mw.audit.event.v1.schema.json",
          () ->
              List.of(
                  BatchAuditMapper.event(pending(), "uploaded", "org-maker01", null, BatchStatus.PENDING_APPROVAL, null, "corr-1", AT),
                  BatchAuditMapper.event(approved(), "approved", "org-checker01", BatchStatus.PENDING_APPROVAL, BatchStatus.APPROVED, null, "corr-2", AT),
                  BatchAuditMapper.event(pending(), "rejected", "org-checker01", BatchStatus.PENDING_APPROVAL, BatchStatus.REJECTED, "wrong month for 01711000001", null, AT),
                  BatchAuditMapper.event(pending(), "expired", null, BatchStatus.PENDING_APPROVAL, BatchStatus.EXPIRED, null, null, AT)),
          "events/mw-orchestrator/mw.txn.request.v1.schema.json",
          () ->
              List.of(
                  ChildRequestMapper.toRequest(
                      new PendingChild(BATCH_ID, 2, "01711000001", 150000L, "SAL-NOV-001", "ORG001", "01311000001", BulkIds.parentTxnId(BATCH_ID), "BDT"),
                      AT)));

  static final List<String> ENTRIES = ContractManifest.load().producedEvents();

  @TestFactory
  Stream<DynamicTest> everyProducedEventMatchesItsSchema() {
    return ENTRIES.stream()
        .map(
            entry ->
                DynamicTest.dynamicTest(
                    entry,
                    () -> {
                      assertThat(FIXTURES).as("fixture for manifest entry %s", entry).containsKey(entry);
                      List<Object> messages = FIXTURES.get(entry).get();
                      assertThat(messages).isNotEmpty();
                      for (Object m : messages) {
                        JsonNode node = JSON.readTree(JSON.writeValueAsString(m));
                        assertThat(ContractSchemas.schema(ContractManifest.schemaPath(entry)).validate(node))
                            .as("%s: %s", entry, node)
                            .isEmpty();
                        String type = ContractManifest.messageType(entry);
                        if (type != null) {
                          assertThat(List.of(
                                  node.path("commandType").asString(""), node.path("eventType").asString(""),
                                  node.path("family").asString("")))
                              .as("message type of %s", entry)
                              .contains(type);
                        }
                      }
                    }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(ENTRIES).isNotEmpty();
    assertThat(ENTRIES).containsExactlyInAnyOrderElementsOf(FIXTURES.keySet());
  }
}
