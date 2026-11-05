package mfs.mw.notify.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import mfs.mw.notify.contracts.ContractSchemas;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema test layer (spec 22.1) for the contracts this service owns ({@code mw.notify.request.v1},
 * {@code mw.notify.status.v1}) and the one it reads ({@code mw.txn.status.v1}).
 */
class NotifyContractsSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String REQUEST = "events/mw-notify/mw.notify.request.v1.schema.json";
  static final String STATUS = "events/mw-notify/mw.notify.status.v1.schema.json";

  static List<Path> requestSamples() {
    return ContractSchemas.samples("events/mw-notify/samples/mw.notify.request.v1", "valid");
  }

  static List<Path> txnStatusSamples() {
    return ContractSchemas.samples("events/mw-orchestrator/samples/mw.txn.status.v1", "valid");
  }

  @ParameterizedTest
  @MethodSource("requestSamples")
  void readsEveryRequestSampleAndWritesValidJson(Path sample) throws Exception {
    NotificationRequest r = JSON.readValue(Files.readString(sample), NotificationRequest.class);

    assertThat(r.messageId()).isNotBlank();
    assertThat(ContractSchemas.schema(REQUEST).validate(JSON.valueToTree(r))).isEmpty();
  }

  @ParameterizedTest
  @MethodSource("txnStatusSamples")
  void readsEveryTxnStatusSample(Path sample) throws Exception {
    TxnStatusEvent e = JSON.readValue(Files.readString(sample), TxnStatusEvent.class);

    assertThat(e.txnId()).isNotBlank();
    assertThat(e.status()).isNotBlank();
  }

  @Test
  void statusEventsTheServiceBuildsMatchTheContract() {
    Instant at = Instant.parse("2026-11-03T08:15:35Z");
    List<NotifyStatusEvent> events =
        List.of(
            new NotifyStatusEvent(1, "m-1", "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b", "corr-1", Channel.SMS,
                NotifyStatus.DELIVERED, true, 1, "TXN_P2P_SENT", "smsc-1", null, at),
            new NotifyStatusEvent(1, "otp-1", null, null, Channel.SMS, NotifyStatus.FAILED, false, 2,
                "OTP_LOGIN", null, "MW-SYS-102", at),
            new NotifyStatusEvent(1, "otp-2", null, "c", Channel.SMS, NotifyStatus.EXPIRED, true, 1,
                "OTP_LOGIN", null, null, at));

    for (NotifyStatusEvent e : events) {
      assertThat(ContractSchemas.schema(STATUS).validate(JSON.valueToTree(e))).as(e.toString()).isEmpty();
    }
    assertThat(JSON.valueToTree(events.getFirst()).get("final").asBoolean()).isTrue();
    assertThat(JSON.valueToTree(events.getFirst()).get("at").asString()).isEqualTo("2026-11-03T08:15:35Z");
  }
}
