package mfs.mw.adapter.telco.mapper;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.adapter.telco.dto.OcsTopupRequest;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import org.springframework.stereotype.Component;

/** Canonical partner request (PAY, STATUS_QUERY) to the OCS wire format. */
@Component
public class OcsRequestMapper {

  private static final Pattern MSISDN = Pattern.compile("^(\\+?880)?01[3-9][0-9]{8}$");

  /** What stops this request being sent, or empty when it is a well-formed telco PAY. */
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
      return Optional.empty(); // the OCS is asked by our stable reference only
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

  /** Call {@link #problem} first. {@code partnerRef} becomes the OCS {@code transactionId}. */
  public OcsTopupRequest toOcs(PartnerRequest request, String partnerRef) {
    PartnerRequest.Payload p = request.payload();
    String amount = BigDecimal.valueOf(p.amount().valueMinor(), 2).toPlainString();
    return new OcsTopupRequest(partnerRef, p.creditParty().id(), amount);
  }
}
