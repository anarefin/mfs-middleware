package mfs.mw.adapter.atm.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.adapter.atm.dto.PartnerRequest;
import mfs.mw.adapter.atm.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical atm partner request to the framework's {@link PartnerCall} (ticket 120a, FRS UC-17,
 * EXT-16, FL-11). The ATM switch calls us (inbound SOAP, {@code controller/AtmSoapEndpoint}); the
 * outbound side serves ATM_CASH_OUT and ATM_CASH_IN, today only STATUS_QUERY (the dispense status
 * the orchestrator asks after a missing ConfirmDispense, ticket 120b) in the dev seed; the generic
 * operations stay for a partner record that needs them. One party must be the customer's
 * {@code WALLET}. The one-time ATM token never travels in a request: attributes named like a token,
 * PIN or OTP are refused before anything is sent (FL-11). The call's {@code input} is the document
 * the record's mappings read: {@code txnType}, {@code amount} ({@code valueMinor}, {@code currency},
 * {@code major}), {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes}; the
 * framework adds {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "atm";

  static final Set<String> OPERATIONS = Set.of("INQUIRY", "PAY", "REVERSE", "STATUS_QUERY");

  /** Attribute names that would carry a secret (FL-11: the ATM token is never sent on). */
  static final Pattern SECRET_ATTRIBUTE = Pattern.compile("(?i)token|pin|otp|password");

  /** The txnTypes this adapter knows. */
  static final Set<String> TXN_TYPES = Set.of("ATM_CASH_OUT", "ATM_CASH_IN");

  /** What stops this request being sent, or empty when it is a well-formed atm request. */
  public Optional<String> problem(PartnerRequest request) {
    if (!FAMILY.equals(request.family())) {
      return Optional.of("unsupported family");
    }
    PartnerRequest.Payload p = request.payload();
    if (p == null) {
      return Optional.of("payload is required");
    }
    if (!OPERATIONS.contains(p.operation())) {
      return Optional.of("unsupported operation");
    }
    if (p.partner() == null || p.partner().partnerId() == null || p.partner().partnerId().isBlank()) {
      return Optional.of("partner is required");
    }
    if ("STATUS_QUERY".equals(p.operation())) {
      return Optional.empty(); // the partner is asked by our stable reference only
    }
    if (!TXN_TYPES.contains(p.txnType())) {
      return Optional.of("unsupported txnType");
    }
    if (p.attributes() != null
        && p.attributes().keySet().stream().anyMatch(k -> SECRET_ATTRIBUTE.matcher(k).find())) {
      return Optional.of("secret attributes are not accepted");
    }
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if (!isWallet(p.debitParty()) && !isWallet(p.creditParty())) {
      return Optional.of("one party must be a customer wallet");
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

  private static boolean isWallet(PartnerRequest.Party party) {
    return party != null && "WALLET".equals(party.type()) && party.id() != null && !party.id().isBlank();
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
