package mfs.mw.orchestrator.mapper;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.service.saga.SagaEvent;
import org.springframework.stereotype.Component;

/**
 * Partner reply to saga event, and the reply's canonical error to the status error (spec 5.3,
 * 7.4). The adapter maps the partner's own codes; this side only checks that the code fits the
 * status contract and falls back to {@code MW-PTR-<PARTNER>-UNMAPPED} otherwise.
 */
@Component
public class PartnerReplyMapper {

  static final String FALLBACK_MESSAGE = "The partner refused the transaction";

  /** The status contract's error code pattern. */
  private static final Pattern CODE =
      Pattern.compile("^MW-(VAL|AUTH|IDEM|CORE|TMO|SYS|PTR-[A-Z0-9_]+)-[A-Z0-9_]+$");

  /**
   * SUCCESS, FAILED and UNKNOWN become saga events; PENDING (the partner will call back or be
   * asked later) and anything else change nothing.
   */
  public Optional<SagaEvent> toSagaEvent(PartnerReply reply) {
    return toSagaEvent(reply, true);
  }

  /**
   * As {@link #toSagaEvent(PartnerReply)}; {@code held}: the transaction holds funds a refusal
   * releases (false for inbound money, ADD_MONEY_BANK: a refusal simply fails it).
   */
  public Optional<SagaEvent> toSagaEvent(PartnerReply reply, boolean held) {
    String outcome = reply.payload() == null || reply.payload().outcome() == null ? "" : reply.payload().outcome();
    return Optional.ofNullable(
        switch (outcome) {
          case "SUCCESS" -> new SagaEvent.PartnerSucceeded();
          case "FAILED" -> new SagaEvent.PartnerFailed(held);
          case "UNKNOWN" -> new SagaEvent.PartnerUnknown();
          default -> null;
        });
  }

  /** The error of a FAILED reply, kept on the transaction through RELEASING into FAILED. */
  public ErrorInfo error(PartnerReply reply) {
    PartnerReply.Payload p = reply.payload();
    String code = p == null ? null : p.errorCode();
    if (code == null || !CODE.matcher(code).matches()) {
      code = "MW-PTR-" + partnerSegment(reply.partnerId()) + "-UNMAPPED";
    }
    String message = p == null || p.message() == null || p.message().isBlank() ? FALLBACK_MESSAGE : p.message();
    boolean retryable = p != null && Boolean.TRUE.equals(p.retryable());
    return new ErrorInfo(code, message, null, retryable);
  }

  private static String partnerSegment(String partnerId) {
    String s = partnerId == null ? "" : partnerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    return s.isEmpty() ? "UNKNOWN" : s;
  }
}
