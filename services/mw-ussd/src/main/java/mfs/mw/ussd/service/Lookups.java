package mfs.mw.ussd.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.LookupOutcome;
import mfs.mw.ussd.exception.BillPayRefusedException;
import mfs.mw.ussd.exception.ErrorCodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only lookups an INPUT node runs once its input is valid ({@code lookup}, arguments from the
 * node's {@code mapping}: argument name to input name); their values are added to the session's
 * inputs. So far {@code bill}: the bill fetch of BILL_PAY, arguments {@code billerCode} and {@code
 * billNumber}, values {@code amount} (taka, {@code 667.00}) and {@code partnerId} (the aggregator
 * serving the biller).
 */
@Component
public class Lookups {

  public static final String BILL = "bill";
  public static final String BILLER_CODE = "billerCode";
  public static final String BILL_NUMBER = "billNumber";

  /** Lookup name to its required arguments. */
  public static final Map<String, Set<String>> ARGUMENTS = Map.of(BILL, Set.of(BILLER_CODE, BILL_NUMBER));

  private static final Logger log = LoggerFactory.getLogger(Lookups.class);

  private final BillDirectory bills;

  public Lookups(BillDirectory bills) {
    this.bills = bills;
  }

  /** Never throws for a known lookup: a refusal carries the partner's error, anything else is UNAVAILABLE. */
  public LookupOutcome run(String lookup, Map<String, String> args) {
    if (!BILL.equals(lookup)) {
      throw new IllegalArgumentException("unknown lookup " + lookup);
    }
    String code = args.get(BILLER_CODE);
    try {
      Bill bill = bills.fetch(code, args.get(BILL_NUMBER));
      String partnerId =
          bills.billers().stream()
              .filter(b -> b.code().equals(code))
              .map(Biller::partnerId)
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("biller not in the catalogue"));
      if (bill.amount() == null || bill.amount().valueMinor() == null || bill.amount().valueMinor() <= 0) {
        return LookupOutcome.refused(
            new ErrorInfo(ErrorCodes.VAL_INVALID, "Nothing to pay on this bill", "এই বিলে দেওয়ার কিছু নেই", false));
      }
      String taka = BigDecimal.valueOf(bill.amount().valueMinor()).movePointLeft(2).toPlainString();
      return LookupOutcome.found(Map.of("amount", taka, "partnerId", partnerId));
    } catch (BillPayRefusedException e) {
      ErrorEnvelope env = e.envelope();
      log.atInfo()
          .addKeyValue("event", "ussd.lookup.refused")
          .addKeyValue("lookup", lookup)
          .addKeyValue("errorCode", env.code())
          .log("bill lookup refused");
      return LookupOutcome.refused(new ErrorInfo(env.code(), env.message(), env.messageBn(), env.retryable()));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "ussd.lookup.unavailable")
          .addKeyValue("lookup", lookup)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("bill lookup unavailable");
      return LookupOutcome.unavailable();
    }
  }
}
