package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A transaction with its saga timeline (ticket 35, spec 15.2): every state change in order and
 * every partner call (requests sent, answers, callbacks).
 */
public record TxnDetail(TxnView transaction, List<TimelineStep> timeline, List<PartnerCallView> partnerCalls) {

  /** One {@code saga_step} row: a state change and the event that caused it. */
  public record TimelineStep(String from, String to, String trigger, Instant at) {}

  /** One {@code partner_call} row; the answer fields stay empty until the partner answered. */
  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record PartnerCallView(
      String requestId,
      String operation,
      String family,
      String partnerId,
      Instant sentAt,
      String outcome,
      String partnerRef,
      String errorCode,
      Instant answeredAt) {}
}
