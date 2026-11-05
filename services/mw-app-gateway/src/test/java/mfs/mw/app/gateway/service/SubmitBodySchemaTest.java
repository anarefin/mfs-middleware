package mfs.mw.app.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.exception.MwException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The submit body schema (the edge's former request-validation, now in the gateway so the bytes it
 * hashes for the device signature are the bytes it parses; ticket 29).
 */
class SubmitBodySchemaTest {

  static final String VALID =
      """
      {"txnType":"P2P","creditParty":{"id":"01711000002"},
       "amount":{"valueMinor":1000,"currency":"BDT"},
       "encryptedPin":{"keyId":"core-pin-1","ciphertext":"Y2lwaGVy"},
       "attributes":{"reference":"r-1"}}""";

  private final BodySchema schema = new BodySchema(JsonMapper.builder().build(), BodySchema.TRANSACTION);

  MwException refused(String body) {
    MwException e =
        catchThrowableOfType(MwException.class, () -> schema.check(body.getBytes(StandardCharsets.UTF_8)));
    assertThat(e).as("refused: %s", body).isNotNull();
    assertThat(e.code()).isEqualTo("MW-VAL-001");
    return e;
  }

  @Test
  void aValidBodyPasses() {
    assertThatCode(() -> schema.check(VALID.getBytes(StandardCharsets.UTF_8))).doesNotThrowAnyException();
  }

  @Test
  void aMissingRequiredFieldIsNamedInTheDetails() {
    MwException e = refused(VALID.replace("\"amount\":{\"valueMinor\":1000,\"currency\":\"BDT\"},", ""));
    assertThat(e.details()).extracting(ErrorDetail::issue).anyMatch(i -> i.contains("amount"));
  }

  @Test
  void anAttributeOutsideTheAllowListIsRefused() {
    refused(VALID.replace("\"reference\":\"r-1\"", "\"duplicate\":\"true\""));
  }

  @Test
  void anAmountAsAStringOrWithAnotherCurrencyIsRefused() {
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":\"1000\""));
    refused(VALID.replace("\"BDT\"", "\"USD\""));
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":0"));
  }

  @Test
  void anUnknownTxnTypeIsRefused() {
    refused(VALID.replace("\"P2P\"", "\"STEAL\""));
  }

  /** What the edge's re-encoding used to neutralise: two parsers reading different values. */
  @Test
  void aDuplicateKeyIsRefused() {
    refused(VALID.replace("\"txnType\":\"P2P\",", "\"txnType\":\"P2P\",\"txnType\":\"CASH_IN\","));
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":1000,\"valueMinor\":1"));
  }

  @Test
  void malformedJsonOrANonObjectIsRefused() {
    refused("{\"txnType\":");
    refused("[]");
    refused("");
    refused(VALID + " {}");
  }

  @Test
  void noRequestValueIsEchoedInTheDetails() {
    MwException e = refused(VALID.replace("\"01711000002\"", "{\"x\":\"01799999999\"}"));
    assertThat(e.details()).extracting(ErrorDetail::issue).noneMatch(i -> i.contains("01799999999"));
    assertThat(e.getMessage()).doesNotContain("01799999999");
  }
}
