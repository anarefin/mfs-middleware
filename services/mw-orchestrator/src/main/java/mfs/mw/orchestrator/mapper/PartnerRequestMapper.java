package mfs.mw.orchestrator.mapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import mfs.mw.orchestrator.dto.PartnerRequest;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Transaction to {@code mw.partner.request.v1} (spec 6.2, 7.3 rule 5). The {@code requestId} is a
 * name-based UUID of {@code <txnId>:<operation>}, so a resent request is the same request to the
 * adapter (which deduplicates on it). The PIN never goes to a partner.
 */
@Component
public class PartnerRequestMapper {

  public static final String PAY = "PAY";

  /** Stable request id of {@code operation} for {@code txnId} (UUID, as the contract requires). */
  public static String requestId(String txnId, String operation) {
    return UUID.nameUUIDFromBytes((txnId + ":" + operation).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** Our reference towards the partner: {@code UPAY} + the txnId's hex digits, upper case. */
  public static String partnerTxnRef(String txnId) {
    return "UPAY" + txnId.replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** The PAY request of a held transaction; the adapter must answer before {@code deadline}. */
  public PartnerRequest pay(TransactionRecord rec, PartnerCall call, Instant now, Duration deadline) {
    String txnId = rec.txnId().toString();
    TxnRequest req = rec.request();
    return new PartnerRequest(
        1, call.family(), requestId(txnId, PAY), txnId, req.correlationId(), now, now.plus(deadline),
        new PartnerRequest.Payload(
            PAY, req.txnType(), req.partner(), partnerTxnRef(txnId), req.amount(), req.debitParty(),
            req.creditParty(), null, req.attributes()));
  }
}
