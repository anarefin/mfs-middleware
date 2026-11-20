package mfs.mw.orchestrator.service;

import java.util.List;
import java.util.Map;
import mfs.mw.orchestrator.config.TopupProperties;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.service.routing.RoutingCatalog;

/**
 * What a top-up may charge (ticket 112b). Postpaid: any amount from the minimum due through the
 * outstanding bill, both inclusive, taken from the bill the preview stored. Prepaid: an amount on
 * the operator's denomination list, when that operator has one. Either refusal is before a hold.
 */
final class TopupAmount {

  static final String POSTPAID = "POSTPAID";
  static final String PREPAID = "PREPAID";
  /** The stored bill, set by the channel from the quote. Not a client field. */
  static final String OUTSTANDING = "billOutstandingMinor";
  static final String MINIMUM = "billMinimumMinor";

  private TopupAmount() {}

  /** A refusal of a confirmed top-up, or null when the amount may be held. */
  static ErrorInfo confirm(TxnRequest req, RoutingCatalog routing, TopupProperties props) {
    String product = req.partner() == null ? null : req.partner().productCode();
    if (POSTPAID.equals(product)) {
      return postpaid(req.amount().valueMinor(), req.attributes());
    }
    if (PREPAID.equals(product)) {
      String msisdn = req.creditParty() == null ? null : req.creditParty().id();
      return prepaid(msisdn, req.amount().valueMinor(), routing, props);
    }
    return null;
  }

  /** A prepaid amount off the operator's list, or null when there is no list or the amount is on it. */
  static ErrorInfo prepaid(String msisdn, long amount, RoutingCatalog routing, TopupProperties props) {
    List<Long> allowed = props.denominationsOf(routing.operatorOf(msisdn).orElse(null));
    if (allowed.isEmpty() || allowed.contains(amount)) {
      return null;
    }
    return new ErrorInfo(
        ErrorCodes.VAL_TOPUP_DENOMINATION,
        "The amount is not a denomination of this operator",
        "এই অপারেটরের এই পরিমাণের রিচার্জ নেই",
        false);
  }

  /**
   * The stored bill must be present, and the amount must sit from the minimum through the
   * outstanding, both inclusive.
   */
  static ErrorInfo postpaid(long amount, Map<String, String> attributes) {
    Long outstanding = attr(attributes, OUTSTANDING);
    Long minimum = attr(attributes, MINIMUM);
    if (outstanding == null || minimum == null || minimum < 0 || outstanding < minimum) {
      return new ErrorInfo(
          ErrorCodes.VAL_TOPUP_BILL,
          "Preview the postpaid bill before paying",
          "পেমেন্টের আগে পোস্টপেইড বিল দেখে নিন",
          false);
    }
    if (amount < minimum || amount > outstanding) {
      return new ErrorInfo(
          ErrorCodes.VAL_TOPUP_BILL,
          "The amount must be from the minimum due through the outstanding bill",
          "পরিমাণটি বকেয়া বিলের সর্বনিম্ন থেকে বকেয়া পর্যন্ত হতে হবে",
          false);
    }
    return null;
  }

  private static Long attr(Map<String, String> attributes, String key) {
    if (attributes == null) {
      return null;
    }
    String raw = attributes.get(key);
    if (raw == null || !raw.matches("[0-9]{1,15}")) {
      return null;
    }
    return Long.valueOf(raw);
  }
}
