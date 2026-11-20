package mfs.mw.adapter.card.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.card.contracts.ContractSchemas;
import mfs.mw.adapter.card.dto.PartnerInbound;
import mfs.mw.adapter.card.partner.InboundResult;
import mfs.mw.adapter.card.partner.PartnerTestSeeds;
import mfs.mw.adapter.card.partner.config.PartnerConfig;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** 3-D Secure webhook to {@code mw.partner.inbound.v1} (ticket 118b), checked against the owner's contract. */
class PartnerInboundMapperTest {

  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String REF = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
  static final Instant NOW = Instant.parse("2026-11-20T08:15:40Z");

  final JsonMapper json = JsonMapper.builder().build();
  final PartnerInboundMapper mapper = new PartnerInboundMapper();
  final PartnerConfig cybersource = PartnerTestSeeds.seed("cybersource");

  PartnerInbound map(InboundResult r) {
    PartnerInbound in = mapper.toInbound(r, cybersource, "cybersource", TXN, "corr-1", NOW);
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(in.toString()).isEmpty();
    return in;
  }

  @Test
  void anAuthenticatedSessionCarriesTheSessionAndTokenForTheCapture() {
    PartnerInbound in = map(new InboundResult("sess-5", REF, "SUCCESS", "100", null,
        Map.of("sessionId", "sess-5", "transientToken", "tok-fake-5")));

    assertThat(in.family()).isEqualTo("card");
    assertThat(in.partnerRef()).isEqualTo("sess-5");
    assertThat(in.payload().kind()).isEqualTo("CALLBACK");
    assertThat(in.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(in.payload().errorCode()).isNull();
    assertThat(in.payload().attributes())
        .containsEntry("sessionId", "sess-5")
        .containsEntry("transientToken", "tok-fake-5");
    assertThat(in.inboundId()).isEqualTo(PartnerInboundMapper.inboundId("cybersource", REF, "SUCCESS"));
  }

  @Test
  void aThreeDsFailureGetsTheMappedCode() {
    PartnerInbound failed = map(new InboundResult("sess-6", REF, "FAILED", "4002", null, Map.of("sessionId", "sess-6")));
    PartnerInbound unlisted = map(new InboundResult(null, REF, "FAILED", "4999", null, Map.of()));

    assertThat(failed.payload().errorCode()).isEqualTo("MW-PTR-CYBERSOURCE-THREE_DS_FAILED");
    assertThat(unlisted.payload().errorCode()).isEqualTo("MW-PTR-CYBERSOURCE-UNMAPPED");
    assertThat(unlisted.partnerRef()).isEqualTo(REF);
  }

  @Test
  void anAmbiguousCodeIsNotDefiniteAndTravelsAsPending() {
    PartnerInbound in = map(new InboundResult(null, REF, "FAILED", "9876", "?", Map.of()));

    assertThat(in.payload().outcome()).isEqualTo("PENDING");
    assertThat(in.payload().errorCode()).isNull();
  }

  /** PLT-014: the same webhook (same partner, our reference, outcome) has one id, so it is processed once. */
  @Test
  void theSameWebhookHasTheSameIdAndAnotherOutcomeAnother() {
    String success = PartnerInboundMapper.inboundId("cybersource", REF, "SUCCESS");

    assertThat(PartnerInboundMapper.inboundId("cybersource", REF, "SUCCESS")).isEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("cybersource", REF, "FAILED")).isNotEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("other", REF, "SUCCESS")).isNotEqualTo(success);
  }

  @Test
  void theCardSamplesReadIntoTheOwnTypeAndBackUnchanged() throws Exception {
    int read = 0;
    for (Path p : ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.inbound.v1/card", "valid")) {
      PartnerInbound in = json.readValue(Files.readString(p), PartnerInbound.class);
      assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(p.toString()).isEmpty();
      read++;
    }
    assertThat(read).isEqualTo(2);
  }
}
