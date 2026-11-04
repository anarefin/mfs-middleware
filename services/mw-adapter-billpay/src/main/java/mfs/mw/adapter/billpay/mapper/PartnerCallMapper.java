package mfs.mw.adapter.billpay.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.adapter.billpay.dto.PartnerRequest;
import mfs.mw.adapter.billpay.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical billpay partner request (PAY, STATUS_QUERY) and the bill fetch (INQUIRY, internal
 * API) to the framework's {@link PartnerCall}.
 * The partner's wire format is not here: it is the JSONata request mapping of the partner config.
 * The call's {@code input} is the document that mapping reads: {@code txnType}, {@code amount}
 * ({@code valueMinor}, {@code currency}, {@code major} as a decimal string such as {@code
 * "20.00"}), {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes}; the
 * framework adds {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  /** A bill or customer number at a biller (same rule as the app API). */
  public static final Pattern BILL_NUMBER = Pattern.compile("^[0-9A-Za-z-]{1,32}$");

  public static final String INQUIRY = "INQUIRY";

  /** What stops this request being sent, or empty when it is a well-formed billpay request. */
  public Optional<String> problem(PartnerRequest request) {
    if (!"billpay".equals(request.family())) {
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
    if (p.amount() == null || p.amount().valueMinor() == null || p.amount().valueMinor() <= 0) {
      return Optional.of("amount must be positive");
    }
    if (p.partner().billerCode() == null || p.partner().billerCode().isBlank()) {
      return Optional.of("partner.billerCode is required");
    }
    if (p.creditParty() == null
        || !"BILLER_ACCOUNT".equals(p.creditParty().type())
        || p.creditParty().id() == null
        || !BILL_NUMBER.matcher(p.creditParty().id()).matches()) {
      return Optional.of("credit party must be a biller account");
    }
    return Optional.empty();
  }

  /**
   * The bill fetch of the internal API: an INQUIRY to aggregator {@code partnerId}. No reference
   * (nothing is paid, so there is nothing for the partner to deduplicate) and no txnId.
   */
  public PartnerCall fetchCall(String partnerId, String billerCode, String billNumber, String correlationId) {
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("txnType", "BILL_PAY");
    input.put("creditParty", Map.of("type", "BILLER_ACCOUNT", "id", billNumber));
    input.put("partner", Map.of("partnerId", partnerId, "billerCode", billerCode));
    return new PartnerCall(partnerId, INQUIRY, "BILL_PAY", null, correlationId, null, input);
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
      put(partner, "billerCode", p.partner().billerCode());
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
