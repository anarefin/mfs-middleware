package mfs.mw.orchestrator.mapper;

import java.time.Duration;
import java.time.Instant;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.CoreCommand;
import mfs.mw.orchestrator.dto.CreditPayload;
import mfs.mw.orchestrator.dto.ReversePayload;
import mfs.mw.orchestrator.dto.HoldPayload;
import mfs.mw.orchestrator.dto.QueryPayload;
import mfs.mw.orchestrator.dto.TransferPayload;
import mfs.mw.orchestrator.entity.TransactionRecord;
import org.springframework.stereotype.Component;

/**
 * Transaction to core command (spec 6.3). {@code commandId} is {@code <txnId>:<step>}, so a
 * resent command is deduplicated by the core (7.3 rule 6).
 */
@Component
public class CoreCommandMapper {

  public static final String STEP_VALIDATE = "validate";
  public static final String STEP_POST = "post";
  public static final String STEP_HOLD = "hold";
  public static final String STEP_COMMIT = "commit";
  public static final String STEP_CREDIT = "credit";
  public static final String STEP_RELEASE = "release";
  public static final String STEP_QUERY = "query";
  public static final String STEP_REVERSE = "reverse";

  public String commandId(String txnId, String step) {
    return txnId + ":" + step;
  }

  public CoreCommand validate(TransactionRecord rec, Instant now, Duration ttl) {
    return transfer("ValidateTransaction", STEP_VALIDATE, rec, now, ttl);
  }

  public CoreCommand post(TransactionRecord rec, Instant now, Duration ttl) {
    return transfer("PostTransfer", STEP_POST, rec, now, ttl);
  }

  /** HoldFunds: the same payload as a transfer; the core reserves amount + fee. */
  public CoreCommand hold(TransactionRecord rec, Instant now, Duration ttl) {
    return transfer("HoldFunds", STEP_HOLD, rec, now, ttl);
  }

  /** CommitHold; a resend keeps the commandId, so the core applies it once. */
  public CoreCommand commit(TransactionRecord rec, Instant now, Duration ttl) {
    return onHold("CommitHold", STEP_COMMIT, rec, now, ttl);
  }

  /**
   * CreditWallet: the commit step of inbound money (ADD_MONEY_BANK, ticket 23), once the partner
   * collected it. {@code <txnId>:credit}, stable across resends, so the core credits once. No PIN.
   */
  public CoreCommand credit(TransactionRecord rec, Instant now, Duration ttl) {
    String txnId = rec.txnId().toString();
    var req = rec.request();
    return new CoreCommand(
        1, "CreditWallet", commandId(txnId, STEP_CREDIT), txnId, req.correlationId(), now, now.plus(ttl),
        new CreditPayload(req.txnType(), req.debitParty(), req.creditParty(), req.amount()));
  }

  /** ReleaseHold; a resend keeps the commandId, so the core applies it once. */
  public CoreCommand release(TransactionRecord rec, Instant now, Duration ttl) {
    return onHold("ReleaseHold", STEP_RELEASE, rec, now, ttl);
  }

  /**
   * ReverseTransaction of a completed transaction, sent under the REVERSAL request's own txnId
   * (the core books the compensating entries under it): {@code <reversalTxnId>:reverse}, stable
   * across resends. Amount and fee are the original's; the core undoes every entry once.
   */
  public CoreCommand reverse(TransactionRecord rec, Instant now, Duration ttl) {
    if (rec.reversalTxnId() == null) {
      throw new IllegalStateException("ReverseTransaction needs a reversal txnId; transaction " + rec.txnId() + " has none");
    }
    String reversalTxnId = rec.reversalTxnId().toString();
    return new CoreCommand(
        1, "ReverseTransaction", commandId(reversalTxnId, STEP_REVERSE), reversalTxnId,
        rec.request().correlationId(), now, now.plus(ttl),
        new ReversePayload(
            rec.txnId().toString(), rec.request().amount(),
            rec.feeMinor() == null ? null : Amount.bdt(rec.feeMinor()), null));
  }

  /**
   * QueryTransaction without {@code queryCommandId}: the core reports the transaction's current
   * state. Each query has its own commandId ({@code <txnId>:query:<epoch ms>}) so a repeated query
   * is answered afresh rather than from the core's command log; queries change nothing.
   */
  public CoreCommand query(TransactionRecord rec, Instant now, Duration ttl) {
    String txnId = rec.txnId().toString();
    return new CoreCommand(
        1, "QueryTransaction", commandId(txnId, STEP_QUERY) + ":" + now.toEpochMilli(), txnId,
        rec.request().correlationId(), now, now.plus(ttl), new QueryPayload(null));
  }

  private CoreCommand onHold(String type, String step, TransactionRecord rec, Instant now, Duration ttl) {
    if (rec.holdId() == null) {
      throw new IllegalStateException(type + " needs a hold id; transaction " + rec.txnId() + " has none");
    }
    String txnId = rec.txnId().toString();
    return new CoreCommand(
        1, type, commandId(txnId, step), txnId, rec.request().correlationId(), now, now.plus(ttl),
        new HoldPayload(rec.holdId(), null));
  }

  private CoreCommand transfer(
      String type, String step, TransactionRecord rec, Instant now, Duration ttl) {
    String txnId = rec.txnId().toString();
    var req = rec.request();
    return new CoreCommand(
        1, type, commandId(txnId, step), txnId, req.correlationId(), now, now.plus(ttl),
        new TransferPayload(
            req.txnType(), req.debitParty(), req.creditParty(), req.amount(), rec.encryptedPin()));
  }
}
