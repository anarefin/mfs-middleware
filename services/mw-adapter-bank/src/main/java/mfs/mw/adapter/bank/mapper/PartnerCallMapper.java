package mfs.mw.adapter.bank.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical bank partner request (PAY, INQUIRY, STATUS_QUERY, REVERSE) to the framework's {@link
 * PartnerCall}. C2W / ADD_MONEY_BANK (ticket 23): PAY = debit the customer's bank account ({@code
 * debitParty} {@code BANK_ACCOUNT}) for money into the wallet ({@code creditParty} {@code WALLET}).
 * W2B (ticket 117, FRS FL-09): PAY = credit the bank account ({@code creditParty} {@code
 * BANK_ACCOUNT}) from the wallet ({@code debitParty} {@code WALLET}). INQUIRY (ticket 117, FRS UC-14,
 * AVL): validate the one {@code BANK_ACCOUNT} party, no amount; the bank answers the holder name.
 * The partner's wire format is not here: it is the JSONata request mapping of the partner config.
 * The call's {@code input} is the document that mapping reads: {@code txnType}, {@code amount}
 * ({@code valueMinor}, {@code currency}, {@code major} as a decimal string such as {@code "20.00"}),
 * {@code debitParty}, {@code creditParty}, {@code account} (the bank account of either side), {@code
 * direction} ({@code DEBIT} for C2W, {@code CREDIT} for W2B; PAY and REVERSE only, so one profile
 * mapping serves both), {@code partner}, {@code attributes}; the framework adds {@code reference},
 * {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "bank";

  /** Wallet to bank (ticket 117): the bank account is credited. */
  public static final String W2B = "W2B";

  /** A bank account number (same rule as the app API). */
  public static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{6,20}$");

  /** The txnTypes whose PAY this adapter knows how to send. */
  static final Set<String> PAY_TYPES = Set.of("ADD_MONEY_BANK", "C2W", W2B);

  private static final Set<String> OPERATIONS = Set.of("PAY", "INQUIRY", "STATUS_QUERY", "REVERSE");

  /** What stops this request being sent, or empty when it is a well-formed bank request. */
  public Optional<String> problem(PartnerRequest request) {
    if (!FAMILY.equals(request.family())) {
      return Optional.of("unsupported family");
    }
    PartnerRequest.Payload p = request.payload();
    if (p == null) {
      return Optional.of("payload is required");
    }
    if (p.operation() == null || !OPERATIONS.contains(p.operation())) {
      return Optional.of("unsupported operation");
    }
    if (p.partner() == null || p.partner().partnerId() == null || p.partner().partnerId().isBlank()) {
      return Optional.of("partner is required");
    }
    if ("STATUS_QUERY".equals(p.operation())) {
      return Optional.empty(); // the partner is asked by our stable reference only
    }
    if ("INQUIRY".equals(p.operation())) {
      // AVL: the bank checks the account and answers its holder; nothing moves
      return account(p) != null ? Optional.empty() : Optional.of("an account validation needs a bank account");
    }
    if ("REVERSE".equals(p.operation())) {
      // ticket 97: undoes the applied call of the same txnId (our stable reference) for its amount;
      // the engine adds the reversal reference reverse:<txnId>
      return p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0
          ? Optional.of("amount must be positive")
          : Optional.empty();
    }
    if (!PAY_TYPES.contains(p.txnType())) {
      return Optional.of("unsupported txnType");
    }
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if (W2B.equals(p.txnType())) {
      if (!isWallet(p.debitParty())) {
        return Optional.of("debit party must be a wallet");
      }
      return bankAccount(p.creditParty()) == null
          ? Optional.of("credit party must be a bank account")
          : Optional.empty();
    }
    if (bankAccount(p.debitParty()) == null) {
      return Optional.of("debit party must be a bank account");
    }
    if (!isWallet(p.creditParty())) {
      return Optional.of("credit party must be a wallet");
    }
    return Optional.empty();
  }

  /** Call {@link #problem} first. {@code reference} is our stable partner reference. */
  public PartnerCall toCall(PartnerRequest request, String reference) {
    PartnerRequest.Payload p = request.payload();
    Map<String, Object> input = new LinkedHashMap<>();
    put(input, "txnType", p.txnType());
    if (p.amount() != null && p.amount().valueMinor() != null) {
      Map<String, Object> amount = new LinkedHashMap<>();
      amount.put("valueMinor", p.amount().valueMinor());
      put(amount, "currency", p.amount().currency());
      amount.put("major", BigDecimal.valueOf(p.amount().valueMinor(), 2).toPlainString());
      input.put("amount", amount);
    }
    put(input, "debitParty", party(p.debitParty()));
    put(input, "creditParty", party(p.creditParty()));
    put(input, "account", account(p));
    if ("PAY".equals(p.operation()) || "REVERSE".equals(p.operation())) {
      input.put("direction", W2B.equals(p.txnType()) ? "CREDIT" : "DEBIT");
    }
    if (p.partner() != null) {
      Map<String, Object> partner = new LinkedHashMap<>();
      put(partner, "partnerId", p.partner().partnerId());
      put(partner, "productCode", p.partner().productCode());
      input.put("partner", partner);
    }
    put(input, "originalPartnerRef", p.originalPartnerRef());
    put(input, "attributes", p.attributes());
    return new PartnerCall(
        p.partner().partnerId(),
        p.operation(),
        p.txnType(),
        request.txnId(),
        request.correlationId(),
        reference,
        input);
  }

  /** The bank account of the request: the credit side's (W2B, AVL) else the debit side's (C2W). */
  private static String account(PartnerRequest.Payload p) {
    String credit = bankAccount(p.creditParty());
    return credit != null ? credit : bankAccount(p.debitParty());
  }

  private static boolean isWallet(PartnerRequest.Party party) {
    return party != null && "WALLET".equals(party.type()) && party.id() != null && !party.id().isBlank();
  }

  /** The party's account number when it is a well-formed bank account, else null. */
  private static String bankAccount(PartnerRequest.Party party) {
    return party != null && "BANK_ACCOUNT".equals(party.type()) && party.id() != null
            && ACCOUNT_NUMBER.matcher(party.id()).matches()
        ? party.id()
        : null;
  }

  private static Map<String, Object> party(PartnerRequest.Party party) {
    if (party == null) {
      return null;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    put(out, "type", party.type());
    put(out, "id", party.id());
    put(out, "name", party.name());
    put(out, "walletType", party.walletType());
    return out;
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }
}
