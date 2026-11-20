package mfs.mw.orchestrator.mapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import mfs.mw.orchestrator.config.MerchantSystemProperties;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.PartnerRef;
import mfs.mw.orchestrator.dto.PartnerRequest;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.service.saga.MerchantPayFlow;
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
  public static final String STATUS_QUERY = "STATUS_QUERY";
  public static final String REVERSE = "REVERSE";

  /** Stable request id of {@code operation} for {@code txnId} (UUID, as the contract requires). */
  public static String requestId(String txnId, String operation) {
    return UUID.nameUUIDFromBytes((txnId + ":" + operation).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /**
   * The partner REVERSE key (ticket 96b, PLT-025): the name-based UUID of {@code reverse:<txnId>},
   * the same on every resend.
   */
  public static String reverseRequestId(String txnId) {
    return UUID.nameUUIDFromBytes(("reverse:" + txnId).getBytes(StandardCharsets.UTF_8)).toString();
  }

  /** Our reference towards the partner: {@code BITS} + the txnId's hex digits, upper case. */
  public static String partnerTxnRef(String txnId) {
    return "BITS" + txnId.replace("-", "").toUpperCase(Locale.ROOT);
  }

  /** The PAY request of a held transaction; the adapter must answer before {@code deadline}. */
  public PartnerRequest pay(TransactionRecord rec, PartnerCall call, Instant now, Duration deadline) {
    return pay(rec, call, now, deadline, PAY);
  }

  /**
   * A PAY. {@code requestKey} is {@code PAY} for the first attempt and {@code PAY:<partner>} for a
   * failover, so the first request id stays {@code <txnId>:PAY} and a later attempt is a new request.
   * The payload operation is always {@code PAY}: the adapter switches on that.
   */
  public PartnerRequest pay(TransactionRecord rec, PartnerCall call, Instant now, Duration deadline, String requestKey) {
    String txnId = rec.txnId().toString();
    TxnRequest req = rec.request();
    return new PartnerRequest(
        1, call.family(), requestId(txnId, requestKey), txnId, req.correlationId(), now, now.plus(deadline),
        new PartnerRequest.Payload(
            PAY, req.txnType(), req.partner(), partnerTxnRef(txnId), req.amount(), req.debitParty(),
            req.creditParty(), null, req.attributes()));
  }

  public static final String INQUIRY = "INQUIRY";

  /**
   * The merchant VALIDATE (ticket 115b): an INQUIRY to the merchant system before the core is asked.
   * Partner = the merchant's record, credit party {@code {MERCHANT, <merchant id in that system>}},
   * {@code attributes.tillId} the till; request id {@code <txnId>:INQUIRY}.
   */
  public PartnerRequest merchantValidate(
      TransactionRecord rec, MerchantSystemProperties.Merchant merchant, Instant now, Duration deadline) {
    return merchant(rec, merchant, INQUIRY, INQUIRY, null, now, deadline);
  }

  /**
   * The merchant NOTIFY (ticket 115b): a PAY to the merchant system after COMPLETED, attempt {@code n}
   * (1 = first); request id {@code <txnId>:NOTIFY:<n>}, so each retry is a new request to the adapter.
   */
  public PartnerRequest merchantNotify(
      TransactionRecord rec, MerchantSystemProperties.Merchant merchant, int attempt, Instant now, Duration deadline) {
    return merchant(rec, merchant, PAY, "NOTIFY:" + attempt, rec.request().amount(), now, deadline);
  }

  private PartnerRequest merchant(
      TransactionRecord rec, MerchantSystemProperties.Merchant merchant, String operation, String key,
      Amount amount, Instant now, Duration deadline) {
    String txnId = rec.txnId().toString();
    TxnRequest req = rec.request();
    Map<String, String> attributes = new LinkedHashMap<>();
    if (merchant.tillId() != null && !merchant.tillId().isBlank()) {
      attributes.put("tillId", merchant.tillId());
    }
    return new PartnerRequest(
        1, MerchantPayFlow.MERCHANT.family(), requestId(txnId, key), txnId, req.correlationId(), now,
        now.plus(deadline),
        new PartnerRequest.Payload(
            operation, req.txnType(), new PartnerRef(merchant.partnerId(), "MERCHANT", null), partnerTxnRef(txnId),
            amount, req.debitParty(), new Party("MERCHANT", merchant.merchantId(), null, null), null,
            attributes.isEmpty() ? null : attributes));
  }

  /**
   * A status inquiry about the PAY of an UNKNOWN transaction (spec 7.4). Each inquiry is a new
   * request ({@code requestId} of {@code <txnId>:STATUS_QUERY:<epoch ms>}), since the adapter
   * answers a requestId once; the partner reference is the PAY's, so the partner finds it.
   */
  public PartnerRequest statusQuery(TransactionRecord rec, PartnerCall call, Instant now, Duration deadline) {
    String txnId = rec.txnId().toString();
    TxnRequest req = rec.request();
    return new PartnerRequest(
        1, call.family(), requestId(txnId, STATUS_QUERY + ":" + now.toEpochMilli()), txnId,
        req.correlationId(), now, now.plus(deadline),
        new PartnerRequest.Payload(
            STATUS_QUERY, req.txnType(), req.partner(), partnerTxnRef(txnId), req.amount(),
            req.debitParty(), req.creditParty(), rec.partnerRef(), req.attributes()));
  }

  /**
   * REVERSE of a partner call the core then refused to commit (ticket 96b). {@code requestId} is
   * {@link #reverseRequestId}: one key for every resend. The amount is the original's; {@code
   * originalPartnerRef} is the partner's reference of the PAY.
   */
  public PartnerRequest reverse(TransactionRecord rec, PartnerCall call, Instant now, Duration deadline) {
    String txnId = rec.txnId().toString();
    TxnRequest req = rec.request();
    return new PartnerRequest(
        1, call.family(), reverseRequestId(txnId), txnId, req.correlationId(), now, now.plus(deadline),
        new PartnerRequest.Payload(
            REVERSE, req.txnType(), req.partner(), partnerTxnRef(txnId), req.amount(), req.debitParty(),
            req.creditParty(), rec.partnerRef(), req.attributes()));
  }
}
