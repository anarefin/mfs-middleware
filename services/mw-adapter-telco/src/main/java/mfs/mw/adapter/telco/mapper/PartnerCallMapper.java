package mfs.mw.adapter.telco.mapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.partner.PartnerCall;
import org.springframework.stereotype.Component;

/**
 * Canonical telco partner request (PAY, STATUS_QUERY) to the framework's {@link PartnerCall}.
 * The partner's wire format is not here: it is the JSONata request mapping of the partner config.
 * The call's {@code input} is the document that mapping reads: {@code txnType}, {@code amount}
 * ({@code valueMinor}, {@code currency}, {@code major} as a decimal string such as {@code
 * "20.00"}), {@code debitParty}, {@code creditParty}, {@code partner}, {@code attributes}; the
 * framework adds {@code reference}, {@code operation} and {@code txnId}.
 */
@Component
public class PartnerCallMapper {

  private static final Pattern MSISDN = Pattern.compile("^(\\+?880)?01[3-9][0-9]{8}$");

  /** What stops this request being sent, or empty when it is a well-formed telco request. */
  public Optional<String> problem(PartnerRequest request) {
    if (!"telco".equals(request.family())) {
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
    if (p.creditParty() == null
        || !"MSISDN".equals(p.creditParty().type())
        || p.creditParty().id() == null
        || !MSISDN.matcher(p.creditParty().id()).matches()) {
      return Optional.of("credit party must be an MSISDN");
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
