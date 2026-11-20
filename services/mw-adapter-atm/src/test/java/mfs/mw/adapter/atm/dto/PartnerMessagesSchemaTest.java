package mfs.mw.adapter.atm.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mfs.mw.adapter.atm.AtmFixtures;
import mfs.mw.adapter.atm.contracts.ContractSchemas;
import mfs.mw.adapter.atm.util.MaskingPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Schema test layer (spec 22.1): this service's own types for {@code mw.partner.request.v1} and
 * {@code mw.partner.reply.v1} (owned by mw-orchestrator) read every valid bank sample and write
 * JSON the contract accepts. Sensitive fields are masked in descriptions (18.3).
 */
class PartnerMessagesSchemaTest {

  static final JsonMapper JSON = JsonMapper.builder().build();

  static final String REQUEST_SCHEMA = "events/mw-orchestrator/mw.partner.request.v1.schema.json";
  static final String REPLY_SCHEMA = "events/mw-orchestrator/mw.partner.reply.v1.schema.json";

  static List<Path> requestSamples() {
    return ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.request.v1/atm", "valid");
  }

  static List<Path> replySamples() {
    return ContractSchemas.samples("events/mw-orchestrator/samples/mw.partner.reply.v1/atm", "valid");
  }

  @ParameterizedTest
  @MethodSource("requestSamples")
  void requestReadsEverySampleAndWritesValidJson(Path sample) throws Exception {
    PartnerRequest request = JSON.readValue(Files.readString(sample), PartnerRequest.class);

    assertThat(ContractSchemas.schema(REQUEST_SCHEMA).validate(JSON.valueToTree(request)))
        .as("re-serialised %s", sample)
        .isEmpty();
  }

  @ParameterizedTest
  @MethodSource("replySamples")
  void replyReadsEverySampleAndWritesValidJson(Path sample) throws Exception {
    PartnerReply reply = JSON.readValue(Files.readString(sample), PartnerReply.class);

    assertThat(ContractSchemas.schema(REPLY_SCHEMA).validate(JSON.valueToTree(reply)))
        .as("re-serialised %s", sample)
        .isEmpty();
  }

  @Test
  void samplesExist() {
    assertThat(requestSamples()).isNotEmpty();
    assertThat(replySamples()).isNotEmpty();
  }

  @Test
  void requestFieldsMarkedSensitiveAreMaskedWhenDescribed() {
    PartnerRequest request = AtmFixtures.pay(AtmFixtures.newTxnId(), AtmFixtures.ACCOUNT, 2000);

    // the settlement org id keeps its last 4; the wallet is an MSISDN. The ATM token is never a party.
    String debit = MaskingPolicy.describe(request.payload().debitParty());
    String credit = MaskingPolicy.describe(request.payload().creditParty());

    assertThat(debit).doesNotContain("ucb-atm-settlement").contains("ment");
    assertThat(credit).doesNotContain(AtmFixtures.ACCOUNT).contains("id=0191****001");
  }
}
