package mfs.mw.notify.client;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import java.net.http.HttpClient;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import mfs.mw.notify.config.PushProperties;
import mfs.mw.notify.dto.PushMessage;
import mfs.mw.notify.dto.PushResult;
import mfs.mw.notify.exception.PushRefusedException;
import mfs.mw.notify.exception.PushSendException;
import mfs.mw.notify.util.MaskingPolicy;
import mfs.mw.notify.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The app-server push API (ticket 110, FRS EXT-04, NOT-06): {@code POST <base-url>/push/v1/messages}, FCM /
 * APNs style ({@code {messageId, token, platform, notification {title, body}, data {txnId}}}), {@code
 * Authorization: key=<mw.notify.push.api-key>}. Every call has the read timeout {@code
 * mw.notify.push.timeout} and goes through the Resilience4j retry {@code push} (default 3 attempts,
 * exponential backoff from 200 ms) inside the circuit breaker {@code push}.
 *
 * <p>2xx: the acknowledgement ({@code status DELIVERED} = delivered, else only accepted; an unreadable body
 * still counts as accepted). 4xx: {@link PushRefusedException}, never retried (404 = token not registered).
 * 5xx, timeout, I/O, breaker open, switched off: {@link PushSendException} after the retries. Tokens are
 * logged masked to their last 6 characters; titles and bodies never.
 */
@Component
public class PushClient {

  static final String INSTANCE = "push";

  private static final Logger log = LoggerFactory.getLogger(PushClient.class);

  private final PushProperties props;
  private final RestClient http;
  private final Retry retry;
  private final CircuitBreaker breaker;
  private final JsonMapper json = JsonMapper.builder().build();

  public PushClient(PushProperties props, RetryRegistry retries, CircuitBreakerRegistry breakers) {
    this.props = props;
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(props.connectTimeout())
        .followRedirects(HttpClient.Redirect.NEVER)
        .version(HttpClient.Version.HTTP_1_1)
        .build());
    factory.setReadTimeout(props.timeout());
    this.http = RestClient.builder().baseUrl(props.baseUrl()).requestFactory(factory).build();
    this.retry = retries.retry(INSTANCE);
    this.breaker = breakers.circuitBreaker(INSTANCE);
  }

  /** Sends one push; the acknowledgement, or {@link PushRefusedException} / {@link PushSendException}. */
  public PushResult send(PushMessage m) {
    if (!props.enabled()) {
      throw new PushSendException(-1, "push client switched off");
    }
    Supplier<PushResult> once = () -> breaker.executeSupplier(() -> post(m));
    try {
      return Retry.decorateSupplier(retry, once).get();
    } catch (CallNotPermittedException e) {
      throw new PushSendException(-1, "push circuit breaker open");
    } finally {
      log.atDebug()
          .addKeyValue("event", "notify.push.call")
          .addKeyValue("messageId", m.messageId())
          .addKeyValue("token", MaskingPolicy.mask(SensitiveType.PUSH_TOKEN, m.token()))
          .log("push call done");
    }
  }

  private PushResult post(PushMessage m) {
    ResponseEntity<String> res;
    try {
      res = http.post()
          .uri("/push/v1/messages")
          .header("Authorization", "key=" + (props.apiKey() == null ? "" : props.apiKey()))
          .contentType(MediaType.APPLICATION_JSON)
          .body(json.writeValueAsString(body(m)))
          .retrieve()
          .onStatus(s -> true, (req, r) -> { })
          .toEntity(String.class);
    } catch (RestClientException e) {
      throw new PushSendException(-1, "push server unreachable or too slow: " + e.getClass().getSimpleName());
    }
    int status = res.getStatusCode().value();
    if (status >= 500 || status == 429 || status == 408) {
      throw new PushSendException(status, "push server answered " + status);
    }
    JsonNode ack = read(res.getBody());
    if (status >= 400) {
      throw new PushRefusedException(status, ack == null ? null : text(ack, "error"));
    }
    if (ack == null) {
      return new PushResult(null, false);
    }
    return new PushResult(text(ack, "messageId"), "DELIVERED".equals(text(ack, "status")));
  }

  private static Map<String, Object> body(PushMessage m) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("messageId", m.messageId());
    b.put("token", m.token());
    if (m.platform() != null) {
      b.put("platform", m.platform());
    }
    b.put("notification", Map.of("title", m.title() == null ? "" : m.title(), "body", m.body()));
    if (m.txnId() != null) {
      b.put("data", Map.of("txnId", m.txnId()));
    }
    return b;
  }

  private JsonNode read(String body) {
    if (body == null || body.isBlank()) {
      return null;
    }
    try {
      JsonNode n = json.readTree(body);
      return n.isObject() ? n : null;
    } catch (JacksonException e) {
      return null;
    }
  }

  private static String text(JsonNode n, String field) {
    JsonNode v = n.get(field);
    return v == null || v.isNull() ? null : v.asString();
  }
}
