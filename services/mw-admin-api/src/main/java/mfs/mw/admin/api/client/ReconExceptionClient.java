package mfs.mw.admin.api.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.config.ReconClientProperties;
import mfs.mw.admin.api.dto.ReconExceptionView;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import org.springframework.stereotype.Component;

/**
 * mw-recon's exception work queue ({@code contracts/api/mw-recon/recon.v1}, ticket 127b): one exception, and the
 * record of an approved action ({@code POST .../{id}/actions}, idempotent on the change request id). Resilience4j
 * instance {@code recon} retries and breaks the circuit on {@link QueryUnavailableException} only.
 */
@Component
public class ReconExceptionClient {

  static final String PATH = "/internal/v1/recon/exceptions/";
  static final String SERVICE = "mw-recon";

  /** What mw-recon said to a recorded action. */
  public enum Recorded {
    RECORDED,
    /** 409: the exception is already actioned by another request, resolved, or has no transaction. */
    REFUSED
  }

  private final ReconClientProperties props;
  private final InternalApiCaller caller;

  public ReconExceptionClient(ReconClientProperties props, InternalApiCaller caller) {
    this.props = props;
    this.caller = caller;
  }

  /** The exception; empty when mw-recon has none with that id. */
  @Retry(name = "recon")
  @CircuitBreaker(name = "recon")
  public Optional<ReconExceptionView> get(UUID id) {
    HttpResponse<byte[]> res = caller.get(props.baseUrl(), PATH + id, SERVICE);
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() != 200) {
      throw new QueryUnavailableException("recon exception query answered " + res.statusCode(), null);
    }
    return Optional.of(caller.read(res.body(), ReconExceptionView.class, SERVICE));
  }

  /** Records the approved action at mw-recon (OPEN / EXPLAINED -> ACTIONED). */
  @Retry(name = "recon")
  @CircuitBreaker(name = "recon")
  public Recorded recordAction(UUID id, String action, UUID changeRequestId, String checkerId) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("action", action);
    body.put("changeRequestId", changeRequestId.toString());
    HttpResponse<byte[]> res =
        caller.post(props.baseUrl(), PATH + id + "/actions", SERVICE, body, Map.of("X-Mw-Operator-Id", checkerId));
    if (res.statusCode() == 200) {
      return Recorded.RECORDED;
    }
    if (res.statusCode() == 409 || res.statusCode() == 404) {
      return Recorded.REFUSED;
    }
    throw new QueryUnavailableException("recon exception action answered " + res.statusCode(), null);
  }
}
