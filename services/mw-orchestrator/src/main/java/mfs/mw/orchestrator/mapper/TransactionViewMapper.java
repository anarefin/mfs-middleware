package mfs.mw.orchestrator.mapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.dto.Amount;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.Party;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.dto.TxnView;
import mfs.mw.orchestrator.entity.TransactionRecord;
import mfs.mw.orchestrator.util.MaskingPolicy;
import mfs.mw.orchestrator.util.SensitiveType;

/**
 * Transaction row -> query view (ticket 35). Masking happens here, before anything leaves the
 * service (spec 18.3, 15.4: unmasked views are a later, audited admin feature): party ids by party
 * type, names to the initial. Only the bulk attributes are shown, scrubbed; device and other
 * metadata stay inside.
 */
public final class TransactionViewMapper {

  /** Attributes a viewer may see: the bulk batch links (ticket 34) and the SP's row reference. */
  static final List<String> SHOWN_ATTRIBUTES = List.of("parentTxnId", "batchId", "lineNo", "reference");

  /** The SP's free-text row reference is scrubbed (it could hold a number); the ids are ours. */
  static final List<String> SCRUBBED_ATTRIBUTES = List.of("reference");

  private TransactionViewMapper() {}

  public static TxnView toView(TransactionRecord rec) {
    TxnRequest r = rec.request();
    String currency = r.amount() == null ? Amount.BDT : r.amount().currency();
    return new TxnView(
        rec.txnId().toString(),
        r.txnType(),
        r.channel(),
        rec.status().name(),
        rec.previousStatus() == null ? null : rec.previousStatus().name(),
        rec.createdAt(),
        rec.updatedAt(),
        r.correlationId(),
        r.amount(),
        rec.feeMinor() == null ? null : new Amount(rec.feeMinor(), currency),
        masked(r.initiator()),
        masked(r.debitParty()),
        masked(r.creditParty()),
        r.partner(),
        rec.partnerRef(),
        rec.coreRef(),
        r.originalTxnId(),
        rec.reversalTxnId() == null ? null : rec.reversalTxnId().toString(),
        scrubbed(rec.error()),
        shownAttributes(r.attributes()));
  }

  /** Error text may quote a partner's message: scrubbed like free text. */
  private static ErrorInfo scrubbed(ErrorInfo e) {
    return e == null
        ? null
        : new ErrorInfo(e.code(), MaskingPolicy.scrub(e.message()), MaskingPolicy.scrub(e.messageBn()), e.retryable());
  }

  static Party masked(Party p) {
    if (p == null) {
      return null;
    }
    return new Party(
        p.type(), MaskingPolicy.maskPartyId(p.type(), p.id()), MaskingPolicy.mask(SensitiveType.NAME, p.name()),
        p.walletType());
  }

  private static Map<String, String> shownAttributes(Map<String, String> attributes) {
    if (attributes == null) {
      return null;
    }
    Map<String, String> shown = new LinkedHashMap<>();
    for (String key : SHOWN_ATTRIBUTES) {
      String v = attributes.get(key);
      if (v != null) {
        shown.put(key, SCRUBBED_ATTRIBUTES.contains(key) ? MaskingPolicy.scrub(v) : v);
      }
    }
    return shown.isEmpty() ? null : shown;
  }
}
