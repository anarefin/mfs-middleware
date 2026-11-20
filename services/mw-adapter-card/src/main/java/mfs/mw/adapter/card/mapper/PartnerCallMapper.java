package mfs.mw.adapter.card.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import mfs.mw.adapter.card.dto.PartnerRequest;
import mfs.mw.adapter.card.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical card partner request to the framework's {@link PartnerCall} (ticket 118, FRS UC-13, EXT-14,
 * FL-07). The family serves ADD_MONEY_CARD with a CyberSource-style card acquirer: INQUIRY =
 * CREATE_SESSION (the hosted page session), PAY = AUTHORISE_CAPTURE, REVERSE = REFUND, STATUS_QUERY =
 * the status of our reference. The credit party is the customer's {@code WALLET}. Card data never enters
 * the middleware: the request carries only {@code attributes.sessionId} and the acquirer's
 * {@code attributes.transientToken}; a request whose attributes name a card number, CVV or expiry is
 * refused before anything is sent. The call's {@code input} is the document the record's mappings
 * read: {@code txnType}, {@code amount} ({@code valueMinor}, {@code currency}, {@code major}),
 * {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes}; the framework adds
 * {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "card";

  static final Set<String> OPERATIONS = Set.of("INQUIRY", "PAY", "REVERSE", "STATUS_QUERY");

  /** Attribute names that would carry card data (FL-07: PCI scope stays out). */
  static final Pattern CARD_DATA =
      Pattern.compile("(?i)^pan$|card_?(number|no)|cardnumber|cvv|cvc|cvn|expir");

  /** The txnTypes this adapter knows. */
  static final Set<String> TXN_TYPES = Set.of("ADD_MONEY_CARD");

  /** What stops this request being sent, or empty when it is a well-formed card request. */
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
        && p.attributes().keySet().stream().anyMatch(k -> CARD_DATA.matcher(k).find())) {
      return Optional.of("card data is not accepted");
    }
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if (p.creditParty() == null
        || !"WALLET".equals(p.creditParty().type())
        || p.creditParty().id() == null
        || p.creditParty().id().isBlank()) {
      return Optional.of("credit party must be a customer wallet");
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
