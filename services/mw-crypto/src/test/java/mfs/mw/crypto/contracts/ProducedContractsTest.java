package mfs.mw.crypto.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.networknt.schema.Schema;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.List;
import mfs.mw.crypto.dto.ErrorEnvelope;
import mfs.mw.crypto.dto.KeyView;
import mfs.mw.crypto.dto.PublicKeyView;
import mfs.mw.crypto.entity.CryptoKey;
import mfs.mw.crypto.entity.KeyState;
import mfs.mw.crypto.mapper.KeyMapper;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The API this module serves ({@code api/mw-crypto/crypto-internal.v1}, ticket 62): every response
 * built by the real mapper from a fixture and serialised as Spring MVC does validates against the
 * contract. One fixture set per produced contract of {@code contracts.yml}; it calls no API.
 */
class ProducedContractsTest {

  static final JsonMapper JSON = JsonMapper.builder().findAndAddModules().build();
  static final String API = "api/mw-crypto/crypto-internal.v1.openapi.yaml";
  static final Instant T0 = Instant.parse("2026-11-14T08:00:00Z");
  final KeyMapper mapper = new KeyMapper();

  static JsonNode asMvc(Object body) {
    return JSON.readTree(JSON.writeValueAsString(body));
  }

  static void assertValid(Schema schema, Object body) {
    JsonNode node = asMvc(body);
    assertThat(schema.validate(node)).as("%s", node).isEmpty();
  }

  static CryptoKey key(String id, int v, String purpose, String alg, KeyState s, Instant rotated) {
    return new CryptoKey(id, v, purpose, alg, "pkcs11", id + ".v" + v, s, T0, rotated);
  }

  @Test
  void theManifestListsExactlyThisApi() {
    assertThat(ContractManifest.load().producedApis()).containsExactly(API);
  }

  @Test
  void keyListAndKeyMatchTheContract() {
    KeyView.Page page = mapper.toPage(List.of(
        key("jwt-sign", 1, "JWT_SIGN", "RSA_3072", KeyState.VERIFY_ONLY, T0.plusSeconds(60)),
        key("jwt-sign", 2, "JWT_SIGN", "RSA_3072", KeyState.ACTIVE, null),
        key("tokenise-msisdn", 1, "TOKENISE_MSISDN", "HMAC_SHA256", KeyState.ACTIVE, null)));
    assertValid(OpenApiSchemas.response(API, "/internal/v1/keys", "get", "200"), page);
    assertValid(OpenApiSchemas.response(API, "/internal/v1/keys", "post", "201"), page.items().get(1));
    assertValid(OpenApiSchemas.response(API, "/internal/v1/keys/{keyId}/rotate", "post", "200"), page.items().getFirst());
    ObjectNode noVersions = (ObjectNode) asMvc(page.items().getFirst());
    noVersions.remove("versions");
    assertThat(OpenApiSchemas.component(API, "Key").validate(noVersions)).isNotEmpty();
  }

  @Test
  void publicKeysMatchTheContract() throws Exception {
    KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
    rsa.initialize(3072);
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    ec.initialize(new ECGenParameterSpec("secp256r1"));
    Schema schema = OpenApiSchemas.response(API, "/internal/v1/keys/{keyId}/public", "get", "200");
    PublicKey r1 = rsa.generateKeyPair().getPublic();
    PublicKey r2 = rsa.generateKeyPair().getPublic();

    PublicKeyView jwt = mapper.toPublic("JWT_SIGN", "RSA_3072", "RS256", "sig",
        List.of(key("jwt-sign", 1, "JWT_SIGN", "RSA_3072", KeyState.VERIFY_ONLY, T0),
            key("jwt-sign", 2, "JWT_SIGN", "RSA_3072", KeyState.ACTIVE, null)),
        List.of(r1, r2));
    PublicKeyView pin = mapper.toPublic("PIN_TRANSPORT", "RSA_3072", "RSA-OAEP-256", "enc",
        List.of(key("pin-transport", 1, "PIN_TRANSPORT", "RSA_3072", KeyState.ACTIVE, null)), List.of(r1));
    PublicKeyView assertion = mapper.toPublic("ASSERTION_SIGN", "EC_P256", "ES256", "sig",
        List.of(key("assertion-sign", 1, "ASSERTION_SIGN", "EC_P256", KeyState.ACTIVE, null)),
        List.of(ec.generateKeyPair().getPublic()));

    assertValid(schema, jwt);
    assertValid(schema, pin);
    assertValid(schema, assertion);
    // the schema really is applied: a private member is refused
    ObjectNode withPrivate = (ObjectNode) asMvc(jwt);
    ((ObjectNode) withPrivate.get("versions").get(0).get("jwk")).put("d", "AAAA");
    assertThat(schema.validate(withPrivate)).isNotEmpty();
  }

  @Test
  void errorEnvelopeMatchesTheContract() {
    assertValid(
        OpenApiSchemas.component(API, "ErrorEnvelope"),
        new ErrorEnvelope("MW-SYS-010", "HSM unavailable: generate", null, null, "corr-1", true, List.of()));
  }
}
