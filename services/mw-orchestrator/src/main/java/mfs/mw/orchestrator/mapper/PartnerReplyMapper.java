package mfs.mw.orchestrator.mapper;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import mfs.mw.orchestrator.dto.ErrorInfo;
import mfs.mw.orchestrator.dto.PartnerReply;
import mfs.mw.orchestrator.service.saga.Outcome;
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

  /** Bangla for the operator-barred suffixes the telco profiles map (ticket 112c, FRS T2). */
  private static final Map<String, String> BARRED_BN =
      Map.of("SUBSCRIBER_BARRED", "গ্রাহক নম্বরটি নিষিদ্ধ", "BARRED", "নম্বরটি নিষিদ্ধ");

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
    return outcome(reply).flatMap(o -> Optional.ofNullable(
        switch (o) {
          case SUCCESS -> new SagaEvent.PartnerSucceeded();
          case FAILED -> new SagaEvent.PartnerFailed(held);
          case UNKNOWN -> new SagaEvent.PartnerUnknown();
          case NOT_FOUND, PENDING -> null;
        }));
  }

  /** The reply's {@code payload.outcome}; empty when absent or not a contract value. */
  public static Optional<Outcome> outcome(PartnerReply reply) {
    return Outcome.parse(reply.payload() == null ? null : reply.payload().outcome());
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
    return new ErrorInfo(code, message, barredBn(code), retryable);
  }

  /** The suffix after the last hyphen, so {@code MW-PTR-GP_OCS-SUBSCRIBER_BARRED} stays distinct from {@code BARRED}. */
  private static String barredBn(String code) {
    int dash = code.lastIndexOf('-');
    return BARRED_BN.get(dash < 0 ? code : code.substring(dash + 1));
  }

  private static String partnerSegment(String partnerId) {
    String s = partnerId == null ? "" : partnerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
    return s.isEmpty() ? "UNKNOWN" : s;
  }
}
