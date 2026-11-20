package mfs.mw.adapter.merchant.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.adapter.merchant.dto.PartnerRequest;
import mfs.mw.adapter.merchant.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical merchant partner request to the framework's {@link PartnerCall} (ticket 115, FRS UC-11, EXT-11).
 * The family serves MERCHANT_PAY with an external merchant system: INQUIRY = VALIDATE (the merchant
 * exists, is active and the till id is valid; before the hold), PAY = NOTIFY (after COMMIT, best effort),
 * REVERSE = REFUND, STATUS_QUERY = the status inquiry of our reference. The credit party is the
 * {@code MERCHANT}; {@code attributes.tillId} names the till. The partner's wire format is not here: it
 * is the record's mappings (SOAP stylesheets). The call's {@code input} is the document those mappings
 * read: {@code txnType}, {@code amount} ({@code valueMinor}, {@code currency}, {@code major}),
 * {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes}; the framework adds
 * {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "merchant";

  static final Set<String> OPERATIONS = Set.of("INQUIRY", "PAY", "REVERSE", "STATUS_QUERY");

  /** The txnTypes this adapter knows. */
  static final Set<String> TXN_TYPES = Set.of("MERCHANT_PAY");

  /** What stops this request being sent, or empty when it is a well-formed merchant request. */
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
    if (!"INQUIRY".equals(p.operation())
        && (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0)) {
      return Optional.of("amount must be positive");
    }
    if (p.creditParty() == null
        || !"MERCHANT".equals(p.creditParty().type())
        || p.creditParty().id() == null
        || p.creditParty().id().isBlank()) {
      return Optional.of("credit party must be a merchant");
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
