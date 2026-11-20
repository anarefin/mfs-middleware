package mfs.mw.auth.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.auth.dto.BoundDevice;
import mfs.mw.auth.dto.CarePinReset;
import mfs.mw.auth.dto.PinResetView;
import mfs.mw.auth.dto.DeviceBound;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.PinKey;
import mfs.mw.auth.dto.SeedPinsResult;
import mfs.mw.auth.dto.TokenPair;
import mfs.mw.auth.dto.TxnAuthorisation;
import mfs.mw.auth.dto.ErrorDetail;
import mfs.mw.auth.dto.ErrorEnvelope;
import mfs.mw.auth.dto.LiveSession;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.SensitiveType;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The APIs this module serves ({@code produces.apis} of {@code contracts.yml}; ticket 68): the
 * response bodies of the public {@code auth.v1} device API and of the internal device lookups,
 * serialised with the service's JsonMapper (as Spring MVC writes them), validate against the
 * OpenAPI component schemas. No Spring context, no Docker.
 */
class ApiContractsTest {

  static final JsonMapper JSON = ServiceJson.MAPPER;
  static final String PUBLIC_API = "api/mw-auth/auth.v1.openapi.yaml";
  static final String INTERNAL_API = "api/mw-auth/auth-internal.v1.openapi.yaml";
  static final String CORRELATION_ID = "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d";
  static final Instant NOW = Instant.parse("2026-11-14T06:00:00Z");

  @Test
  void theManifestListsTheseApis() {
    ContractManifest manifest = ContractManifest.load();
    assertThat(manifest.producedApis()).containsExactly(PUBLIC_API, INTERNAL_API, AccountTestApiContractsTest.TEST_API);
  }

  static void assertValid(Schema schema, Object body) {
    JsonNode tree = JSON.readTree(JSON.writeValueAsString(body));
    assertThat(schema.validate(tree)).as("%s", tree).isEmpty();
  }

  @Test
  void deviceResponsesMatchThePublicContract() {
    assertValid(OpenApiSchemas.component(PUBLIC_API, "DeviceOtpSent"),
        new DeviceOtpSent(MaskingPolicy.mask(SensitiveType.MSISDN, "01711000001"), NOW.plusSeconds(120), 4));
    assertValid(OpenApiSchemas.component(PUBLIC_API, "DeviceBound"), new DeviceBound("device-1", "0171****001", NOW));
  }

  @Test
  void tokenAndKeyResponsesMatchThePublicContract() {
    assertValid(OpenApiSchemas.component(PUBLIC_API, "TokenResponse"),
        new TokenPair("eyJ.eyJ.sig", "Bearer", 300, "A".repeat(43), 2592000, "019a4f6e-0000-7a3b-8c4d-5e6f7a8b9c0d",
            300, false));
    assertValid(OpenApiSchemas.component(PUBLIC_API, "PinKey"),
        new PinKey("pin-transport.v1", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----\nMII=\n-----END PUBLIC KEY-----\n"));
    assertValid(OpenApiSchemas.component(PUBLIC_API, "JwkSet"),
        Map.of("keys", List.of(Map.of("kty", "RSA", "kid", "jwt-sign.v1", "alg", "RS256", "use", "sig", "n", "AQAB",
            "e", "AQAB"))));
    assertThat(OpenApiSchemas.component(PUBLIC_API, "JwkSet").validate(JSON.readTree(
        "{\"keys\":[{\"kty\":\"RSA\",\"kid\":\"k\",\"alg\":\"RS256\",\"use\":\"sig\",\"d\":\"secret\"}]}")))
        .as("a private member is refused").isNotEmpty();
  }

  @Test
  void theAssertionKeySetAndTheAssertionMatchTheContracts() {
    assertValid(OpenApiSchemas.component(PUBLIC_API, "EcJwkSet"),
        Map.of("keys", List.of(Map.of("kty", "EC", "kid", "assertion-sign.v1", "alg", "ES256", "use", "sig",
            "crv", "P-256", "x", "AQAB", "y", "AQAB"))));
    assertThat(OpenApiSchemas.component(PUBLIC_API, "EcJwkSet").validate(JSON.readTree(
        "{\"keys\":[{\"kty\":\"EC\",\"kid\":\"k\",\"alg\":\"ES256\",\"use\":\"sig\",\"crv\":\"P-256\",\"x\":\"A\","
            + "\"y\":\"A\",\"d\":\"secret\"}]}")))
        .as("a private member is refused").isNotEmpty();
    assertValid(OpenApiSchemas.component(INTERNAL_API, "TxnAuthorisation"),
        new TxnAuthorisation("eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln", NOW.plusSeconds(60), false));
  }

  @Test
  void lookupsMatchTheInternalContract() {
    assertValid(OpenApiSchemas.component(INTERNAL_API, "BoundDevice"),
        new BoundDevice("device-0001", "01711000001", "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE", NOW));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "LiveSession"),
        new LiveSession("01711000001", "device-0001", 300, "ab".repeat(32), NOW, 42L));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "SeedPinsResult"),
        new SeedPinsResult(List.of(new SeedPinsResult.Outcome("01711000001", "CREATED"))));
  }

  @Test
  void theChannelOtpBodiesMatchTheInternalContract() {
    assertValid(OpenApiSchemas.component(INTERNAL_API, "ChannelOtpSent"),
        new mfs.mw.auth.dto.DeviceOtpSent("0171****001", NOW.plusSeconds(120), 4));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "ChannelOtpRequest"),
        new mfs.mw.auth.dto.InternalOtpRequest(
            "01711000001", "CHECKOUT", "3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d", "en", "250.00", "Demo Shop"));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "ChannelOtpVerification"),
        new mfs.mw.auth.dto.InternalOtpVerification(
            "01711000001", "CHECKOUT", "3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d", "123456"));
  }

  @Test
  void theAtmTokenBodiesMatchTheInternalContract() {
    assertValid(OpenApiSchemas.component(INTERNAL_API, "AtmTokenRequest"),
        new mfs.mw.auth.dto.AtmTokenRequest("01711000001", "CUSTOMER_APP", "device-0001", 200000, "BDT",
            "3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d", "eyJhbGciOiJFUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln"));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "AtmTokenIssued"),
        new mfs.mw.auth.dto.AtmTokenIssued("123456", NOW.plusSeconds(600)));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "AtmTokenVerification"),
        new mfs.mw.auth.dto.AtmTokenVerification("01711000001", 200000, "BDT", "123456"));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "AtmTokenVerified"),
        new mfs.mw.auth.dto.AtmTokenVerified("01711000001"));
  }

  @Test
  void theChannelPinKeyMatchesTheInternalContract() {
    assertValid(OpenApiSchemas.component(INTERNAL_API, "ChannelPinKey"),
        mfs.mw.auth.dto.InternalPinKey.of(new mfs.mw.auth.dto.PinKey(
            "pin-transport.v2", "RSA-OAEP-SHA-256", "-----BEGIN PUBLIC KEY-----\nAA==\n-----END PUBLIC KEY-----\n")));
  }

  @Test
  void pinResetResponsesMatchTheContracts() {
    String id = "3f6b1c2d-4e5f-4a6b-9c7d-8e9f0a1b2c3d";
    assertValid(OpenApiSchemas.component(PUBLIC_API, "PinReset"),
        new PinResetView(id, "SELF", "PENDING_KYC", NOW.plusSeconds(900), null));
    assertValid(OpenApiSchemas.component(PUBLIC_API, "PinReset"),
        new PinResetView(id, "SELF", "REJECTED", NOW.plusSeconds(900), "FACTORS_MISMATCH"));
    assertValid(OpenApiSchemas.component(INTERNAL_API, "CarePinReset"),
        new CarePinReset(id, "APPROVED", NOW.plusSeconds(259200)));
  }

  @Test
  void errorEnvelopesMatchBothContracts() {
    for (String api : List.of(PUBLIC_API, INTERNAL_API)) {
      Schema schema = OpenApiSchemas.component(api, "ErrorEnvelope");
      assertValid(schema, new ErrorEnvelope("MW-VAL-001", "Invalid request", null, null, CORRELATION_ID, false,
          List.of(new ErrorDetail("otp", "must match \"[0-9]{6}\""))));
      assertValid(schema, new ErrorEnvelope("MW-AUTH-007", "No live session", null, null, CORRELATION_ID, false,
          List.of()));
    }
  }

  @Test
  void theOpenApiSchemasReallyValidate() {
    // guard against a $ref that resolves to nothing (which would accept anything)
    assertThat(OpenApiSchemas.component(INTERNAL_API, "BoundDevice").validate(JSON.readTree("{\"deviceId\":1}")))
        .isNotEmpty();
  }
}
