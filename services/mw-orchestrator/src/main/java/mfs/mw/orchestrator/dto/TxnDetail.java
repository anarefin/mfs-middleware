package mfs.mw.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;

/**
 * A transaction with its saga timeline (ticket 35, spec 15.2): every state change and every core
 * command with its result, in order (B13), and every partner call (requests sent, answers,
 * callbacks).
 */
public record TxnDetail(TxnView transaction, List<TimelineEntry> timeline, List<PartnerCallView> partnerCalls) {

  /** {@link TimelineEntry#kind()} of a state change ({@code saga_step}). */
  public static final String STATE = "STATE";

  /** {@link TimelineEntry#kind()} of a core command and its result ({@code core_call}). */
  public static final String CORE = "CORE";

  /**
   * One timeline entry. {@code STATE}: {@code from}, {@code to}, {@code trigger} (the saga event)
   * at {@code at}. {@code CORE}: {@code command} (HoldFunds, PostTransfer, CommitHold, ...) with
   * its {@code commandId}, first sent at {@code at}, {@code sends} times in all, and once the core
   * answered its {@code result} (the event type), {@code outcome}, {@code reasonCode} and {@code
   * resultAt}.
   */
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
