package mfs.mw.adapter.interop.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.interop.config.CryptoClientProperties;
import mfs.mw.adapter.interop.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * XML-DSig of the NPSB wire through mw-crypto (ticket 121b; mw-crypto ticket 65, contract
 * {@code api/mw-crypto/crypto-internal.v1.openapi.yaml}): {@link #sign} sends an outbound ISO 20022
 * document to {@code POST /internal/v1/xmldsig/sign} under our XMLDSIG key and gets it back with the
 * enveloped signature; {@link #verifyFromSwitch} checks an inbound one with {@code /verify} under the
 * switch's certificate (never the document's KeyInfo). No key material here. A signature that does
 * not verify is {@code MW-PTR-NPSB-SIGNATURE}; mw-crypto not answering is
 * {@code MW-SYS-CRYPTO_UNAVAILABLE} (fail closed: nothing is sent unsigned or accepted unverified).
 */
@Component
@ConditionalOnProperty(name = "mw.interop.crypto.enabled", havingValue = "true")
public class CryptoXmlSigner {

  public static final String SIGNATURE_INVALID = "MW-PTR-NPSB-SIGNATURE";
  public static final String CRYPTO_UNAVAILABLE = "MW-SYS-CRYPTO_UNAVAILABLE";

  private static final Logger log = LoggerFactory.getLogger(CryptoXmlSigner.class);

  private final CryptoClientProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CryptoXmlSigner(CryptoClientProperties props, ServiceTokenClient tokens, JsonMapper json, SslBundles bundles) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(props.connectTimeout());
    if (bundles != null && props.sslBundle() != null && !props.sslBundle().isBlank()) {
      builder.sslContext(bundles.getBundle(props.sslBundle()).createSslContext());
    }
    this.http = builder.build();
  }

  /** The document with our enveloped signature (RSA-SHA256, exclusive C14N, KeyName = kid). */
  public String sign(String xml) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("keyId", props.keyId());
    body.put("xml", base64(xml));
    JsonNode answer = call("/internal/v1/xmldsig/sign", body);
    String signed = answer.path("xml").asString("");
    if (signed.isEmpty()) {
      throw new MwException(CRYPTO_UNAVAILABLE, "mw-crypto sign answer has no document");
    }
    try {
      return new String(Base64.getDecoder().decode(signed), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException e) {
      throw new MwException(CRYPTO_UNAVAILABLE, "mw-crypto sign answer is not base64", e);
    }
  }

  /** Passes when the switch's signature verifies; else {@value #SIGNATURE_INVALID}. */
  public void verifyFromSwitch(String xml) {
    if (props.switchCertificatePem() == null || props.switchCertificatePem().isBlank()) {
      throw new MwException(SIGNATURE_INVALID, "no NPSB switch certificate configured");
    }
    JsonNode answer = call("/internal/v1/xmldsig/verify",
        Map.of("xml", base64(xml), "certificatePem", props.switchCertificatePem()));
    if (!answer.path("valid").asBoolean(false)) {
      String reason = answer.path("reason").asString("UNKNOWN");
      log.atWarn()
          .addKeyValue("event", "security.partner_signature_invalid")
          .addKeyValue("partnerId", "npsb")
          .addKeyValue("reason", reason)
          .log("NPSB message signature does not verify");
      throw new MwException(SIGNATURE_INVALID, "NPSB message signature does not verify: " + reason);
    }
  }

  /** mw-crypto's {@code XmlDocument}: the UTF-8 document in standard base64. */
  private static String base64(String xml) {
    return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
  }

  private JsonNode call(String path, Map<String, Object> body) {
    HttpResponse<byte[]> res = send(path, body);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = send(path, body);
    }
    if (res.statusCode() != 200) {
      throw new MwException(CRYPTO_UNAVAILABLE, "mw-crypto " + path + " answered " + res.statusCode());
    }
    return json.readTree(res.body());
  }

  private HttpResponse<byte[]> send(String path, Map<String, Object> body) {
    String correlation = MDC.get("correlationId");
    HttpRequest req = HttpRequest.newBuilder(URI.create(props.baseUrl().replaceAll("/+$", "") + path))
        .timeout(props.readTimeout())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + tokens.token())
        .header("X-Correlation-Id", correlation == null ? UUID.randomUUID().toString() : correlation)
        .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
        .build();
    try {
      return http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new MwException(CRYPTO_UNAVAILABLE, "mw-crypto not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new MwException(CRYPTO_UNAVAILABLE, "mw-crypto call interrupted", e);
    }
  }
}
