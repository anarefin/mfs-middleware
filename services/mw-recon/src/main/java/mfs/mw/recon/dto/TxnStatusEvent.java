package mfs.mw.recon.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

/** The fields of {@code mw.txn.status.v1} this service projects. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnStatusEvent(
    String txnId,
    String txnType,
    String status,
    Partner partner,
    String partnerRef,
    String coreRef,
    Amount amount,
    Instant updatedAt,
    Instant completedAt) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Partner(String partnerId) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(long valueMinor, String currency) {}
}
