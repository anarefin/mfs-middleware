package mfs.mw.adapter.billpay.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Map;
import mfs.mw.adapter.billpay.util.Sensitive;
import mfs.mw.adapter.billpay.util.SensitiveType;

/**
 * This service's own type for {@code mw.partner.request.v1} (contract owned by mw-orchestrator,
 * shared payload; 4.3 rule 4). Unknown fields are ignored (6.1). {@code PartnerRequestSchemaTest}
 * keeps it aligned with the contract samples.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PartnerRequest(
    int schemaVersion,
    String family,
    String requestId,
    String txnId,
    String correlationId,
    Instant issuedAt,
    Instant deadlineAt,
    Payload payload) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Payload(
      String operation,
      String txnType,
      PartnerRef partner,
      String partnerTxnRef,
      Amount amount,
      Party debitParty,
      Party creditParty,
      String originalPartnerRef,
      Map<String, String> attributes) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartnerRef(String partnerId, String productCode, String billerCode) {}

  /** Money as integer minor units (never a floating point number). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(Long valueMinor, String currency) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Party(
      String type,
      @Sensitive(SensitiveType.MSISDN) String id,
      @Sensitive(SensitiveType.NAME) String name,
      String walletType) {}
}
