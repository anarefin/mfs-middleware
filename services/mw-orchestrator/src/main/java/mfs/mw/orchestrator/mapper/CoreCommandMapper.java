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
  public static final String STEP_REVERSE_QUERY = "reverse-query";
  public static final String STEP_CREDIT_CHECK = "credit-check";

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
    return commit(rec, now, ttl, 0);
  }

  /**
   * CommitHold of the {@code manualRound}-th operator decision (ticket 37): {@code
   * <txnId>:commit:manual-<n>}, so the core decides the operator's commit afresh instead of
   * replaying the refusal it gave the saga's own commit; resends within the round keep the id.
   * Round 0 is the saga's own commit ({@code <txnId>:commit}).
   */
  public CoreCommand commit(TransactionRecord rec, Instant now, Duration ttl, int manualRound) {
    return onHold("CommitHold", round(STEP_COMMIT, manualRound), rec, now, ttl);
  }

  /** The step part of a commandId: {@code step}, or {@code step:manual-<n>} for an operator round. */
  static String round(String step, int manualRound) {
    return manualRound <= 0 ? step : step + ":manual-" + manualRound;
  }

  /**
   * CreditWallet: the commit step of inbound money (ADD_MONEY_BANK, ticket 23), once the partner
   * collected it. {@code <txnId>:credit}, stable across resends, so the core credits once. No PIN.
   */
  public CoreCommand credit(TransactionRecord rec, Instant now, Duration ttl) {
    return credit(rec, now, ttl, 0);
  }

  /** CreditWallet of an operator round, as {@link #commit(TransactionRecord, Instant, Duration, int)}. */
  public CoreCommand credit(TransactionRecord rec, Instant now, Duration ttl, int manualRound) {
    String txnId = rec.txnId().toString();
    var req = rec.request();
    return new CoreCommand(
        1, "CreditWallet", commandId(txnId, round(STEP_CREDIT, manualRound)), txnId, req.correlationId(), now, now.plus(ttl),
        new CreditPayload(req.txnType(), req.debitParty(), req.creditParty(), req.amount()));
  }

  /** ReleaseHold; a resend keeps the commandId, so the core applies it once. */
  public CoreCommand release(TransactionRecord rec, Instant now, Duration ttl) {
    return release(rec, now, ttl, 0);
  }

  /** ReleaseHold of an operator round, as {@link #commit(TransactionRecord, Instant, Duration, int)}. */
  public CoreCommand release(TransactionRecord rec, Instant now, Duration ttl, int manualRound) {
    return onHold("ReleaseHold", round(STEP_RELEASE, manualRound), rec, now, ttl);
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

  /**
   * QueryTransaction asking whether a REVERSING transaction was reversed, sent once every
   * ReverseTransaction has expired: under the ORIGINAL's txnId (the core reports REVERSED for a
   * transaction it compensated), commandId {@code <txnId>:reverse-query:<epoch ms>}, fresh per
   * query, so its TransactionStatus answer is told apart from a HOLDING or inquiry answer.
   */
  public CoreCommand reversalQuery(TransactionRecord rec, Instant now, Duration ttl) {
    String txnId = rec.txnId().toString();
    return new CoreCommand(
        1, "QueryTransaction", commandId(txnId, STEP_REVERSE_QUERY) + ":" + now.toEpochMilli(), txnId,
        rec.request().correlationId(), now, now.plus(ttl), new QueryPayload(null));
  }

  /**
   * QueryTransaction a forced credit (ticket 37) sends before its fresh-round CreditWallet, so a
   * credit the core applied but whose answer was lost is never applied twice: commandId {@code
   * <txnId>:credit-check:<epoch ms>}, fresh per query, so its answer is told apart.
   */
  public CoreCommand creditCheck(TransactionRecord rec, Instant now, Duration ttl) {
    String txnId = rec.txnId().toString();
    return new CoreCommand(
        1, "QueryTransaction", commandId(txnId, STEP_CREDIT_CHECK) + ":" + now.toEpochMilli(), txnId,
        rec.request().correlationId(), now, now.plus(ttl), new QueryPayload(null));
  }

  /** True for the commandId of a {@link #creditCheck}. */
  public boolean isCreditCheck(String commandId) {
    return commandId != null && commandId.contains(":" + STEP_CREDIT_CHECK + ":");
  }

  /** True for the commandId of a {@link #reversalQuery}. */
  public boolean isReversalQuery(String commandId) {
    return commandId != null && commandId.contains(":" + STEP_REVERSE_QUERY + ":");
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
