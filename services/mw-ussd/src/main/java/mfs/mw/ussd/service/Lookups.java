package mfs.mw.ussd.service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import mfs.mw.ussd.dto.Bill;
import mfs.mw.ussd.dto.Biller;
import mfs.mw.ussd.dto.ErrorEnvelope;
import mfs.mw.ussd.dto.ErrorInfo;
import mfs.mw.ussd.dto.LookupOutcome;
import mfs.mw.ussd.exception.BillPayRefusedException;
import mfs.mw.ussd.exception.ErrorCodes;
import mfs.mw.ussd.mapper.TxnRequestMapper;
import mfs.mw.ussd.util.UssdText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only lookups a node runs ({@code lookup}, arguments from the node's {@code mapping}:
 * argument name to an input name, or a literal {@code =VALUE}); their values are added to the
 * session's inputs. {@code bill} fetches a bill ({@code billerCode}, {@code billNumber}) and adds
 * {@code amount} (taka, {@code 667.00}), {@code partnerId} and {@code billOutstandingMinor} (the
 * fetched amount in poisha, which a BILL_PAY carries as {@code attributes.billOutstandingMinor} so
 * the orchestrator checks a FIXED biller's amount; ticket 113b); a biller the catalogue disabled is
 * refused with its bn/en maintenance text before any fetch. {@code charges} quotes the fee
 * (WAL-04) for {@code txnType} and {@code amount} and adds {@code fee} in taka. {@code statement}
 * and {@code remit} add the screen text ({@code lines}, {@code status}).
 */
@Component
public class Lookups {

  public static final String BILL = "bill";
  public static final String CHARGES = "charges";
  public static final String STATEMENT = "statement";
  public static final String REMIT = "remit";
  public static final String BILLER_CODE = "billerCode";
  public static final String BILL_NUMBER = "billNumber";
  /** The fetched bill in poisha, added by {@code bill} (session input, never typed by the subscriber). */
  public static final String BILL_OUTSTANDING = "billOutstandingMinor";

  /** Lookup name to its required arguments. */
  public static final Map<String, Set<String>> ARGUMENTS =
      Map.of(
          BILL, Set.of(BILLER_CODE, BILL_NUMBER),
          CHARGES, Set.of("txnType", "amount"),
          STATEMENT, Set.of("walletId"),
          REMIT, Set.of("reference"));

  private static final Logger log = LoggerFactory.getLogger(Lookups.class);
  private static final java.time.ZoneId DHAKA = java.time.ZoneId.of("Asia/Dhaka");
  private static final java.time.format.DateTimeFormatter BILL_MONTH =
      java.time.format.DateTimeFormatter.ofPattern("yyyyMM");

  private final BillDirectory bills;
  private final ChargeQuote charges;
  private final AccountReads accounts;

  public Lookups(BillDirectory bills, ChargeQuote charges, AccountReads accounts) {
    this.bills = bills;
    this.charges = charges;
    this.accounts = accounts;
  }

  /** Never throws for a known lookup: a refusal carries the partner's error, anything else is UNAVAILABLE. */
  public LookupOutcome run(String lookup, Map<String, String> args) {
    if (CHARGES.equals(lookup)) {
      return charges(args);
    }
    if (STATEMENT.equals(lookup)) {
      return text("lines", () -> accounts.statement(args.get("walletId"), lang(args)), lookup);
    }
    if (REMIT.equals(lookup)) {
      return text("status", () -> accounts.remitStatus(args.get("reference"), lang(args)), lookup);
    }
    if (!BILL.equals(lookup)) {
      throw new IllegalArgumentException("unknown lookup " + lookup);
    }
    String code = args.get(BILLER_CODE);
    try {
      Biller biller =
          bills.billers().stream()
              .filter(b -> b.code().equals(code))
              .findFirst()
              .orElseThrow(() -> new IllegalStateException("biller not in the catalogue"));
      if (!biller.isEnabled()) {
        return LookupOutcome.refused(new ErrorInfo(
            "MW-SYS-BILLER_DISABLED",
            biller.maintenanceEn() == null ? "This biller is not available now" : biller.maintenanceEn(),
            biller.maintenanceBn() == null ? "এই বিলার এখন পাওয়া যাচ্ছে না" : biller.maintenanceBn(),
            false));
      }
      String partnerId = biller.partnerId();
      // A handset has no bill-month screen: a biller that needs one is asked for the current month's
      // bill (Asia/Dhaka), the bill a subscriber pays over USSD (ticket 113b).
      Bill bill = Boolean.TRUE.equals(biller.billMonthRequired())
          ? bills.fetch(code, args.get(BILL_NUMBER), BILL_MONTH.format(java.time.YearMonth.now(DHAKA)))
          : bills.fetch(code, args.get(BILL_NUMBER));
      if (bill.amount() == null || bill.amount().valueMinor() == null || bill.amount().valueMinor() <= 0) {
        return LookupOutcome.refused(
            new ErrorInfo(ErrorCodes.VAL_INVALID, "Nothing to pay on this bill", "এই বিলে দেওয়ার কিছু নেই", false));
      }
      String taka = BigDecimal.valueOf(bill.amount().valueMinor()).movePointLeft(2).toPlainString();
      return LookupOutcome.found(Map.of(
          "amount", taka, "partnerId", partnerId, BILL_OUTSTANDING, Long.toString(bill.amount().valueMinor())));
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

  /** Fee in taka ({@code 5.00}) for the confirm screen. A quote that cannot be had ends the session. */
  private LookupOutcome charges(Map<String, String> args) {
    try {
      String type = args.get("customerType");
      ChargeQuote.Quoted quoted =
          charges.quote(args.get("txnType"), TxnRequestMapper.minor(args.get("amount")), type == null ? "CUSTOMER" : type);
      return LookupOutcome.found(Map.of("fee", UssdText.taka(quoted.feeMinor())));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "ussd.lookup.unavailable")
          .addKeyValue("lookup", CHARGES)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("charge quote unavailable");
      return LookupOutcome.unavailable();
    }
  }

  private LookupOutcome text(String key, Supplier<String> read, String lookup) {
    try {
      String value = read.get();
      if (value == null || value.isBlank()) {
        return LookupOutcome.unavailable();
      }
      return LookupOutcome.found(Map.of(key, value));
    } catch (RuntimeException e) {
      log.atWarn()
          .addKeyValue("event", "ussd.lookup.unavailable")
          .addKeyValue("lookup", lookup)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("lookup unavailable");
      return LookupOutcome.unavailable();
    }
  }

  private static String lang(Map<String, String> args) {
    String language = args.get("language");
    return language == null || language.isBlank() ? "bn" : language;
  }
}
