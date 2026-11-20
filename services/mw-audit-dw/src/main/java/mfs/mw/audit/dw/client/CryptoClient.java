package mfs.mw.audit.dw.client;

import com.nimbusds.jose.jwk.JWKSet;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.audit.dw.config.AnchorProperties;
import mfs.mw.audit.dw.config.ServiceCertificateManager;
import mfs.mw.audit.dw.dto.AuditAnchor;
import mfs.mw.audit.dw.exception.CryptoUnavailableException;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-crypto's internal API for the audit anchor (ticket 74, contract {@code
 * api/mw-crypto/crypto-internal.v1.openapi.yaml}): {@code POST /internal/v1/jws/sign} with purpose
 * {@code AUDIT_ANCHOR_SIGN} (bound to this service in mw-crypto's {@code mw.crypto.authz}) and
 * {@code GET /internal/v1/jwks/AUDIT_ANCHOR_SIGN}. mTLS is mandatory at mw-crypto: the JDK client is
 * built once on {@link ServiceCertificateManager#clientSslContext()} (which follows renewals) when
 * {@code mw.tls.enabled}; the client-credentials JWT goes with every call (its {@code azp} must
 * equal the certificate CN). Not retried here: the job tries again on its next run. Any failure is
 * a {@link CryptoUnavailableException}; bodies are never logged.
 */
@Component
@ConditionalOnProperty(name = "mw.audit-dw.anchor.enabled", havingValue = "true", matchIfMissing = true)
public class CryptoClient implements MsisdnTokeniser {

  static final String SIGN_PATH = "/internal/v1/jws/sign";
  static final String TOKENS_PATH = "/internal/v1/tokens";
  static final String JWKS_PATH = "/internal/v1/jwks/AUDIT_ANCHOR_SIGN";
  static final String PURPOSE = "AUDIT_ANCHOR_SIGN";
  /** JWS {@code typ} of an anchor, so it can never pass for another kind of token. */
  public static final String ANCHOR_TYPE = "mw-audit-anchor+jws";

  /** A signed anchor: the compact JWS and the key version that signed it. */
  public record Signed(String jws, String kid) {}

  private final AnchorProperties.Crypto props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CryptoClient(
      AnchorProperties props,
      ServiceTokenClient tokens,
      JsonMapper json,
      ObjectProvider<ServiceCertificateManager> certificates) {
    this.props = props.crypto();
    this.tokens = tokens;
    this.json = json;
    HttpClient.Builder builder =
        HttpClient.newBuilder().connectTimeout(this.props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager certs = certificates.getIfAvailable();
    if (certs != null) {
      builder.sslContext(certs.clientSslContext());
    }
    this.http = builder.build();
  }

  /** Signs {@code anchor} as an ES256 JWS with the ACTIVE {@code AUDIT_ANCHOR_SIGN} key version. */
  public Signed sign(AuditAnchor anchor) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("purpose", PURPOSE);
    body.put("header", Map.of("typ", ANCHOR_TYPE));
    body.put("payload", anchor);
    HttpRequest req =
        request(SIGN_PATH)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
            .build();
    JsonNode answer = read(send(req, "sign"), "sign");
    String jws = answer.path("jws").asString("");
    String kid = answer.path("kid").asString("");
    if (jws.isBlank() || kid.isBlank()) {
      throw new CryptoUnavailableException("mw-crypto sign answer without jws or kid", null);
    }
    return new Signed(jws, kid);
  }

  /** Every {@code AUDIT_ANCHOR_SIGN} key version in use (current and previous), public keys only. */
  public JWKSet jwks() {
    HttpResponse<byte[]> res = send(request(JWKS_PATH).GET().build(), "jwks");
    try {
      return JWKSet.parse(new String(res.body(), StandardCharsets.UTF_8));
    } catch (ParseException e) {
      throw new CryptoUnavailableException("mw-crypto JWKS unreadable", e);
    }
  }

  /**
   * Ticket 130: MSISDN tokens for the search projection ({@code POST /internal/v1/tokens}, domain
   * {@code MSISDN}, purpose {@code TOKENISE_MSISDN} bound to this service in mw-crypto's {@code
   * mw.crypto.authz}). The values are never logged.
   */
  @Override
  public List<String> tokenise(List<String> msisdns) {
    HttpRequest req =
        request(TOKENS_PATH)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofByteArray(
                    json.writeValueAsBytes(Map.of("domain", "MSISDN", "values", msisdns))))
            .build();
    JsonNode tokens = read(send(req, "tokens"), "tokens").path("tokens");
    if (!tokens.isArray() || tokens.size() != msisdns.size()) {
      throw new CryptoUnavailableException("mw-crypto tokens answer does not match the request", null);
    }
    List<String> out = new ArrayList<>();
    tokens.forEach(t -> out.add(t.asString()));
    return out;
  }

  private HttpRequest.Builder request(String path) {
    String correlationId = MDC.get("correlationId");
    return HttpRequest.newBuilder(URI.create(props.baseUrl().toString().replaceAll("/+$", "") + path))
        .timeout(props.readTimeout())
        .header("Accept", "application/json")
        .header("Authorization", "Bearer " + tokens.token())
        .header("X-Correlation-Id", correlationId == null ? UUID.randomUUID().toString() : correlationId);
  }

  private HttpResponse<byte[]> send(HttpRequest req, String op) {
    HttpResponse<byte[]> res;
    try {
      res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw new CryptoUnavailableException("mw-crypto " + op + " not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CryptoUnavailableException("interrupted", e);
    }
    if (res.statusCode() == 401) {
      tokens.invalidate();
    }
    if (res.statusCode() != 200) {
      throw new CryptoUnavailableException("mw-crypto " + op + " answered " + res.statusCode(), null);
    }
    return res;
  }

  private JsonNode read(HttpResponse<byte[]> res, String op) {
    try {
      return json.readTree(res.body());
    } catch (JacksonException e) {
      throw new CryptoUnavailableException("mw-crypto " + op + " answer unreadable", e);
    }
  }
}
