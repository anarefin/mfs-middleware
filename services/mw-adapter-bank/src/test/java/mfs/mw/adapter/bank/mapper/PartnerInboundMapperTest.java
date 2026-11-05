package mfs.mw.adapter.bank.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.bank.contracts.ContractSchemas;
import mfs.mw.adapter.bank.dto.PartnerInbound;
import mfs.mw.adapter.bank.partner.InboundResult;
import mfs.mw.adapter.bank.partner.config.PartnerConfig;
import mfs.mw.adapter.bank.partner.template.PartnerFrameworkSeeds;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Callback to {@code mw.partner.inbound.v1} (ticket 22), checked against the owner's contract. */
class PartnerInboundMapperTest {

  static final String SCHEMA = "events/mw-orchestrator/mw.partner.inbound.v1.schema.json";
  static final String TXN = "01933b8e-7c1a-7b2e-9f3d-2a4c5e6f7a8c";
  static final String REF = "UPAY01933B8E7C1A7B2E9F3D2A4C5E6F7A8C";
  static final Instant NOW = Instant.parse("2026-11-03T08:15:40Z");

  final JsonMapper json = JsonMapper.builder().build();
  final PartnerInboundMapper mapper = new PartnerInboundMapper();
  final PartnerConfig c2wbank = PartnerFrameworkSeeds.c2wbank();

  PartnerInbound map(InboundResult r) {
    PartnerInbound in = mapper.toInbound(r, c2wbank, "c2wbank", TXN, "corr-1", NOW);
    assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(in.toString()).isEmpty();
    return in;
  }

  @Test
  void aSuccessKeepsTheBankReferenceAndMessage() {
    PartnerInbound in = map(new InboundResult("C2W-5", REF, "SUCCESS", "000", "Account debited", Map.of()));

    assertThat(in.family()).isEqualTo("bank");
    assertThat(in.partnerRef()).isEqualTo("C2W-5");
    assertThat(in.payload().kind()).isEqualTo("CALLBACK");
    assertThat(in.payload().outcome()).isEqualTo("SUCCESS");
    assertThat(in.payload().errorCode()).isNull();
    assertThat(in.payload().attributes()).containsEntry("message", "Account debited");
    assertThat(in.inboundId()).isEqualTo(PartnerInboundMapper.inboundId("c2wbank", REF, "SUCCESS"));
  }

  @Test
  void aFailureGetsTheMappedCodeAndFallsBackToOurReference() {
    PartnerInbound refused = map(new InboundResult(null, REF, "FAILED", "4002", null, Map.of()));
    PartnerInbound unlisted = map(new InboundResult(null, REF, "FAILED", "4999", null, Map.of()));

    assertThat(refused.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-DEBIT_REFUSED");
    assertThat(refused.partnerRef()).isEqualTo(REF);
    assertThat(refused.payload().attributes()).isNull();
    assertThat(unlisted.payload().errorCode()).isEqualTo("MW-PTR-C2WBANK-UNMAPPED");
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
    String success = PartnerInboundMapper.inboundId("c2wbank", REF, "SUCCESS");

    assertThat(PartnerInboundMapper.inboundId("c2wbank", REF, "SUCCESS")).isEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("c2wbank", REF, "PENDING")).isNotEqualTo(success);
    assertThat(PartnerInboundMapper.inboundId("other", REF, "SUCCESS")).isNotEqualTo(success);
  }

  @Test
  void theBankCallbackSampleReadsIntoTheOwnTypeAndBackUnchanged() throws Exception {
    int read = 0;
    for (Path p : ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.inbound.v1/bank", "valid-c2w-callback")) {
      PartnerInbound in = json.readValue(Files.readString(p), PartnerInbound.class);
      assertThat(ContractSchemas.schema(SCHEMA).validate(json.valueToTree(in))).as(p.toString()).isEmpty();
      read++;
    }
    assertThat(read).isPositive();
  }
}
