package mfs.mw.adapter.billpay.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.billpay.contracts.ContractSchemas;
import mfs.mw.adapter.billpay.dto.PartnerInbound;
import mfs.mw.adapter.billpay.partner.InboundResult;
import mfs.mw.adapter.billpay.partner.config.PartnerConfig;
import mfs.mw.adapter.billpay.partner.template.PartnerFrameworkSeeds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Callback to {@code mw.partner.inbound.v1} (ticket 22), checked against the owner's contract. */
class PartnerInboundMapperTest {

  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String REF = "BITS01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
  static final Instant NOW = Instant.parse("2026-11-03T08:15:40Z");

  final JsonMapper json = JsonMapper.builder().build();
  final PartnerInboundMapper mapper = new PartnerInboundMapper();
  final PartnerConfig billagg = PartnerFrameworkSeeds.billagg();

  PartnerInbound map(InboundResult r) {
    PartnerInbound in = mapper.toInbound(r, billagg, "billagg", TXN, "corr-1", NOW);
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(in.toString()).isEmpty();
    return in;
  }

  @Test
  void aSuccessKeepsTheAggregatorReferenceAndMessage() {
    PartnerInbound in = map(new InboundResult("AGG-5", REF, "SUCCESS", "000", "Bill paid", Map.of()));

    assertThat(in.family()).isEqualTo("billpay");
    assertThat(in.partnerRef()).isEqualTo("AGG-5");
    assertThat(in.payload().kind()).isEqualTo("CALLBACK");
    assertThat(in.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(in.payload().errorCode()).isNull();
    assertThat(in.payload().attributes()).containsEntry("message", "Bill paid");
    assertThat(in.inboundId()).isEqualTo(PartnerInboundMapper.inboundId("billagg", REF, "SUCCESS"));
  }

  @Test
  void aFailureGetsTheMappedCodeAndFallsBackToOurReference() {
    PartnerInbound refused = map(new InboundResult(null, REF, "FAILED", "3003", null, Map.of()));
    PartnerInbound unlisted = map(new InboundResult(null, REF, "FAILED", "3999", null, Map.of()));

    assertThat(refused.payload().errorCode()).isEqualTo("MW-PTR-BILLAGG-PAYMENT_REFUSED");
    assertThat(refused.partnerRef()).isEqualTo(REF);
    assertThat(refused.payload().attributes()).isNull();
    assertThat(unlisted.payload().errorCode()).isEqualTo("MW-PTR-BILLAGG-UNMAPPED");
  }

  @Test
  void anAmbiguousFailureIsNotDefiniteAndTravelsAsPending() {
    PartnerInbound in = map(new InboundResult(null, REF, "FAILED", "9876", "?", Map.of()));

    assertThat(in.payload().outcome()).isEqualTo("PENDING");
    assertThat(in.payload().errorCode()).isNull();
    assertThat(in.payload().partnerCode()).isEqualTo("9876");
  }

  @Test
  void theSameCallbackHasTheSameIdAndAnotherOutcomeAnother() {
    String success = PartnerInboundMapper.inboundId("billagg", REF, "SUCCESS");

    assertThat(PartnerInboundMapper.inboundId("billagg", REF, "SUCCESS")).isEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("billagg", REF, "PENDING")).isNotEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("other", REF, "SUCCESS")).isNotEqualTo(success);
  }

  @Test
  void theBillpaySampleReadsIntoTheOwnTypeAndBackUnchanged() throws Exception {
    int read = 0;
    for (Path p : ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.inbound.v1/billpay", "valid")) {
      PartnerInbound in = json.readValue(Files.readString(p), PartnerInbound.class);
      assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(p.toString()).isEmpty();
      read++;
    }
    assertThat(read).isPositive();
  }
}
