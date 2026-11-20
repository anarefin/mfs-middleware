package mfs.mw.ussd.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.ussd.contracts.ServiceJson;
import mfs.mw.ussd.dto.AuditEvent;
import mfs.mw.ussd.dto.EncryptedPin;
import mfs.mw.ussd.dto.FlowRequest;
import mfs.mw.ussd.dto.TxnRequest;
import mfs.mw.ussd.dto.UssdSession;
import org.junit.jupiter.api.Test;

/** Ticket 130b (FRS AUD-01, AUD-04): the CHANNEL_IN hop of a USSD transaction request. */
class ChannelAuditMapperTest {

  static final Instant NOW = Instant.parse("2026-11-04T10:00:00Z");
  static final String TXN = "019a4f6e-1c2d-7a3b-8c4d-5e6f7a8b9c0d";
  static final String ASSERTION = "eyJhbGciOiJFUzI1NiJ9.eyJqdGkiOiJ0In0.c2lnbmF0dXJl";

  @Test
  void theRequestIsMaskedAndTheHopIsInbound() {
    UssdSession s = new UssdSession("robi", "sess-1", "01712345678", "main", 2, "en", "p2p.send",
        List.of("main:1", "p2p.pin"), Map.of(), 0, List.of(), false, NOW);
    TxnRequest req = new TxnRequestMapper()
        .toRequest(new FlowRequest("P2P", s, "main:1", Map.of("creditParty", "01812345678", "amount", "500.00"),
            new EncryptedPin("PIN_TRANSPORT.v1", "Y2lwaGVy")), TXN, "k".repeat(64), "corr-1", NOW)
        .withAssertion(ASSERTION);

    AuditEvent e = ChannelAuditMapper.channelIn(new ChannelAuditMapper.Served("mw-ussd", "USSD", TXN, "corr-1",
        "01712345678", "P2P", "COMPLETED", null, req, null), NOW, 30);

    assertThat(e.hop()).isEqualTo("CHANNEL_IN");
    assertThat(e.direction()).isEqualTo("IN");
    assertThat(e.payload()).singleElement().satisfies(p -> assertThat(p.body()).contains("P2P").contains("50000"));
    assertThat(ServiceJson.MAPPER.writeValueAsString(e))
        .doesNotContain(ASSERTION).doesNotContain("01712345678").doesNotContain("01812345678");
  }
}
