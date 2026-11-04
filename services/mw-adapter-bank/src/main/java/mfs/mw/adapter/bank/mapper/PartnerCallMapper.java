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
 * Canonical bank partner request (PAY, STATUS_QUERY) to the framework's {@link PartnerCall}. Today
 * the bank family serves C2W / ADD_MONEY_BANK (ticket 23): PAY = debit the customer's bank
 * account ({@code debitParty} {@code BANK_ACCOUNT}) for money into the wallet ({@code creditParty}
 * {@code WALLET}). The partner's wire format is not here: it is the JSONata request mapping of the
 * partner config. The call's {@code input} is the document that mapping reads: {@code txnType},
 * {@code amount} ({@code valueMinor}, {@code currency}, {@code major} as a decimal string such as
 * {@code "20.00"}), {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes};
 * the framework adds {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "bank";

  /** A bank account number (same rule as the app API). */
  public static final Pattern ACCOUNT_NUMBER = Pattern.compile("^[0-9]{6,20}$");

  /** The txnTypes whose PAY this adapter knows how to send. */
  static final Set<String> PAY_TYPES = Set.of("ADD_MONEY_BANK", "C2W");

  /** What stops this request being sent, or empty when it is a well-formed bank request. */
  public Optional<String> problem(PartnerRequest request) {
    if (!FAMILY.equals(request.family())) {
      return Optional.of("unsupported family");
    }
    PartnerRequest.Payload p = request.payload();
    if (p == null) {
      return Optional.of("payload is required");
    }
    if (!"PAY".equals(p.operation()) && !"STATUS_QUERY".equals(p.operation())) {
      return Optional.of("unsupported operation");
    }
    if (p.partner() == null || p.partner().partnerId() == null || p.partner().partnerId().isBlank()) {
      return Optional.of("partner is required");
    }
    if ("STATUS_QUERY".equals(p.operation())) {
      return Optional.empty(); // the partner is asked by our stable reference only
    }
    if (!PAY_TYPES.contains(p.txnType())) {
      return Optional.of("unsupported txnType");
    }
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if (p.debitParty() == null
        || !"BANK_ACCOUNT".equals(p.debitParty().type())
        || p.debitParty().id() == null
        || !ACCOUNT_NUMBER.matcher(p.debitParty().id()).matches()) {
      return Optional.of("debit party must be a bank account");
    }
    if (p.creditParty() == null
        || !"WALLET".equals(p.creditParty().type())
        || p.creditParty().id() == null
        || p.creditParty().id().isBlank()) {
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
