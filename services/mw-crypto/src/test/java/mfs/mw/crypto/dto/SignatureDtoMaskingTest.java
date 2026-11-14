package mfs.mw.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import mfs.mw.crypto.util.MaskingPolicy;
import org.junit.jupiter.api.Test;

/**
 * The XML-DSig and HTTP signature DTOs (ticket 65) render masked: no document and no component value
 * reaches a log line even when a DTO is logged by mistake (18.3).
 */
class SignatureDtoMaskingTest {

  static final String XML = "PERvY3VtZW50PjxBbXQ+MTUwMDwvQW10PjwvRG9jdW1lbnQ+";
  static final String VALUE = "sha-256=:secret-digest-value:";

  static void masked(Object dto, String... secrets) {
    for (String rendered : List.of(dto.toString(), MaskingPolicy.describe(dto), MaskingPolicy.describe(List.of(dto)))) {
      for (String s : secrets) {
        assertThat(rendered).as(dto.getClass().getSimpleName()).doesNotContain(s);
      }
    }
  }

  @Test
  void xmlDsig() {
    masked(new XmlDsigSignRequest("xmldsig", XML), XML);
    masked(new XmlDsigSignResponse(XML, "xmldsig.v1"), XML);
    masked(new XmlDsigVerifyRequest(XML, "xmldsig", null), XML);
  }

  @Test
  void httpSignatures() {
    HttpComponent c = new HttpComponent("content-digest", null, VALUE);
    masked(c, VALUE);
    masked(new HttpSignatureSignRequest("http-sign-ec", null, null, List.of(c), null, null, null, null), VALUE);
    masked(new HttpSignatureVerifyRequest("http-sign-ec", null, null, null, "sig1=(\"content-digest\")", "sig1=:AAAA:",
        List.of(c), null, null, null), VALUE);
  }
}
