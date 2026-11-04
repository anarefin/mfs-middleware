package mfs.mw.portal.api.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import mfs.mw.portal.api.dto.Amount;
import mfs.mw.portal.api.dto.Party;
import mfs.mw.portal.api.dto.TxnRequest;
import mfs.mw.portal.api.entity.PendingChild;
import mfs.mw.portal.api.util.BulkIds;

/**
 * One batch row -> its child {@code BULK_DISBURSE} request on {@code mw.txn.request.v1} (ticket 34):
 * channel ORG_PORTAL, initiator the org, payer the org's payout wallet, payee the row's customer
 * wallet. Everything the orchestrator hashes for idempotency (parties, amount, attributes) and the
 * txnId and key come from the batch and the row only, so a resend is the same request.
 */
public final class ChildRequestMapper {

  public static final String CHANNEL = "ORG_PORTAL";
  public static final String TXN_TYPE = "BULK_DISBURSE";

  private ChildRequestMapper() {}

  public static TxnRequest toRequest(PendingChild c, Instant now) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("parentTxnId", c.parentTxnId().toString());
    attributes.put("batchId", c.batchId().toString());
    attributes.put("lineNo", Integer.toString(c.lineNo()));
    attributes.put("reference", c.reference());
    return new TxnRequest(
        1,
        BulkIds.childTxnId(c.batchId(), c.lineNo()).toString(),
        BulkIds.idempotencyKey(c.batchId(), c.lineNo()),
        correlationId(c),
        CHANNEL,
        TXN_TYPE,
        new Party("ORG", c.orgId(), null),
        new Party("ORG", c.orgWallet(), null),
        new Party("WALLET", c.msisdn(), "CUSTOMER"),
        new Amount(c.amountMinor(), c.currency()),
        "en",
        attributes,
        now);
  }

  /** One correlation id per batch, so every hop of every child can be found from the batch. */
  public static String correlationId(PendingChild c) {
    return "bulk-" + c.batchId();
  }
}
