package mfs.mw.orchestrator.service.saga;

import java.util.Map;
import java.util.Optional;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.TxnRequest;
import mfs.mw.orchestrator.exception.ErrorCodes;
import mfs.mw.orchestrator.service.BillerRules.Rule;
import org.springframework.stereotype.Component;

/**
 * Bill payment (spec 2.1, WP15): validate, hold the payer's funds, pay the bill through
 * mw-adapter-billpay (the aggregator in {@code partner.partnerId}, the biller in
 * {@code partner.billerCode}, the bill number as the {@code BILLER_ACCOUNT} credit party), then
 * commit the hold on success or release it on a refusal. Same steps as TOPUP.
 *
 * <p>The biller's amount rule (ticket 113c, FRS FL-04/FL-05) is checked at intake, before a hold:
 * {@link #amountRefusal}.
 */
@Component
public class BillPayFlow implements Flow {

  static final PartnerCall BILLPAY = new PartnerCall("billpay", "mw.partner.request.billpay.v1");

  /** The fetched bill the channel stored and attached (never a client field); same key as a postpaid top-up. */
  static final String FETCHED = "billOutstandingMinor";

  @Override
  public String txnType() {
    return "BILL_PAY";
  }

  @Override
  public boolean needsHold() {
    return true;
  }

  @Override
  public Optional<PartnerCall> partnerCall() {
    return Optional.of(BILLPAY);
  }

  /**
   * A refusal ({@code MW-VAL-032}) of a bill amount the biller's rule does not allow, or null.
   * FIXED: the amount equals the fetched bill ({@code attributes.billOutstandingMinor}); without a
   * fetched bill attached the aggregator stays the judge. MINIMUM: at least the minimum. OPEN:
   * within the minimum and the maximum, both inclusive, each optional. A biller with no rule in the
   * projection is not checked here.
   */
  public static ErrorInfo amountRefusal(TxnRequest req, Optional<Rule> rule) {
    if (rule.isEmpty() || req.amount() == null) {
      return null;
    }
    Rule r = rule.get();
    long amount = req.amount().valueMinor();
    return switch (r.amountRule()) {
      case "FIXED" -> {
        Long fetched = fetched(req.attributes());
        yield fetched == null || fetched == amount
            ? null
            : refusal("The amount must equal the bill amount", "পরিমাণটি বিলের পরিমাণের সমান হতে হবে");
      }
      case "MINIMUM" ->
          r.minAmountMinor() != null && amount < r.minAmountMinor()
              ? refusal("The amount is below this biller's minimum", "পরিমাণটি এই বিলারের সর্বনিম্ন পরিমাণের কম")
              : null;
      case "OPEN" ->
          (r.minAmountMinor() != null && amount < r.minAmountMinor())
                  || (r.maxAmountMinor() != null && amount > r.maxAmountMinor())
              ? refusal("The amount is outside this biller's range", "পরিমাণটি এই বিলারের সীমার বাইরে")
              : null;
      default -> null;
    };
  }

  private static ErrorInfo refusal(String message, String messageBn) {
    return new ErrorInfo(ErrorCodes.VAL_BILL_AMOUNT, message, messageBn, false);
  }

  private static Long fetched(Map<String, String> attributes) {
    String raw = attributes == null ? null : attributes.get(FETCHED);
    return raw == null || !raw.matches("[0-9]{1,15}") ? null : Long.valueOf(raw);
  }
}
