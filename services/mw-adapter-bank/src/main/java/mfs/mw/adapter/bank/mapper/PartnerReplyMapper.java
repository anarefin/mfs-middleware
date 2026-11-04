package mfs.mw.adapter.bank.mapper;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.bank.dto.PartnerReply;
import mfs.mw.adapter.bank.dto.PartnerRequest;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import org.springframework.stereotype.Component;

/**
 * Framework outcomes to canonical {@code mw.partner.reply.v1} (spec 5.3, 7.4): SUCCESS, FAILED
 * with a mapped {@code MW-PTR-<PARTNER>-*} code (suffix from the partner's error-code map) or
 * {@code MW-SYS-*} when the call never left, UNKNOWN with {@code MW-TMO-*} when the request may
 * have been applied, NOT_FOUND for a status inquiry the partner has no record of, PENDING for an
 * acknowledged async request.
 */
@Component
public class PartnerReplyMapper {

  private final Clock clock;

  public PartnerReplyMapper(Clock clock) {
    this.clock = clock;
  }

  public PartnerReply toReply(PartnerRequest request, String partnerRef, PartnerResponse result) {
    return switch (result) {
      case PartnerResponse.Success s ->
          reply(request, partnerRef, "SUCCESS", s.partnerRef(), s.code(), null, null, null);
      case PartnerResponse.Rejected r ->
          reply(
              request, partnerRef, "FAILED", null, r.code(),
              "MW-PTR-" + partnerSegment(request) + "-" + r.errorSuffix(), r.message(), r.retryable());
      case PartnerResponse.Ambiguous a ->
          reply(
              request, partnerRef, "UNKNOWN", null, a.code(),
              "MW-TMO-PARTNER_AMBIGUOUS_CODE", a.message(), false);
      case PartnerResponse.NotFound n ->
          reply(request, partnerRef, "NOT_FOUND", null, n.code(), null, n.message(), null);
      case PartnerResponse.Pending p ->
          reply(request, partnerRef, "PENDING", p.partnerRef(), p.code(), null, null, null);
      case PartnerResponse.NotSent n -> notSent(request, partnerRef, n.reason());
      case PartnerResponse.Unknown u -> unknown(request, partnerRef, u.reason());
    };
  }

  /**
   * Nothing reached the partner: for a PAY a definite, retryable failure. For a STATUS_QUERY it is
   * no answer at all, so UNKNOWN (the orchestrator asks again at the next slot): a FAILED inquiry
   * answer would release a hold the partner may have used.
   */
  private PartnerReply notSent(
      PartnerRequest request, String partnerRef, PartnerResponse.NotSentReason reason) {
    if (isStatusQuery(request)) {
      return reply(
          request, partnerRef, "UNKNOWN", null, null, "MW-TMO-PARTNER_" + reason.name(),
          "Status inquiry not sent to the partner", false);
    }
    String code =
        switch (reason) {
          case CIRCUIT_OPEN -> "MW-SYS-PARTNER_CIRCUIT_OPEN";
          case BULKHEAD_FULL -> "MW-SYS-PARTNER_BUSY";
          case CONNECT_FAILED -> "MW-SYS-PARTNER_UNAVAILABLE";
          case DISABLED -> "MW-SYS-PARTNER_DISABLED";
          case MISCONFIGURED -> "MW-SYS-PARTNER_MISCONFIGURED";
        };
    return reply(request, partnerRef, "FAILED", null, null, code, "Bank debit not sent to the partner", true);
  }

  /** The request may have been applied: UNKNOWN, resolved later by status inquiry (spec 7.4). */
  private PartnerReply unknown(
      PartnerRequest request, String partnerRef, PartnerResponse.UnknownReason reason) {
    return reply(
        request, partnerRef, "UNKNOWN", null, null, "MW-TMO-PARTNER_" + reason.name(),
        "Bank debit outcome unknown", false);
  }

  /**
   * A PAY consumed after its {@code deadlineAt}: never sent, so the orchestrator's status inquiry
   * can trust a later "not found" (spec 7.4). Definite and retryable.
   */
  public PartnerReply expired(PartnerRequest request) {
    return reply(
        request, null, "FAILED", null, null, "MW-SYS-PARTNER_REQUEST_EXPIRED",
        "Bank debit not sent: the request expired before it could be sent", true);
  }

  public static boolean isStatusQuery(PartnerRequest request) {
    return request.payload() != null && "STATUS_QUERY".equals(request.payload().operation());
  }

  public PartnerReply invalid(PartnerRequest request, String problem) {
    return reply(
        request, null, "FAILED", null, null, "MW-VAL-INVALID_PARTNER_REQUEST", problem, false);
  }

  private PartnerReply reply(
      PartnerRequest request,
      String partnerRef,
      String outcome,
      String theirRef,
      String partnerCode,
      String errorCode,
      String message,
      Boolean retryable) {
    PartnerRequest.Payload p = request.payload();
    return new PartnerReply(
        1,
        PartnerCallMapper.FAMILY,
        request.requestId(),
        request.txnId(),
        request.correlationId(),
        partnerId(request),
        clock.instant(),
        new PartnerReply.Payload(
            p == null || p.operation() == null ? "PAY" : p.operation(),
            outcome,
            theirRef,
            partnerCode,
            errorCode,
            message,
            retryable,
            partnerRef == null ? null : Map.of("partnerTxnRef", partnerRef)));
  }

  private static String partnerId(PartnerRequest request) {
    PartnerRequest.Payload p = request.payload();
    return p == null || p.partner() == null || p.partner().partnerId() == null
        ? "unknown"
        : p.partner().partnerId();
  }

  /** Upper case, with anything outside A-Z, 0-9 and underscore turned into an underscore. */
  private static String partnerSegment(PartnerRequest request) {
    return partnerId(request).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }
}
