package mfs.mw.admin.api.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpResponse;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.dto.AdminTxn;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import org.springframework.stereotype.Component;

/**
 * mw-orchestrator's internal query API ({@code contracts/api/mw-orchestrator/txn-query.v1}, ticket
 * 35/36): search and one transaction with its timeline, never with an {@code orgId} (operators see
 * every transaction). {@code reveal} asks for unmasked MSISDNs; the service sets it only for an
 * operator allowed a reveal (spec 15.4). Idempotent reads: Resilience4j instance {@code
 * orchestrator} retries and breaks the circuit on {@link QueryUnavailableException} only.
 */
@Component
public class OrchestratorQueryClient {

  static final String PATH = "/internal/v1/transactions";
  static final String SERVICE = "mw-orchestrator";

  private final AdminApiProperties props;
  private final InternalApiCaller caller;

  public OrchestratorQueryClient(AdminApiProperties props, InternalApiCaller caller) {
    this.props = props;
    this.caller = caller;
  }

  /** @param filters query filters of {@code txn-query.v1} (null values are left out) */
  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public AdminTxn.Page search(Map<String, ?> filters) {
    String q = InternalApiCaller.query(filters);
    HttpResponse<byte[]> res = caller.get(props.orchestrator().baseUrl(), PATH + (q.isEmpty() ? "" : "?" + q), SERVICE);
    if (res.statusCode() != 200) {
      throw new QueryUnavailableException("orchestrator query answered " + res.statusCode(), null);
    }
    return caller.read(res.body(), AdminTxn.Page.class, SERVICE);
  }

  /** The transaction with its timeline and partner calls; empty when there is none. */
  @Retry(name = "orchestrator")
  @CircuitBreaker(name = "orchestrator")
  public Optional<AdminTxn.Detail> detail(UUID txnId, boolean reveal) {
    String path = PATH + "/" + txnId + (reveal ? "?reveal=true" : "");
    HttpResponse<byte[]> res = caller.get(props.orchestrator().baseUrl(), path, SERVICE);
    if (res.statusCode() == 404) {
      return Optional.empty();
    }
    if (res.statusCode() != 200) {
      throw new QueryUnavailableException("orchestrator query answered " + res.statusCode(), null);
    }
    return Optional.of(caller.read(res.body(), AdminTxn.Detail.class, SERVICE));
  }
}
