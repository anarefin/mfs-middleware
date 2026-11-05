package mfs.mw.portal.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import mfs.mw.portal.api.dto.ErrorDetail;
import mfs.mw.portal.api.exception.MwException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The reject body schema, derived from the contract (checked in the service, never at the edge: A6). */
class BodySchemaTest {

  private final BodySchema schema = new BodySchema(JsonMapper.builder().build(), BodySchema.REJECT);

  MwException refused(String body) {
    MwException e =
        catchThrowableOfType(MwException.class, () -> schema.check(body.getBytes(StandardCharsets.UTF_8)));
    assertThat(e).as("refused: %s", body).isNotNull();
    assertThat(e.code()).isEqualTo("MW-VAL-001");
    return e;
  }

  @Test
  void aValidBodyPasses() {
    assertThatCode(() -> schema.check("{\"reason\":\"wrong month\"}".getBytes(StandardCharsets.UTF_8)))
        .doesNotThrowAnyException();
  }

  @Test
  void aMissingReasonIsNamedInTheDetails() {
    assertThat(refused("{}").details()).extracting(ErrorDetail::issue).anyMatch(i -> i.contains("reason"));
  }

  @Test
  void noRequestValueIsEchoedInTheDetails() {
    MwException e = refused("{\"reason\":{\"msisdn\":\"01799999999\"},\"x\":\"01788888888\"}");
    assertThat(e.details()).extracting(ErrorDetail::issue)
        .noneMatch(i -> i.contains("01799999999") || i.contains("01788888888"));
  }
}
