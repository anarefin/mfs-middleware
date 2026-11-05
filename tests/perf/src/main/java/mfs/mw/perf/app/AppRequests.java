package mfs.mw.perf.app;

import java.util.random.RandomGenerator;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bodies of {@code POST /app/v1/transactions} for each app flow of the mix (contracts/api/
 * mw-app-gateway/app.v1.openapi.yaml, as the e2e suite builds them), as the exact JSON text that is
 * signed and sent. Amounts are whole taka, so no simulator magic fraction (.13 timeout, .55
 * malformed, .77 5xx, .99 ambiguous) is ever hit by accident.
 */
public final class AppRequests {

  /** P2P, cash-in, cash-out, merchant payment, top-up: 10 taka. */
  public static final long AMOUNT_MINOR = 1000;
  /** Add money: 500 taka, more than a customer pays out per run on average (README.md). */
  public static final long ADD_MONEY_MINOR = 50000;
  /** The OCS simulator refuses every MSISDN ending in 999 (the run's intended business failure). */
  public static final String REFUSED_TOPUP_MSISDN = "01799000999";
  public static final String TOPUP_OPERATOR = "gp";
  public static final String BILLER = "DESCO";
  public static final String BANK = "c2wbank";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final PinCipher pin;
  private final String plainPin;

  /** @param plainPin the seeded wallets' fake PIN (mw-core-mock {@code mw.coremock.seed.pin}) */
  public AppRequests(PinCipher pin, String plainPin) {
    this.pin = pin;
    this.plainPin = plainPin;
  }

  public String p2p(String toWallet) {
    return text(base("P2P", toWallet, AMOUNT_MINOR));
  }

  public String cashIn(String customerWallet) {
    return text(base("CASH_IN", customerWallet, AMOUNT_MINOR));
  }

  public String cashOut(String agentWallet) {
    return text(base("CASH_OUT", agentWallet, AMOUNT_MINOR));
  }

  public String merchantPay(String merchantWallet) {
    return text(base("MERCHANT_PAY", merchantWallet, AMOUNT_MINOR));
  }

  public String topUp(String msisdn) {
    ObjectNode b = base("TOPUP", msisdn, AMOUNT_MINOR);
    ((ObjectNode) b.get("creditParty")).put("type", "MSISDN");
    b.putObject("partner").put("partnerId", TOPUP_OPERATOR).put("productCode", "PREPAID");
    return text(b);
  }

  public String billPay(String billNumber, long amountMinor) {
    ObjectNode b = base("BILL_PAY", billNumber, amountMinor);
    ((ObjectNode) b.get("creditParty")).put("type", "BILLER_ACCOUNT");
    b.putObject("partner").put("billerCode", BILLER);
    return text(b);
  }

  public String addMoneyBank(String bankAccount) {
    ObjectNode b = base("ADD_MONEY_BANK", "unused", ADD_MONEY_MINOR);
    b.remove("creditParty");
    b.putObject("debitParty").put("type", "BANK_ACCOUNT").put("id", bankAccount);
    b.putObject("partner").put("partnerId", BANK);
    return text(b);
  }

  /** {@code {"billNumber": ...}} of {@code POST /app/v1/billers/{code}/fetch-bill}. */
  public static String fetchBill(String billNumber) {
    return text(JSON.createObjectNode().put("billNumber", billNumber));
  }

  /**
   * A DESCO bill number the aggregator simulator serves normally: 7 digits ending 001..050 (never
   * the magic 000 already paid, 404 unknown, 999 refused), so its due is 101..150 taka.
   */
  public static String billNumber(RandomGenerator random) {
    return random.nextInt(1000, 10000) + String.format("%03d", random.nextInt(1, 51));
  }

  /** The simulator's amount due: 100 taka plus the last three digits of the bill number, in taka. */
  public static long dueMinor(String billNumber) {
    return (100 + Long.parseLong(billNumber.substring(billNumber.length() - 3))) * 100;
  }

  /** A linked bank account the c2w bank simulator accepts (never ending in 999). */
  public static String bankAccount(String wallet) {
    return "20" + wallet.substring(3);
  }

  private ObjectNode base(String txnType, String creditPartyId, long amountMinor) {
    ObjectNode body = JSON.createObjectNode();
    body.put("txnType", txnType);
    body.putObject("creditParty").put("id", creditPartyId);
    body.putObject("amount").put("valueMinor", amountMinor).put("currency", "BDT");
    body.putObject("encryptedPin").put("keyId", pin.keyId()).put("ciphertext", pin.encrypt(plainPin));
    return body;
  }

  private static String text(ObjectNode body) {
    return JSON.writeValueAsString(body);
  }
}
