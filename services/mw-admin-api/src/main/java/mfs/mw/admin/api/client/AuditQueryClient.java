package mfs.mw.admin.api.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.admin.api.config.AdminApiProperties;
import mfs.mw.admin.api.dto.AuditTrail;
import mfs.mw.admin.api.exception.QueryUnavailableException;
import org.springframework.stereotype.Component;

/**
 * mw-audit-dw's internal audit search ({@code contracts/api/mw-audit-dw/audit-query.v1}, ticket
 * 35/36): the audit trail of one transaction, in the order it happened. Idempotent reads:
 * Resilience4j instance {@code audit-dw} retries and breaks the circuit on {@link
 * QueryUnavailableException} only.
 */
@Component
public class AuditQueryClient {

  static final String PATH = "/internal/v1/audit/events";
  static final String SERVICE = "mw-audit-dw";

  private final AdminApiProperties props;
  private final InternalApiCaller caller;

  public AuditQueryClient(AdminApiProperties props, InternalApiCaller caller) {
    this.props = props;
    this.caller = caller;
  }

  @Retry(name = "audit-dw")
  @CircuitBreaker(name = "audit-dw")
  public AuditTrail.Page ofTransaction(UUID txnId, int limit) {
    Map<String, Object> q = new LinkedHashMap<>();
    q.put("txnId", txnId);
    q.put("limit", limit);
    HttpResponse<byte[]> res = caller.get(props.auditDw().baseUrl(), PATH + "?" + InternalApiCaller.query(q), SERVICE);
    if (res.statusCode() != 200) {
      throw new QueryUnavailableException("audit query answered " + res.statusCode(), null);
    }
    return caller.read(res.body(), AuditTrail.Page.class, SERVICE);
  }
}
