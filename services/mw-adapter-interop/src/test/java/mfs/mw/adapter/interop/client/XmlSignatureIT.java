package mfs.mw.adapter.interop.client;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import mfs.mw.adapter.interop.config.CryptoClientProperties;
import mfs.mw.adapter.interop.exception.MwException;
import mfs.mw.adapter.interop.mapping.Iso20022Mapper;
import mfs.mw.adapter.interop.mapping.Iso20022Schemas;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ticket 121b: outbound NPSB messages are signed through mw-crypto {@code /internal/v1/xmldsig/sign}
 * (stubbed here), inbound ones verified through {@code /verify} under the switch's certificate; a
 * tampered message ({@code valid: false}) is refused {@code MW-PTR-NPSB-SIGNATURE}. No key material
 * in the adapter.
 */
class XmlSignatureIT {

  private static final String PEM = "-----BEGIN CERTIFICATE-----\nMIIBfake\n-----END CERTIFICATE-----";
  private static WireMockServer crypto;

  private final JsonMapper json = JsonMapper.builder().build();
  private CryptoXmlSigner signer;

  @BeforeAll
  static void start() {
    crypto = new WireMockServer(options().dynamicPort());
    crypto.start();
  }

  @AfterAll
  static void stop() {
    crypto.stop();
  }

  @BeforeEach
  void setUp() {
    crypto.resetAll();
    crypto.stubFor(post(urlEqualTo("/token")).willReturn(aResponse().withStatus(200)
        .withHeader("Content-Type", "application/json")
        .withBody("{\"access_token\":\"fake-token\",\"expires_in\":300}")));
    CryptoClientProperties props = new CryptoClientProperties(
        true, crypto.baseUrl(), crypto.baseUrl() + "/token", "mw-adapter-interop", "fake-secret", "xmldsig",
        PEM, null, Duration.ofSeconds(2), Duration.ofSeconds(2));
    ServiceTokenClient tokens = new ServiceTokenClient(props, json, Clock.systemUTC());
    signer = new CryptoXmlSigner(props, tokens, json, null);
  }

  @Test
  void anOutboundPacs008IsSignedThroughMwCrypto() throws Exception {
    String pacs008 = new Iso20022Mapper(new Iso20022Schemas()).pacs008(mfs.mw.adapter.interop.mapping.IsoSamples.transfer());
    String signed = pacs008.replace("</Document>", "<ds:Signature xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\"/></Document>");
    crypto.stubFor(post(urlEqualTo("/internal/v1/xmldsig/sign"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(json.writeValueAsString(Map.of("xml", b64(signed), "kid", "xmldsig.v1")))));

    assertThat(signer.sign(pacs008)).isEqualTo(signed);

    crypto.verify(postRequestedFor(urlEqualTo("/internal/v1/xmldsig/sign"))
        .withHeader("Authorization", equalTo("Bearer fake-token"))
        .withRequestBody(equalToJson(json.writeValueAsString(Map.of("keyId", "xmldsig", "xml", b64(pacs008))))));
  }

  /** mw-crypto's XmlDocument is the UTF-8 document in standard base64 (contract). */
  private static String b64(String xml) {
    return java.util.Base64.getEncoder().encodeToString(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  @Test
  void aValidInboundSignatureIsAccepted() {
    crypto.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify"))
        .withRequestBody(matchingJsonPath("$.certificatePem", equalTo(PEM)))
        .withRequestBody(matchingJsonPath("$.xml", equalTo(b64("<Document/>"))))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"valid\":true}")));

    signer.verifyFromSwitch("<Document/>");
  }

  @Test
  void aTamperedInboundMessageIsRejected() {
    crypto.stubFor(post(urlEqualTo("/internal/v1/xmldsig/verify"))
        .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody("{\"valid\":false,\"reason\":\"DIGEST_MISMATCH\"}")));

    assertThatThrownBy(() -> signer.verifyFromSwitch("<Document/>"))
        .isInstanceOf(MwException.class)
        .extracting("code")
        .isEqualTo("MW-PTR-NPSB-SIGNATURE");
  }

  @Test
  void mwCryptoDownIsASystemErrorNotASignatureVerdict() {
    crypto.stubFor(post(urlEqualTo("/internal/v1/xmldsig/sign")).willReturn(aResponse().withStatus(503)));

    assertThatThrownBy(() -> signer.sign("<Document/>"))
        .isInstanceOf(MwException.class)
        .extracting("code")
        .isEqualTo("MW-SYS-CRYPTO_UNAVAILABLE");
  }
}
