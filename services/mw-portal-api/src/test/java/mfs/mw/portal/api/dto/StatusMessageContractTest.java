package mfs.mw.portal.api.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.portal.api.contracts.ContractSchemas;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/** Every valid {@code mw.txn.status.v1} sample of the owner reads into this service's own type. */
class StatusMessageContractTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();

  static List<Path> samples() {
    return ContractSchemas.samples("events/mw-orchestrator/samples/mw.txn.status.v1", "valid");
  }

  @ParameterizedTest
  @MethodSource("samples")
  void readsEveryValidStatusSample(Path sample) throws Exception {
    StatusMessage s = JSON.readValue(Files.readString(sample), StatusMessage.class);
    assertThat(s.txnId()).isNotBlank();
    assertThat(s.status()).isNotBlank();
    assertThat(s.channel()).isNotBlank();
    if (s.status().equals("REJECTED")) {
      assertThat(s.error().code()).startsWith("MW-");
    }
  }
}
