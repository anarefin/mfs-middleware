package mfs.mw.adapter.telco.mapper;

import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.telco.config.OcsProperties;
import mfs.mw.adapter.telco.dto.OcsResult;
import mfs.mw.adapter.telco.dto.PartnerReply;
import mfs.mw.adapter.telco.dto.PartnerRequest;
import mfs.mw.adapter.telco.exception.OcsNotSentException;
import mfs.mw.adapter.telco.exception.OcsUnknownOutcomeException;
import org.springframework.stereotype.Component;

/**
 * OCS outcomes to canonical {@code mw.partner.reply.v1} (spec 5.3, 7.4): SUCCESS, FAILED with a
 * mapped {@code MW-PTR-<PARTNER>-*} code (or {@code MW-SYS-*} when the call never left), UNKNOWN
 * with {@code MW-TMO-*} when the top-up may have been applied.
 */
@Component
public class PartnerReplyMapper {

  private final OcsProperties props;
  private final Clock clock;

  public PartnerReplyMapper(OcsProperties props, Clock clock) {
    this.props = props;
    this.clock = clock;
  }

  public PartnerReply toReply(PartnerRequest request, String partnerRef, OcsResult result) {
    return switch (result) {
      case OcsResult.Accepted a ->
          reply(request, partnerRef, "SUCCESS", a.ocsRef(), a.code(), null, null, null);
      case OcsResult.Rejected r -> {
        OcsProperties.ErrorMapping m =
            props.errorCodes().getOrDefault(r.code(), new OcsProperties.ErrorMapping("UNMAPPED", false));
        yield reply(
            request, partnerRef, "FAILED", null, r.code(),
            "MW-PTR-" + partnerSegment(request) + "-" + m.suffix(), r.message(), m.retryable());
      }
      case OcsResult.Ambiguous a ->
          reply(
              request, partnerRef, "UNKNOWN", null, a.code(),
              "MW-TMO-PARTNER_AMBIGUOUS_CODE", a.message(), false);
      case OcsResult.NotFound n ->
          reply(request, partnerRef, "NOT_FOUND", null, n.code(), null, n.message(), null);
    };
  }

  /**
   * Nothing reached the OCS: for a PAY a definite, retryable failure. For a STATUS_QUERY it is no
   * answer at all, so UNKNOWN (the orchestrator asks again at the next slot): a FAILED inquiry
   * answer would release a hold the partner may have used.
   */
  public PartnerReply notSent(PartnerRequest request, String partnerRef, OcsNotSentException e) {
    if (isStatusQuery(request)) {
      return reply(
          request, partnerRef, "UNKNOWN", null, null, "MW-TMO-PARTNER_" + e.reason().name(),
          "Status inquiry not sent to the partner", false);
    }
    String code =
        switch (e.reason()) {
          case CIRCUIT_OPEN -> "MW-SYS-PARTNER_CIRCUIT_OPEN";
          case BULKHEAD_FULL -> "MW-SYS-PARTNER_BUSY";
          case CONNECT_FAILED -> "MW-SYS-PARTNER_UNAVAILABLE";
        };
    return reply(request, partnerRef, "FAILED", null, null, code, "Top-up not sent to the partner", true);
  }

  /** The top-up may have been applied: UNKNOWN, resolved later by status inquiry (spec 7.4). */
  public PartnerReply unknown(
      PartnerRequest request, String partnerRef, OcsUnknownOutcomeException e) {
    return reply(
        request, partnerRef, "UNKNOWN", null, null, "MW-TMO-PARTNER_" + e.reason().name(),
        "Top-up outcome unknown", false);
  }

  /**
   * A PAY consumed after its {@code deadlineAt}: never sent, so the orchestrator's status inquiry
   * can trust a later "not found" (spec 7.4). Definite and retryable.
   */
  public PartnerReply expired(PartnerRequest request) {
    return reply(
        request, null, "FAILED", null, null, "MW-SYS-PARTNER_REQUEST_EXPIRED",
        "Top-up not sent: the request expired before it could be sent", true);
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
      String ocsRef,
      String partnerCode,
      String errorCode,
      String message,
      Boolean retryable) {
    PartnerRequest.Payload p = request.payload();
    return new PartnerReply(
        1,
        "telco",
        request.requestId(),
        request.txnId(),
        request.correlationId(),
        partnerId(request),
        clock.instant(),
        new PartnerReply.Payload(
            p == null || p.operation() == null ? "PAY" : p.operation(),
            outcome,
            ocsRef,
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
