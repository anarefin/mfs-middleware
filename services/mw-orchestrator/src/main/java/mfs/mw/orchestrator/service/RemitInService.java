package mfs.mw.orchestrator.service;

import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.PartnerInbound;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.service.saga.RemitInFlow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Starts the REMIT_IN transaction of an inbound remittance (ticket 119b, FRS 5.3 inbound template):
 * the adapter checked the signature, the fields and the partner reference; this turns the
 * REMITTANCE into a {@code mw.txn.request.v1}-shaped request under the adapter's txnId and hands it
 * to intake ({@link TransactionService#accept}), so idempotency, the saga, its status and audit are
 * the same as for every other flow. Called inside {@link PartnerInboundService}'s inbox step, so a
 * re-delivered remittance (same inboundId) starts nothing.
 *
 * <p>AML screen (FRS 5.3 step 4): {@code mw.remit.aml-screen} is {@code off} (pass-through) until
 * mw-compliance exists (ticket 125); {@code on} is refused at start rather than silently passing
 * remittances unscreened. The sender's name is used only here and never stored on the transaction.
 */
@Service
public class RemitInService {

  /** Intake channel of partner-initiated money (contract Channel). */
  static final String CHANNEL = "PARTNER_API";

  private static final Logger log = LoggerFactory.getLogger(RemitInService.class);

  private final TransactionService transactions;

  public RemitInService(TransactionService transactions, @Value("${mw.remit.aml-screen:off}") String amlScreen) {
    if (!"off".equals(amlScreen)) {
      throw new IllegalStateException(
          "mw.remit.aml-screen=" + amlScreen + " is not supported yet: the screen calls mw-compliance (ticket 125)");
    }
    this.transactions = transactions;
  }

  /** Starts the transaction; throws (retry topics, then the DLQ) when the event lacks money fields. */
  public void accept(PartnerInbound in) {
    PartnerInbound.Payload p = in.payload();
    if (p.amount() == null || p.creditParty() == null || in.partnerRef() == null) {
      throw new IllegalArgumentException("remittance lacks amount, credit party or partner reference");
    }
    Party partner = new Party("BANK_ACCOUNT", in.partnerId(), null, null);
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("partnerRef", in.partnerRef());
    if (p.attributes() != null && p.attributes().get("senderCountry") != null) {
      attributes.put("senderCountry", p.attributes().get("senderCountry"));
    }
    TxnRequest req =
        new TxnRequest(
            1, in.txnId(), "remit:" + in.partnerId() + ":" + in.partnerRef(), in.correlationId(), CHANNEL,
            RemitInFlow.TXN_TYPE, partner, partner, p.creditParty(), p.amount(), null, null, null, null,
            attributes, in.receivedAt());
    log.atInfo()
        .addKeyValue("event", "remit.in.accepted")
        .addKeyValue("txnId", in.txnId())
        .addKeyValue("partnerId", in.partnerId())
        .log("inbound remittance handed to intake");
    transactions.accept(req, null);
  }
}
