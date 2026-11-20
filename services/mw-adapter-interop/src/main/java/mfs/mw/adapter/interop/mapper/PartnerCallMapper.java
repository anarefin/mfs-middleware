package mfs.mw.adapter.interop.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical interop partner request to the framework's {@link PartnerCall} (ticket 121, FRS EXT-13, UC-19,
 * FL-12). The family serves the NPSB switch: PAY = the fund transfer (pacs.008), STATUS_QUERY = the status
 * of our reference (pacs.028), REVERSE = the return of an earlier transfer (camt.056). txnTypes: NPSB_OUT
 * (customer wallet to a bank account or MFS wallet at the participant {@code attributes.participantId})
 * and QR_PAY_INTEROP (the off-us BanglaQR leg of ticket 122, to a merchant). The call's {@code input} is
 * the document the record's mappings read: {@code txnType}, {@code amount} ({@code valueMinor},
 * {@code currency}, {@code major}), {@code debitParty}, {@code creditParty}, {@code partner},
 * {@code originalPartnerRef}, {@code attributes}; the framework adds {@code reference}, {@code operation}
 * and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  public static final String FAMILY = "interop";

  static final Set<String> OPERATIONS = Set.of("PAY", "REVERSE", "STATUS_QUERY");

  /** The txnTypes this adapter knows. */
  static final Set<String> TXN_TYPES = Set.of("NPSB_OUT", "QR_PAY_INTEROP");

  /** Who an NPSB_OUT may credit at the destination participant. */
  static final Set<String> NPSB_CREDIT_TYPES = Set.of("BANK_ACCOUNT", "WALLET");

  /** What stops this request being sent, or empty when it is a well-formed interop request. */
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
    if (p.partner() == null || blank(p.partner().partnerId())) {
      return Optional.of("partner is required");
    }
    if ("STATUS_QUERY".equals(p.operation())) {
      return Optional.empty(); // the switch is asked by our stable reference only
    }
    if (!TXN_TYPES.contains(p.txnType())) {
      return Optional.of("unsupported txnType");
    }
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if ("REVERSE".equals(p.operation())) {
      return blank(p.originalPartnerRef())
          ? Optional.of("originalPartnerRef is required") // the return names the switch's original transfer
          : Optional.empty();
    }
    if (p.debitParty() == null || !"WALLET".equals(p.debitParty().type()) || blank(p.debitParty().id())) {
      return Optional.of("debit party must be a customer wallet");
    }
    if ("QR_PAY_INTEROP".equals(p.txnType())) {
      return p.creditParty() == null || !"MERCHANT".equals(p.creditParty().type()) || blank(p.creditParty().id())
          ? Optional.of("credit party must be a merchant")
          : Optional.empty();
    }
    if (p.creditParty() == null
        || !NPSB_CREDIT_TYPES.contains(p.creditParty().type())
        || blank(p.creditParty().id())) {
      return Optional.of("credit party must be a bank account or wallet");
    }
    if (p.attributes() == null || blank(p.attributes().get("participantId"))) {
      return Optional.of("participantId is required");
    }
    return Optional.empty();
  }

  private static boolean blank(String s) {
    return s == null || s.isBlank();
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
