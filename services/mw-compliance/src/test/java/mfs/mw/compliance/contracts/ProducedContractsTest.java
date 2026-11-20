package mfs.mw.compliance.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.compliance.dto.KycCompletedEvent;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** What mw-compliance produces matches its contracts.yml {@code produces.events} (no Spring, no Docker). */
class ProducedContractsTest {

  static final String KYC = "events/mw-compliance/mw.kyc.completed.v1.schema.json";
  static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void kycCompletedAsTheServiceWritesItIsValid() {
    Schema schema = ContractSchemas.schema(KYC);
    for (KycCompletedEvent e : List.of(
        new KycCompletedEvent(1, "0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4c01", "0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4c02",
            "01712345678", "FULL", "DSO", "DSO-17", 93, "2026-11-20T10:00:00Z", "corr-1"),
        new KycCompletedEvent(1, "0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4c11", "0193a1b2-3c4d-7e5f-8a9b-0c1d2e3f4c12",
            "01812345678", "BASIC", "APP", null, 20, "2026-11-20T10:00:00.123456Z", "corr-2"))) {
      assertThat(schema.validate(JSON.writeValueAsString(e), com.networknt.schema.InputFormat.JSON)).isEmpty();
    }
  }

  @Test
  void validSamplesPassAndInvalidOnesFail() throws Exception {
    Schema schema = ContractSchemas.schema(KYC);
    Path dir = ContractSchemas.contractsDir().resolve("events/mw-compliance/samples/mw.kyc.completed.v1");
    try (var files = Files.list(dir)) {
      for (Path p : files.toList()) {
        String name = p.getFileName().toString();
        if (name.equals("expected-errors.json")) {
          continue;
        }
        var errors = schema.validate(Files.readString(p), com.networknt.schema.InputFormat.JSON);
        assertThat(errors).as(name).hasSize(name.startsWith("valid") ? 0 : 1);
      }
    }
  }
}
