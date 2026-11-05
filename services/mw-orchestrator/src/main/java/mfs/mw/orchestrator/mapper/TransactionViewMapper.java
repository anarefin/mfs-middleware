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
 * service (spec 18.3): party ids by party type, names to the initial. A revealed view (ticket 36,
 * spec 15.4: for mw-admin-api, which audits it) keeps MSISDN party ids whole; account ids, names and
 * free text stay masked. Only the bulk attributes are shown, scrubbed; device and other
 * metadata stay inside.
 */
public final class TransactionViewMapper {

  /** Attributes a viewer may see: the bulk batch links (ticket 34) and the SP's row reference. */
  static final List<String> SHOWN_ATTRIBUTES = List.of("parentTxnId", "batchId", "lineNo", "reference");

  /** The SP's free-text row reference is scrubbed (it could hold a number); the ids are ours. */
  static final List<String> SCRUBBED_ATTRIBUTES = List.of("reference");

  private TransactionViewMapper() {}

  public static TxnView toView(TransactionRecord rec) {
    return toView(rec, null);
  }

  /** @param role the viewing org's role ({@code INITIATOR} / {@code PAYEE}), null when unscoped */
  public static TxnView toView(TransactionRecord rec, String role) {
    return toView(rec, role, false);
  }

  /** @param reveal MSISDN party ids unmasked (ticket 36) */
  public static TxnView toView(TransactionRecord rec, String role, boolean reveal) {
    TxnRequest r = rec.request();
    String currency = r.amount() == null ? Amount.BDT : r.amount().currency();
    return new TxnView(
        role,
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
        masked(r.initiator(), reveal),
        masked(r.debitParty(), reveal),
        masked(r.creditParty(), reveal),
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
    return masked(p, false);
  }

  static Party masked(Party p, boolean reveal) {
    if (p == null) {
      return null;
    }
    String maskedId = MaskingPolicy.maskPartyId(p.type(), p.id());
    // revealed: only an id masked as an MSISDN goes out whole (accounts stay last 4)
    boolean msisdn = maskedId != null && maskedId.equals(MaskingPolicy.mask(SensitiveType.MSISDN, p.id()))
        && !maskedId.equals(MaskingPolicy.mask(SensitiveType.ACCOUNT, p.id()));
    return new Party(
        p.type(), reveal && msisdn ? p.id() : maskedId, MaskingPolicy.mask(SensitiveType.NAME, p.name()),
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
