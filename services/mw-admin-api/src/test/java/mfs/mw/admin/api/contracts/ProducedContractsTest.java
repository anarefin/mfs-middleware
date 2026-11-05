package mfs.mw.admin.api.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Stream;
import mfs.mw.admin.api.dto.Operator;
import mfs.mw.admin.api.mapper.RevealAuditMapper;
import mfs.mw.admin.api.security.AdminRole;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every {@code produces.events} entry of {@code contracts.yml} (ticket 53): the message built by the
 * real mapper from a fixture, serialised as {@code messaging/OutboxWriter} does (the Boot-built
 * {@code JsonMapper}), validates against its schema. An entry without a fixture fails.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final Instant AT = Instant.parse("2026-11-05T10:00:00Z");
  static final UUID TXN = UUID.fromString("019a5b2e-1c00-7abc-8def-000000000002");

  static final Map<String, Supplier<List<Object>>> FIXTURES =
      Map.of(
          "events/mw-audit-dw/mw.audit.event.v1.schema.json",
          () ->
              List.of(
                  RevealAuditMapper.event(
                      new Operator("sub-1", "ops-compliance01", Set.of(AdminRole.COMPLIANCE_OFFICER)),
                      AdminRole.COMPLIANCE_OFFICER, TXN, "corr-1", AT),
                  RevealAuditMapper.event(
                      new Operator("sub-2", "ops-auditor01", Set.of(AdminRole.AUDITOR)), AdminRole.AUDITOR, TXN, null, AT)));

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
                      }
                    }));
  }

  @Test
  void everyFixtureIsInTheManifest() {
    assertThat(ENTRIES).isNotEmpty();
    assertThat(ENTRIES).containsExactlyInAnyOrderElementsOf(FIXTURES.keySet());
  }
}
