package mfs.mw.crypto.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import mfs.mw.crypto.util.MaskingPolicy;
import org.junit.jupiter.api.Test;

/**
 * Every operation request and response renders masked, by {@code toString()} and by the log
 * formatter's {@code MaskingPolicy.describe}: no ciphertext, HMAC, PIN-related value, payload, value
 * or plaintext reaches a log line even when a DTO is logged by mistake (18.3, ticket 63).
 */
class OperationDtoMaskingTest {

  static final String CT = "Q2lwaGVydGV4dE9mVGhlUGlu";
  static final String HMAC = "SG1hY09mVGhlUGluSG1hY09mVGhlUGluSG1hY09mVGg=";
  static final String MSISDN = "01712345678";

  static void masked(Object dto, String... secrets) {
    for (String rendered : List.of(dto.toString(), MaskingPolicy.describe(dto), MaskingPolicy.describe(List.of(dto)))) {
      for (String s : secrets) {
        assertThat(rendered).as(dto.getClass().getSimpleName()).doesNotContain(s);
      }
    }
  }

  @Test
  void pinRequestsAndAnswers() {
    EncryptedPin pin = new EncryptedPin("pin-transport", CT);
    masked(pin, CT);
    masked(new VerifyPinRequest(MSISDN, pin, HMAC, "pin-hmac.v1"), CT, HMAC, MSISDN);
    masked(new DerivePinRequest(MSISDN, pin, new PinPolicyInput(MSISDN, 1990, HMAC, "pin-hmac.v1")), CT, HMAC, MSISDN);
    masked(new VerifyPinResponse(true, new PinHmac(HMAC, "pin-hmac.v2")), HMAC);
    masked(DerivePinResponse.of(new PinHmac(HMAC, "pin-hmac.v2")), HMAC);
  }

  @Test
  void jwsHmacTokensAndEnvelope() {
    masked(new JwsSignRequest(null, "JWT_SIGN", null, null, Map.of("msisdn", MSISDN, "claim", "c-value-1")), MSISDN, "c-value-1");
    masked(new JwsSignResponse("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ3In0.sig", "jwt-sign.v1", "RS256"), "eyJzdWIiOiJ3In0");
    masked(new HmacSignRequest("hmac-webhook", CT), CT);
    masked(new HmacVerifyRequest("hmac-webhook", CT, HMAC), CT, HMAC);
    masked(HmacView.signed(HMAC, "hmac-webhook.v1"), HMAC);
    masked(new TokeniseRequest(TokeniseRequest.Domain.NID, List.of("1990123456789", "n-value-2")), "1990123456789", "n-value-2");
    masked(new EnvelopeEncryptRequest(null, "KEK_COLUMN", CT, null), CT);
    masked(new Envelope("kek-column.v1", HMAC, "AAAAAAAAAAAAAAAA", CT, null), CT, HMAC);
    masked(new PlaintextView(CT), CT);
  }
}
