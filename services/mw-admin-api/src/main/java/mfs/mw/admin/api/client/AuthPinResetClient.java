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
import mfs.mw.admin.api.exception.ErrorCodes;
import mfs.mw.admin.api.exception.MwException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * mw-auth's care PIN reset ({@code POST /internal/v1/pin-resets} of {@code auth-internal.v1},
 * ticket 70), called when an ops-checker approves a PIN_RESET change request. Over mTLS with this
 * service's OpenBao certificate when {@code mw.tls.enabled} (mw-auth's internal port needs it; CN
 * {@code mw-admin-api} == the token's {@code azp}) and the services token of {@link
 * ServiceTokenClient} (audience mw-auth). Idempotent on the change request id at mw-auth, so a retry
 * after a lost answer opens nothing twice. Not reachable, a 401 (the cached token dropped) or a 5xx:
 * 503 {@code MW-SYS-040} (the request stays PENDING; approve again); a wallet mw-auth does not know:
 * 409 {@code MW-VAL-030}. Nothing personal is logged.
 */
@Component
public class AuthPinResetClient {

  static final String PATH = "/internal/v1/pin-resets";
  private static final Logger log = LoggerFactory.getLogger(AuthPinResetClient.class);

  /** The reset mw-auth opened (or had opened) for the change request. */
  public record CareReset(String resetId, String status) {}

  private final AdminApiProperties props;
  private final ServiceTokenClient tokens;
  private final JsonMapper json;
  private final HttpClient http;

  public AuthPinResetClient(
      AdminApiProperties props,
      ServiceTokenClient tokens,
      JsonMapper json,
      ObjectProvider<ServiceCertificateManager> certificates) {
    this.props = props;
    this.tokens = tokens;
    this.json = json;
    HttpClient.Builder b =
        HttpClient.newBuilder().connectTimeout(props.connectTimeout()).followRedirects(HttpClient.Redirect.NEVER);
    ServiceCertificateManager manager = certificates.getIfAvailable();
    if (manager != null) {
      b.sslContext(manager.clientSslContext());
    }
    this.http = b.build();
  }

  public CareReset approve(String walletId, UUID changeRequestId, String makerId, String checkerId, String reason) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("walletId", walletId);
    body.put("changeRequestId", changeRequestId.toString());
    body.put("makerId", makerId);
    body.put("checkerId", checkerId);
    if (reason != null && !reason.isBlank()) {
      body.put("reason", reason);
    }
    String root = props.auth().baseUrl().toString().replaceAll("/+$", "");
    HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(root + PATH))
        .timeout(props.readTimeout())
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("Authorization", "Bearer " + tokens.token())
        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    String correlationId = MDC.get("correlationId");
    if (correlationId != null) {
      req.header("X-Correlation-Id", correlationId);
    }
    HttpResponse<byte[]> res;
    try {
      res = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
    } catch (IOException e) {
      throw unavailable("mw-auth not reachable", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw unavailable("interrupted", e);
    }
    int status = res.statusCode();
    if (status == 200 || status == 201) {
      try {
        Map<?, ?> m = json.readValue(res.body(), Map.class);
        return new CareReset(String.valueOf(m.get("resetId")), String.valueOf(m.get("status")));
      } catch (JacksonException e) {
        throw unavailable("mw-auth answer unreadable", e);
      }
    }
    log.atWarn().addKeyValue("event", "admin.pin_reset.refused").addKeyValue("status", status)
        .log("mw-auth did not open the care PIN reset");
    if (status == 401) {
      tokens.invalidate();
    }
    if (status == 404) {
      throw new MwException(ErrorCodes.VAL_ACTION_NOT_ALLOWED, "mw-auth does not know this wallet")
          .withStatus(HttpStatus.CONFLICT);
    }
    throw unavailable("mw-auth answered " + status, null);
  }

  private static MwException unavailable(String message, Throwable cause) {
    return new MwException(ErrorCodes.SYS_QUERY_UNAVAILABLE, "mw-auth unavailable: " + message, cause)
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
  }
}
