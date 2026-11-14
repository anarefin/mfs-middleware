package mfs.mw.admin.api.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.config.ServiceCertificateManager;
import mfs.mw.admin.api.dto.CryptoKeys;
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-crypto's key API (ticket 75; contract {@code api/mw-crypto/crypto-internal.v1}): the key
 * list, approved key changes ({@code PUT /internal/v1/key-changes/{id}}) and the key ceremony
 * ({@code /internal/v1/ceremonies/**}). mw-crypto needs mTLS: the JDK client is built once on
 * {@link ServiceCertificateManager#clientSslContext()} when {@code mw.tls.enabled} (the stack),
 * with this service's client-credentials token ({@code azp} mw-admin-api, a key-admin client
 * there). A ceremony component passes through in the request body only: never logged, never kept.
 *
 * <p>Failures: mw-crypto's own {@code MW-VAL-*} refusals keep their code and status (bad
 * component, conflict, unknown key); a 401 drops the cached token and retries once; anything else
 * (unreachable, timeout, 5xx, another refusal) is 503 {@code MW-SYS-042}.
 */
@Component
public class CryptoKeyClient {

  private static final Logger log = LoggerFactory.getLogger(CryptoKeyClient.class);
  private static final String SERVICE = "mw-crypto";

  private final AdminApiProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public CryptoKeyClient(
      AdminApiProperties props,
      ServiceTokenClient tokens,
      JsonMapper json,
      ObjectProvider<ServiceCertificateManager> certificates) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    HttpClient.Builder b =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager certs = certificates.getIfAvailable();
    if (certs != null) {
      b.sslContext(certs.clientSslContext());
    }
    this.http = b.build();
  }

  public CryptoKeys.Page list() {
    return read(send("GET", "/internal/v1/keys", null, "keys.list"), CryptoKeys.Page.class, "keys.list");
  }

  /** An approved generate / rotate / retire; idempotent on {@code changeRequestId}. */
  public CryptoKeys.Key applyChange(
      UUID changeRequestId, String action, String keyId, String purpose, Integer version, String makerId,
      String checkerId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("action", action);
    body.put("keyId", keyId);
    if (purpose != null) {
      body.put("purpose", purpose);
    }
    if (version != null) {
      body.put("version", version);
    }
    body.put("makerId", makerId);
    body.put("checkerId", checkerId);
    return read(send("PUT", "/internal/v1/key-changes/" + changeRequestId, body, "key.change"),
        CryptoKeys.Key.class, "key.change");
  }

  /** Whether the change of {@code changeRequestId} was applied (asked before a claim is given up). */
  public CryptoKeys.ChangeState keyChangeState(UUID changeRequestId) {
    return read(send("GET", "/internal/v1/key-changes/" + changeRequestId, null, "key.change.state"),
        CryptoKeys.ChangeState.class, "key.change.state");
  }

  /** Opens the ceremony of KEY_CEREMONY request {@code ceremonyId} (at filing time; idempotent). */
  public CryptoKeys.Ceremony openCeremony(UUID ceremonyId, String keyId, String purpose) {
    return read(send("PUT", "/internal/v1/ceremonies/" + ceremonyId, Map.of("keyId", keyId, "purpose", purpose),
        "ceremony.open"), CryptoKeys.Ceremony.class, "ceremony.open");
  }

  /** The ceremony as mw-crypto has it; empty when it has none of that id (404). */
  public java.util.Optional<CryptoKeys.Ceremony> ceremony(UUID ceremonyId) {
    try {
      return java.util.Optional.of(read(send("GET", "/internal/v1/ceremonies/" + ceremonyId, null, "ceremony.get"),
          CryptoKeys.Ceremony.class, "ceremony.get"));
    } catch (MwException e) {
      if (ErrorCodes.VAL_NOT_FOUND.equals(e.code())) {
        return java.util.Optional.empty();
      }
      throw e;
    }
  }

  /** One custodian's component of ceremony {@code ceremonyId}; the component is only in the body. */
  public CryptoKeys.Ceremony enterComponent(
      UUID ceremonyId, String keyId, String purpose, String custodian, String component, String checkValue) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("keyId", keyId);
    body.put("purpose", purpose);
    body.put("custodian", custodian);
    body.put("component", component);
    body.put("checkValue", checkValue);
    return read(send("POST", "/internal/v1/ceremonies/" + ceremonyId + "/components", body, "ceremony.component"),
        CryptoKeys.Ceremony.class, "ceremony.component");
  }

  public CryptoKeys.Key commit(UUID ceremonyId, String makerId, String checkerId) {
    return read(send("POST", "/internal/v1/ceremonies/" + ceremonyId + "/commit",
            Map.of("makerId", makerId, "checkerId", checkerId), "ceremony.commit"),
        CryptoKeys.Key.class, "ceremony.commit");
  }

  public void abort(UUID ceremonyId) {
    send("POST", "/internal/v1/ceremonies/" + ceremonyId + "/abort", Map.of(), "ceremony.abort");
  }

  private byte[] send(String method, String path, Object body, String op) {
    HttpResponse<byte[]> res = once(method, path, body, op);
    if (res.statusCode() == 401) {
      tokens.invalidate();
      res = once(method, path, body, op);
    }
    int status = res.statusCode();
    if (status >= 200 && status < 300) {
      return res.body();
    }
    if (status >= 400 && status < 500) {
      MwException passed = passThrough(res.body(), status);
      if (passed != null) {
        throw passed;
      }
    }
    log.atWarn()
        .addKeyValue("event", "admin.crypto.call_failed")
        .addKeyValue("op", op)
        .addKeyValue("status", status)
        .addKeyValue("errorCode", ErrorCodes.SYS_CRYPTO_UNAVAILABLE)
        .log("mw-crypto refused or failed the call");
    throw unavailable(op, null);
  }

  private HttpResponse<byte[]> once(String method, String path, Object body, String op) {
    String root = props.crypto().baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create(root + path))
            .timeout(props.readTimeout())
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + tokens.token());
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      b.header("X-Correlation-Id", correlationId);
    }
    if (body == null) {
      b.method(method, HttpRequest.BodyPublishers.noBody());
    } else {
      b.header("Content-Type", "application/json")
          .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)));
    }
    try {
      return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      log.atWarn()
          .addKeyValue("event", "admin.crypto.call_failed")
          .addKeyValue("op", op)
          .addKeyValue("errorClass", e.getClass().getSimpleName())
          .addKeyValue("errorCode", ErrorCodes.SYS_CRYPTO_UNAVAILABLE)
          .log("mw-crypto not reachable");
      throw unavailable(op, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable(op, e);
    }
  }

  /** mw-crypto's MW-VAL-* envelope as this service's error, same code, message and status; else null. */
  private MwException passThrough(byte[] body, int status) {
    try {
      JsonNode e = json.readTree(body);
      String code = e.path("code").asString("");
      if (!code.startsWith("MW-VAL-")) {
        return null;
      }
      String message = e.path("message").asString("Refused by mw-crypto");
      return new MwException(code, message).withStatus(HttpStatus.valueOf(status));
    } catch (JacksonException | IllegalArgumentException ex) {
      return null;
    }
  }

  private <T> T read(byte[] body, Class<T> type, String op) {
    try {
      return json.readValue(body, type);
    } catch (JacksonException e) {
      throw unavailable(op, e);
    }
  }

  private static MwException unavailable(String op, Throwable cause) {
    return new MwException(ErrorCodes.SYS_CRYPTO_UNAVAILABLE, SERVICE + " unavailable: " + op, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true);
  }
}
