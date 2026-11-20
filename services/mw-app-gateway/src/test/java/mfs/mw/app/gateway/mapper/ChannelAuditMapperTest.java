package mfs.mw.app.gateway.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import mfs.mw.app.gateway.contracts.ContractSchemas;
import mfs.mw.app.gateway.contracts.ServiceJson;
import mfs.mw.app.gateway.dto.Amount;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.AuditEvent;
import mfs.mw.app.gateway.dto.EncryptedPin;
import mfs.mw.app.gateway.dto.ErrorInfo;
import mfs.mw.app.gateway.dto.TransactionRequest;
import mfs.mw.app.gateway.dto.TransactionView;
import mfs.mw.app.gateway.dto.TxnRequest;
import org.junit.jupiter.api.Test;

/** Ticket 130b (FRS AUD-01, AUD-04): the CHANNEL_IN hop of a served transaction request. */
class ChannelAuditMapperTest {

  static final String AUDIT = "events/mw-audit-dw/mw.audit.event.v1.schema.json";
  static final String TXN = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-04T06:00:00Z");
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJ4In0.c2lnbmF0dXJl";

  final TxnRequest req = new RequestMapper().toRequest(
      new AppUser("01711000001", "CUSTOMER_APP", "CUSTOMER"), "key-p2p",
      new TransactionRequest("P2P", new TransactionRequest.CounterParty(null, "01711000002"), new Amount(12_345L, "BDT"),
          new EncryptedPin("core-pin-1", "b3BhcXVlLWNpcGhlcnRleHQ="), "en", Map.of("reference", "lunch")),
      TXN, "corr-1", NOW).withAssertion(ASSERTION);

  AuditEvent hop(String status, ErrorInfo error) {
    TransactionView answer = new TransactionView(TXN, status, "P2P", null, null, new Amount(12_345L, "BDT"), null,
        null, error, NOW, NOW);
    return ChannelAuditMapper.channelIn(new ChannelAuditMapper.Served("mw-app-gateway", "CUSTOMER_APP", TXN, "corr-1",
        "01711000001", "P2P", status, error == null ? null : error.code(), req, answer), NOW, 87);
  }

  @Test
  void aServedRequestIsAnInboundChannelHopWithItsLatencyAndBothBodiesMasked() {
    AuditEvent e = hop("COMPLETED", null);
    String json = ServiceJson.MAPPER.writeValueAsString(e);

    assertThat(ContractSchemas.schema(AUDIT).validate(ServiceJson.MAPPER.readTree(json))).as(json).isEmpty();
    assertThat(e.hop()).isEqualTo("CHANNEL_IN");
    assertThat(e.direction()).isEqualTo("IN");
    assertThat(e.action()).isEqualTo("channel.request.completed");
    assertThat(e.durationMs()).isEqualTo(87L);
    assertThat(e.outcome()).isEqualTo("SUCCESS");
    assertThat(e.resource()).isEqualTo("txn/" + TXN);
    assertThat(e.after()).containsEntry("channel", "CUSTOMER_APP").containsEntry("status", "COMPLETED");
    assertThat(e.payload()).extracting(AuditEvent.Payload::direction).containsExactly("IN", "OUT");
    assertThat(e.payload().get(0).body()).contains("P2P").contains("12345");
    assertThat(json).doesNotContain(ASSERTION).doesNotContain("01711000001").doesNotContain("01711000002")
        .doesNotContain("b3BhcXVlLWNpcGhlcnRleHQ=");
  }

  @Test
  void aRejectedAnswerIsAFailureWithItsCode() {
    AuditEvent e = hop("REJECTED", new ErrorInfo("MW-CORE-INSUFFICIENT_BALANCE", "no", null, false));

    assertThat(e.outcome()).isEqualTo("FAILURE");
    assertThat(e.after()).containsEntry("errorCode", "MW-CORE-INSUFFICIENT_BALANCE");
  }
}
