package mfs.mw.app.gateway.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.app.gateway.client.OrchestratorQueryClient;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.dto.ErrorDetail;
import mfs.mw.app.gateway.dto.HistoryItem;
import mfs.mw.app.gateway.dto.QueryTxnPage;
import mfs.mw.app.gateway.dto.TransactionHistory;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.mapper.HistoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The signed-in wallet's transaction history ({@code GET /app/v1/transactions}, ADR D25): sent and
 * received, every channel (app, USSD, bulk credits), as mw-orchestrator's query API answers it for
 * that wallet. The wallet is always the token's, never a parameter, so nobody reads another
 * wallet's history. Any app role. Read-only, nothing moves. One more item than asked is fetched to
 * tell whether another page follows. The orchestrator out of reach (or its breaker open) is 503
 * {@code MW-SYS-013}, retryable.
 */
@Service
public class TransactionHistoryService {

  public static final int DEFAULT_LIMIT = 20;
  public static final int MAX_LIMIT = 100;
  public static final int MAX_OFFSET = 10_000;

  private static final Logger log = LoggerFactory.getLogger(TransactionHistoryService.class);

  private final OrchestratorQueryClient client;
  private final HistoryMapper mapper;

  public TransactionHistoryService(OrchestratorQueryClient client, HistoryMapper mapper) {
    this.client = client;
    this.mapper = mapper;
  }

  /**
   * @param from inclusive lower bound of when the transaction was requested, or null
   * @param to exclusive upper bound, or null
   */
  public TransactionHistory history(
      AppUser user, int limit, int offset, String txnType, String direction, Instant from, Instant to) {
    if (from != null && to != null && !from.isBefore(to)) {
      throw new MwException(ErrorCodes.VAL_INVALID, "Request validation failed")
          .withDetails(List.of(new ErrorDetail("from", "must be before to")));
    }
    Map<String, Object> filters = new LinkedHashMap<>();
    filters.put("direction", direction);
    filters.put("txnType", txnType);
    filters.put("from", from);
    filters.put("to", to);
    filters.put("limit", limit + 1);
    filters.put("offset", offset);
    QueryTxnPage page;
    try {
      page = client.walletHistory(user.msisdn(), filters);
    } catch (RuntimeException e) {
      // unavailable after the retries, or the breaker open (CallNotPermittedException)
      log.atWarn()
          .addKeyValue("event", "orchestrator.query.unavailable")
          .addKeyValue("errorCode", ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("transaction history could not be read");
      throw new MwException(
              ErrorCodes.SYS_ORCHESTRATOR_UNAVAILABLE, "Transaction history temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    List<HistoryItem> items = page.items().stream().limit(limit).map(mapper::toItem).toList();
    return new TransactionHistory(items, limit, offset, page.items().size() > limit);
  }
}
