package mfs.mw.audit.dw.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.networknt.schema.Schema;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import mfs.mw.audit.dw.config.AnchorProperties;
import mfs.mw.audit.dw.config.ServiceCertificateManager;
import mfs.mw.audit.dw.contracts.OpenApiSchemas;
import mfs.mw.audit.dw.dto.AuditAnchor;
import mfs.mw.audit.dw.exception.CryptoUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The mw-crypto calls of the audit anchor (ticket 74) against a stand-in: the request is the
 * contract's {@code JwsSignRequest} for purpose {@code AUDIT_ANCHOR_SIGN}, with the service token;
 * failures are {@link CryptoUnavailableException}. No Docker.
 */
class CryptoClientTest {

  private static final String CRYPTO_API = "api/mw-crypto/crypto-internal.v1.openapi.yaml";
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final AuditAnchor ANCHOR =
      new AuditAnchor("2026-11-03", List.of(new AuditAnchor.Head("2026-11", "ab".repeat(32), 2)), "0".repeat(64));

  private final CryptoStub stub = CryptoStub.start();
  private final CryptoClient client = client(stub.baseUrl(), stub.tokenUri());

  private static CryptoClient client(String base, String token) {
    AnchorProperties props =
        new AnchorProperties(
            true, "-", "", 31,
            new AnchorProperties.Crypto(
                URI.create(base), URI.create(token), "mw-audit-dw", "test-only-client-secret",
                Duration.ofSeconds(2), Duration.ofSeconds(5)));
    return new CryptoClient(
        props,
        new ServiceTokenClient(props, JSON, Clock.systemUTC()),
        JSON,
        new StaticListableBeanFactory().getBeanProvider(ServiceCertificateManager.class));
  }

  @AfterEach
  void stop() {
    stub.close();
  }

  @Test
  void signsTheAnchorWithItsPurposeAndTheServiceToken() {
    CryptoClient.Signed signed = client.sign(ANCHOR);

    assertThat(signed.kid()).isEqualTo(CryptoStub.KID);
    assertThat(signed.jws().split("\\.")).hasSize(3);
    JsonNode request = JSON.valueToTree(stub.signRequests.get(0));
    Schema schema = OpenApiSchemas.component(CRYPTO_API, "JwsSignRequest");
    assertThat(schema.validate(request)).as("%s", request).isEmpty();
    assertThat(request.path("purpose").asString()).isEqualTo("AUDIT_ANCHOR_SIGN");
    assertThat(request.path("header").path("typ").asString()).isEqualTo(CryptoClient.ANCHOR_TYPE);
    assertThat(request.path("payload").path("heads").get(0).path("rowCount").asLong()).isEqualTo(2);
    // client credentials as HTTP Basic, the token fetched once
    assertThat(stub.tokenAuth).hasSize(1).allSatisfy(a -> assertThat(a).startsWith("Basic "));

    assertThat(client.jwks().getKeyByKeyId(CryptoStub.KID)).isNotNull();
    assertThat(stub.tokenAuth).hasSize(1);
  }

  @Test
  void anUnavailableMwCryptoIsACryptoUnavailableException() {
    stub.signStatus(503);
    assertThatThrownBy(() -> client.sign(ANCHOR))
        .isInstanceOf(CryptoUnavailableException.class)
        .hasMessage("mw-crypto sign answered 503");

    CryptoClient unreachable = client("http://127.0.0.1:1", stub.tokenUri());
    assertThatThrownBy(() -> unreachable.sign(ANCHOR)).isInstanceOf(CryptoUnavailableException.class);
  }
}
