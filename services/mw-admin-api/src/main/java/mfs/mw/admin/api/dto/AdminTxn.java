package mfs.mw.admin.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import mfs.mw.admin.api.util.Sensitive;
import mfs.mw.admin.api.util.SensitiveType;

/**
 * One transaction as an operator sees it (ticket 36): read from mw-orchestrator's {@code
 * txn-query.v1} (own type; unknown fields ignored) and shown as {@code admin.v1}. Party ids arrive
 * masked unless the operator was allowed a reveal (spec 15.4).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdminTxn(
    String txnId,
    String txnType,
    String channel,
    String status,
    String previousStatus,
    Instant createdAt,
    Instant updatedAt,
    String correlationId,
    Amount amount,
    Amount fee,
    Party initiator,
    Party debitParty,
    Party creditParty,
    Partner partner,
    String partnerRef,
    String coreRef,
    String originalTxnId,
    String reversalTxnId,
    Error error,
    Map<String, String> attributes) {

  public AdminTxn {
    attributes = attributes == null ? null : Map.copyOf(attributes);
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Amount(long valueMinor, String currency) {}

  /** A party; {@code id} is masked by type unless revealed, {@code name} is the initial only. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Party(
      String type,
      @Sensitive(SensitiveType.PARTY_ID) String id,
      @Sensitive(SensitiveType.NAME) String name,
      String walletType) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Partner(String partnerId, String productCode, String billerCode) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record Error(String code, String message, String messageBn, Boolean retryable) {}

  /** One page, newest first. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Page(List<AdminTxn> items, int limit, int offset) {

    public Page {
      items = items == null ? List.of() : List.copyOf(items);
    }
  }

  /** One saga timeline entry: a state change (STATE) or a core command and its result (CORE). */
  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record TimelineEntry(
      String kind,
      String from,
      String to,
      String trigger,
      String command,
      String commandId,
      Integer sends,
      String result,
      String outcome,
      String reasonCode,
      Instant at,
      Instant resultAt) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartnerCall(
      String requestId,
      String operation,
      String family,
      String partnerId,
      Instant sentAt,
      String outcome,
      String partnerRef,
      String errorCode,
      Instant answeredAt) {}

  /** The orchestrator's detail answer: the transaction, its timeline and its partner calls. */
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record Detail(AdminTxn transaction, List<TimelineEntry> timeline, List<PartnerCall> partnerCalls) {

    public Detail {
      timeline = timeline == null ? List.of() : List.copyOf(timeline);
      partnerCalls = partnerCalls == null ? List.of() : List.copyOf(partnerCalls);
    }
  }
}
