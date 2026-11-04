package mfs.mw.orchestrator.mapper;

import java.time.Duration;
import java.time.Instant;
import mfs.mw.orchestrator.dto.CoreCommand;
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

  public String commandId(String txnId, String step) {
    return txnId + ":" + step;
  }

  public CoreCommand validate(TransactionRecord rec, Instant now, Duration ttl) {
    return transfer("ValidateTransaction", STEP_VALIDATE, rec, now, ttl);
  }

  public CoreCommand post(TransactionRecord rec, Instant now, Duration ttl) {
    return transfer("PostTransfer", STEP_POST, rec, now, ttl);
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
