package mfs.mw.crypto.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import mfs.mw.crypto.dto.DerivePinRequest;
import mfs.mw.crypto.dto.DerivePinResponse;
import mfs.mw.crypto.dto.Envelope;
import mfs.mw.crypto.dto.EnvelopeEncryptRequest;
import mfs.mw.crypto.dto.HmacSignRequest;
import mfs.mw.crypto.dto.HmacVerifyRequest;
import mfs.mw.crypto.dto.HmacView;
import mfs.mw.crypto.dto.JwsSignRequest;
import mfs.mw.crypto.dto.JwsSignResponse;
import mfs.mw.crypto.dto.PinHmac;
import mfs.mw.crypto.dto.PlaintextView;
import mfs.mw.crypto.dto.TokeniseRequest;
import mfs.mw.crypto.dto.TokensView;
import mfs.mw.crypto.dto.VerifyPinRequest;
import mfs.mw.crypto.dto.VerifyPinResponse;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The operation endpoints of {@code api/mw-crypto/crypto-internal.v1} (ticket 63): every response DTO,
 * serialised as Spring MVC does, validates against the contract's response schema; every request
 * fixture (what mw-auth and the other callers send) validates against the request schema, reads into
 * the request DTO and passes its Bean Validation; a few invalid requests fail both.
 */
class OperationContractsTest {

  static final JsonMapper JSON = ProducedContractsTest.JSON;
  static final String API = ProducedContractsTest.API;
  static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
  static final String CT = "A".repeat(510) + "==";
  static final String HMAC = "A".repeat(43) + "=";
  static final String TOKEN = "MSISDN_" + "A".repeat(52);

  static Schema request(String path) {
    return OpenApiSchemas.pointer(API, "/paths/" + path.replace("/", "~1") + "/post/requestBody/content/application~1json/schema");
  }

  static Schema response(String path, String method) {
    return OpenApiSchemas.response(API, path, method, "200");
  }

  static void validResponse(String path, Object body) {
    ProducedContractsTest.assertValid(response(path, "post"), body);
  }

  /** The fixture validates against the request schema, reads into {@code type} and passes Bean Validation. */
  static <T> void validRequest(String path, Class<T> type, String json) {
    JsonNode node = JSON.readTree(json);
    assertThat(request(path).validate(node)).as(json).isEmpty();
    assertThat(VALIDATOR.validate(JSON.readValue(json, type))).as(json).isEmpty();
  }

  static <T> void invalidRequest(String path, Class<T> type, String json) {
    assertThat(request(path).validate(JSON.readTree(json))).as(json).isNotEmpty();
    assertThat(VALIDATOR.validate(JSON.readValue(json, type))).as(json).isNotEmpty();
  }

  static String pin(String ct) {
    return "{\"keyId\":\"pin-transport\",\"ciphertext\":\"" + ct + "\"}";
  }

  @Test
  void pinVerifyAndDerive() {
    String verify = "/internal/v1/pin/verify";
    validRequest(verify, VerifyPinRequest.class, "{\"walletId\":\"01712345678\",\"encryptedPin\":" + pin(CT)
        + ",\"storedHmac\":\"" + HMAC + "\",\"hmacKeyId\":\"pin-hmac.v1\"}");
    invalidRequest(verify, VerifyPinRequest.class, "{\"walletId\":\"01712345678\",\"encryptedPin\":" + pin(CT)
        + ",\"storedHmac\":\"c2hvcnQ=\",\"hmacKeyId\":\"pin-hmac.v1\"}");
    validResponse(verify, new VerifyPinResponse(false, null));
    validResponse(verify, new VerifyPinResponse(true, new PinHmac(HMAC, "pin-hmac.v2")));

    String derive = "/internal/v1/pin/derive";
    validRequest(derive, DerivePinRequest.class, "{\"walletId\":\"01712345678\",\"encryptedPin\":" + pin(CT) + "}");
    validRequest(derive, DerivePinRequest.class, "{\"walletId\":\"01712345678\",\"encryptedPin\":" + pin(CT)
        + ",\"policyInput\":{\"msisdn\":\"01712345678\",\"birthYear\":1990,\"lastHmac\":\"" + HMAC
        + "\",\"lastHmacKeyId\":\"pin-hmac.v1\"}}");
    invalidRequest(derive, DerivePinRequest.class, "{\"walletId\":\"01712345678\",\"encryptedPin\":" + pin("not base64!") + "}");
    validResponse(derive, DerivePinResponse.of(new PinHmac(HMAC, "pin-hmac.v1")));
    validResponse(derive, DerivePinResponse.weak("SEQUENCE"));
    assertThat(response(derive, "post").validate(JSON.readTree("{\"weak\":true,\"rule\":\"SEQUENCE\",\"hmac\":\"" + HMAC + "\"}")))
        .isNotEmpty();
  }

  @Test
  void jwsAndJwks() {
    String sign = "/internal/v1/jws/sign";
    validRequest(sign, JwsSignRequest.class, "{\"purpose\":\"JWT_SIGN\",\"header\":{\"typ\":\"at+jwt\"},\"payload\":{\"sub\":\"w-1\"}}");
    validRequest(sign, JwsSignRequest.class, "{\"keyId\":\"assertion-sign\",\"alg\":\"ES256\",\"payload\":{}}");
    invalidRequest(sign, JwsSignRequest.class, "{\"purpose\":\"JWT_SIGN\"}");
    assertThat(request(sign).validate(JSON.readTree("{\"purpose\":\"JWT_SIGN\",\"keyId\":\"jwt-sign\",\"payload\":{}}")))
        .isNotEmpty();
    validResponse(sign, new JwsSignResponse("eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ3LTEifQ.c2ln", "jwt-sign.v1", "RS256"));
    ProducedContractsTest.assertValid(response("/internal/v1/jwks/{purpose}", "get"),
        Map.of("keys", List.of(Map.of("kty", "EC", "kid", "assertion-sign.v1", "alg", "ES256", "use", "sig",
            "crv", "P-256", "x", "AA", "y", "AA"))));
  }

  @Test
  void hmacTokensAndEnvelope() {
    validRequest("/internal/v1/hmac/sign", HmacSignRequest.class, "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\"}");
    validRequest("/internal/v1/hmac/verify", HmacVerifyRequest.class,
        "{\"keyId\":\"hmac-webhook\",\"data\":\"AAAA\",\"mac\":\"" + HMAC + "\"}");
    validResponse("/internal/v1/hmac/sign", HmacView.signed(HMAC, "hmac-webhook.v1"));
    validResponse("/internal/v1/hmac/verify", HmacView.verified(true, "hmac-webhook.v1"));
    validResponse("/internal/v1/hmac/verify", HmacView.verified(false, null));

    validRequest("/internal/v1/tokens", TokeniseRequest.class, "{\"domain\":\"NID\",\"values\":[\"1990123456789\"]}");
    invalidRequest("/internal/v1/tokens", TokeniseRequest.class, "{\"domain\":\"NID\",\"values\":[]}");
    validResponse("/internal/v1/tokens", new TokensView("MSISDN", "tokenise-msisdn.v1", List.of(TOKEN)));

    validRequest("/internal/v1/envelope/encrypt", EnvelopeEncryptRequest.class,
        "{\"purpose\":\"KEK_COLUMN\",\"plaintext\":\"AAAA\",\"aad\":\"AAAA\"}");
    Envelope e = new Envelope("kek-column.v1", "AAAA", "AAAAAAAAAAAAAAAA", "AAAA", null);
    validResponse("/internal/v1/envelope/encrypt", e);
    validRequest("/internal/v1/envelope/decrypt", Envelope.class, JSON.writeValueAsString(e));
    invalidRequest("/internal/v1/envelope/decrypt", Envelope.class,
        "{\"kekId\":\"kek-column.v1\",\"wrappedKey\":\"AAAA\",\"iv\":\"AAAA\",\"ciphertext\":\"AAAA\"}");
    validResponse("/internal/v1/envelope/decrypt", new PlaintextView("AAAA"));
  }
}
