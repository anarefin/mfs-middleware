package mfs.mw.audit.dw.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.audit.dw.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** This service's own type for {@code mw.audit.event.v1} (owned here) against its contract. */
class AuditEventSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String SCHEMA = "events/mw-audit-dw/mw.audit.event.v1.schema.json";

  static final String SAMPLES = "events/mw-audit-dw/samples/mw.audit.event.v1";

  static List<Path> validSamples() {
    return ContractSchemas.samples(SAMPLES, "valid");
  }

  @Test
  void thereAreSamples() {
    assertThat(validSamples()).isNotEmpty();
  }

  @ParameterizedTest
  @MethodSource("validSamples")
  void readsEverySampleAndWritesValidJson(Path sample) throws Exception {
    AuditEvent event = JSON.readValue(Files.readString(sample), AuditEvent.class);

    assertThat(event.auditId()).isNotNull();
    assertThat(event.actor().type()).isNotBlank();
    assertThat(ContractSchemas.schema(SCHEMA).validate(JSON.valueToTree(event)))
        .as("re-serialised %s", sample)
        .isEmpty();
  }

  @Test
  void keepsBeforeAndAfterAsJson() throws Exception {
    AuditEvent event =
        JSON.readValue(
            Files.readString(ContractSchemas.contractsDir().resolve(SAMPLES + "/valid-txn-state-changed.json")),
            AuditEvent.class);

    assertThat(event.before().path("status").asString()).isEqualTo("POSTING");
    assertThat(event.after().path("status").asString()).isEqualTo("COMPLETED");
    assertThat(event.txnId()).isEqualTo("01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b");
  }

  @Test
  void unknownFieldsAreIgnored() {
    AuditEvent event =
        JSON.readValue(
            """
            {"schemaVersion":1,"auditId":"9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d","correlationId":"c",
             "actor":{"type":"system","id":"mw-x"},"action":"a.b","resource":"r","outcome":"SUCCESS",
             "at":"2026-11-03T08:15:31Z","service":"mw-x","addedLater":1}
            """,
            AuditEvent.class);

    assertThat(event.service()).isEqualTo("mw-x");
  }
}
