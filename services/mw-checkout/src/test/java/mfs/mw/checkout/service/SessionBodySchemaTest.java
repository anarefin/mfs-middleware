package mfs.mw.checkout.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import mfs.mw.checkout.dto.ErrorDetail;
import mfs.mw.checkout.exception.MwException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * The create-session body schema (formerly APISIX request-validation; checked in the service on
 * the bytes it parses, M3 gate decision), derived from the contract's {@code CreateSessionRequest}.
 */
public class SessionBodySchemaTest {

  public static final String VALID =
      """
      {"orderId":"ORD-1","amount":{"valueMinor":1000,"currency":"BDT"},
       "successUrl":"https://shop.example.test/ok","failUrl":"https://shop.example.test/fail",
       "cancelUrl":"https://shop.example.test/cancel","ipnUrl":"https://shop.example.test/ipn",
       "description":"Order 1","locale":"en"}""";

  private final SessionBodySchema schema = new SessionBodySchema(JsonMapper.builder().build());

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
    MwException e = refused(VALID.replace("\"ipnUrl\":\"https://shop.example.test/ipn\",", ""));
    assertThat(e.details()).extracting(ErrorDetail::issue).anyMatch(i -> i.contains("ipnUrl"));
  }

  @Test
  void aWrongAmountOrCurrencyOrLocaleIsRefused() {
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":\"1000\""));
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":0"));
    refused(VALID.replace("\"BDT\"", "\"USD\""));
    refused(VALID.replace("\"locale\":\"en\"", "\"locale\":\"fr\""));
    refused(VALID.replace("\"Order 1\"", "\"" + "x".repeat(141) + "\""));
  }

  /** Two parsers may keep different values of a repeated key; the edge's re-encoding used to settle it. */
  @Test
  void aDuplicateKeyIsRefused() {
    refused(VALID.replace("\"orderId\":\"ORD-1\",", "\"orderId\":\"ORD-1\",\"orderId\":\"ORD-2\","));
    refused(VALID.replace("\"valueMinor\":1000", "\"valueMinor\":1000,\"valueMinor\":1"));
  }

  @Test
  void malformedJsonOrANonObjectIsRefused() {
    refused("{\"orderId\":");
    refused("[]");
    refused("");
    refused(VALID + " {}");
  }

  @Test
  void noRequestValueIsEchoedInTheDetails() {
    MwException e = refused(VALID.replace("\"ORD-1\"", "{\"msisdn\":\"01799999999\"}"));
    assertThat(e.details()).extracting(ErrorDetail::issue).noneMatch(i -> i.contains("01799999999"));
    assertThat(e.getMessage()).doesNotContain("01799999999");
  }
}
