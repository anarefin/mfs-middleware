package mfs.mw.app.gateway.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** One page of mw-orchestrator's query API (contract {@code txn-query.v1} {@code TxnPage}). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QueryTxnPage(List<QueryTxn> items, int limit, int offset) {

  public QueryTxnPage {
    items = items == null ? List.of() : List.copyOf(items);
  }
}
