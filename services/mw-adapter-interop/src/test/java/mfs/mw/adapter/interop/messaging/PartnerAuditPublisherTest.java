package mfs.mw.adapter.interop.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import mfs.mw.adapter.interop.config.MwServiceProperties;
import mfs.mw.adapter.interop.dto.AuditEvent;
import mfs.mw.adapter.interop.partner.route.PartnerAudit;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Ticket 130 (FRS AUD-01): a partner call's audit event carries the hop facts of the per-hop
 * timeline (hop PARTNER_CALL, direction OUT, the call's latency) and its masked bodies as {@code
 * payload} (request OUT, response IN, each with the {@code payloadRef} of the full masked body).
 */
class PartnerAuditPublisherTest {

  private static final String REF = "partner-payload/6f1c2a3b-4d5e-4f60-8a7b-9c0d1e2f3a4b";

  private final PartnerAuditPublisher publisher =
      new PartnerAuditPublisher(
          mock(OutboxWriter.class),
          mock(PlatformTransactionManager.class),
          new MwServiceProperties("mw-adapter-interop", "0.0.1", "test"),
          Clock.fixed(Instant.parse("2026-11-03T08:15:31Z"), ZoneOffset.UTC));

  private static PartnerAudit.Entry entry(String request, String response, String ref) {
    return new PartnerAudit.Entry(
        "billagg", "billagg-1", "PAY", "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8b", "corr-1", "POST", "/pay", 200,
        "SUCCESS", 184, request, response, ref);
  }

  @Test
  void aPartnerCallIsAnOutboundHopWithItsLatencyAndBodies() {
    AuditEvent e = publisher.toEvent(entry("{\"msisdn\":\"0171****567\"}", "{\"status\":\"OK\"}", REF));

    assertThat(e.hop()).isEqualTo("PARTNER_CALL");
    assertThat(e.direction()).isEqualTo("OUT");
    assertThat(e.durationMs()).isEqualTo(184L);
    assertThat(e.payload()).hasSize(2);
    assertThat(e.payload().get(0))
        .isEqualTo(new AuditEvent.Payload("OUT", "application/json", "{\"msisdn\":\"0171****567\"}", REF));
    assertThat(e.payload().get(1))
        .isEqualTo(new AuditEvent.Payload("IN", "application/json", "{\"status\":\"OK\"}", REF));
  }

  @Test
  void aCallWithoutAnAnswerHasOnlyItsRequestAndXmlIsNamedSo() {
    AuditEvent e = publisher.toEvent(entry("<pay><msisdn>0171****567</msisdn></pay>", null, null));

    assertThat(e.payload()).containsExactly(
        new AuditEvent.Payload("OUT", "application/xml", "<pay><msisdn>0171****567</msisdn></pay>", null));
  }

  @Test
  void aCallWithoutBodiesHasNoPayload() {
    AuditEvent e = publisher.toEvent(entry(null, null, null));

    assertThat(e.payload()).isNull();
    assertThat(e.hop()).isEqualTo("PARTNER_CALL");
  }
}
