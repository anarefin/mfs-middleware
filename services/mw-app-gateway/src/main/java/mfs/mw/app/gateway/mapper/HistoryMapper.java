package mfs.mw.app.gateway.mapper;

import mfs.mw.app.gateway.dto.HistoryItem;
import mfs.mw.app.gateway.dto.Party;
import mfs.mw.app.gateway.dto.QueryTxn;
import org.springframework.stereotype.Component;

/**
 * An orchestrator query item to what the wallet sees of it (ADR D25): the counterparty is the
 * credit party of what the wallet paid ({@code OUT}) and the debit party of what reached it
 * ({@code IN}), masked as the orchestrator sent it; the wallet's own side is left out, and so are
 * the fee and the error of an {@code IN} item. {@code requestedAt} is when the orchestrator took the
 * request ({@code createdAt}).
 */
@Component
public class HistoryMapper {

  public static final String IN = "IN";
  public static final String OUT = "OUT";

  public HistoryItem toItem(QueryTxn t) {
    boolean in = IN.equals(t.direction());
    Party other = in ? t.debitParty() : t.creditParty();
    return new HistoryItem(
        t.txnId(),
        t.txnType(),
        in ? IN : OUT,
        t.status(),
        t.amount(),
        in ? null : t.fee(),
        other == null ? null : new Party(other.type(), other.id(), other.name(), other.walletType()),
        t.coreRef(),
        in ? null : t.error(),
        t.createdAt(),
        t.updatedAt());
  }
}
