package mfs.mw.audit.dw.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.audit.dw.util.Sensitive;
import mfs.mw.audit.dw.util.SensitiveType;

/**
 * The part of {@code mw.txn.status.v1} (owner mw-orchestrator) this service reads for its search
 * projection {@code audit.txn_index} (ticket 130). Party ids are personal: they are only tokenised
 * (MSISDNs, mw-crypto {@code TOKENISE_MSISDN}), never stored or logged. Unknown fields are ignored
 * (6.1).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TxnStatusEvent(
    UUID txnId,
    String correlationId,
    String channel,
    String txnType,
    String status,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Amount amount,
    PartnerRef partner,
    Instant requestedAt,
    Instant updatedAt) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Party(String type, @Sensitive(SensitiveType.PARTY_ID) String id) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(Long valueMinor, String currency) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PartnerRef(String partnerId) {}
}
