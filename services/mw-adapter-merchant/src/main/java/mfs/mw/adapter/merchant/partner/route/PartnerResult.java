package mfs.mw.adapter.merchant.partner.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;
import mfs.mw.adapter.merchant.partner.PartnerResponse;
import mfs.mw.adapter.merchant.partner.PartnerResponse.NotSentReason;
import mfs.mw.adapter.merchant.partner.PartnerResponse.UnknownReason;

/**
 * The output of a partner route (ADR D28): {@code {outcome, partnerRef, code, message, reason}},
 * plus what the record's error map says about a FAILED code ({@code errorSuffix}, {@code
 * retryable}) and the text {@code attributes} of a SUCCESS. Checked against {@code
 * classpath:partner/partner-result.schema.json} inside {@code mw-classify}.
 *
 * @param outcome SUCCESS, FAILED, AMBIGUOUS, NOT_FOUND, PENDING, NOT_SENT or UNKNOWN
 * @param reason the {@link NotSentReason} or {@link UnknownReason} of NOT_SENT and UNKNOWN
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PartnerResult(
    String outcome,
    String partnerRef,
    String code,
    String message,
    String reason,
    String errorSuffix,
    Boolean retryable,
    Map<String, String> attributes) {

  public static PartnerResult notSent(NotSentReason reason) {
    return new PartnerResult("NOT_SENT", null, null, null, reason.name(), null, null, null);
  }

  public static PartnerResult unknown(UnknownReason reason) {
    return unknown(reason, null, null);
  }

  public static PartnerResult unknown(UnknownReason reason, String code, String message) {
    return new PartnerResult("UNKNOWN", null, code, message, reason.name(), null, null, null);
  }

  /** The canonical response; anything it cannot read is UNKNOWN {@code MALFORMED_REPLY} (fail closed). */
  public PartnerResponse toResponse() {
    if (outcome == null) {
      return new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY);
    }
    return switch (outcome) {
      case "SUCCESS" -> new PartnerResponse.Success(partnerRef, code, attributes);
      case "FAILED" ->
          new PartnerResponse.Rejected(
              code, message, errorSuffix == null ? "UNMAPPED" : errorSuffix, Boolean.TRUE.equals(retryable));
      case "AMBIGUOUS" -> new PartnerResponse.Ambiguous(code, message);
      case "NOT_FOUND" -> new PartnerResponse.NotFound(code, message);
      case "PENDING" -> new PartnerResponse.Pending(partnerRef, code);
      case "NOT_SENT" -> new PartnerResponse.NotSent(enumOf(NotSentReason.class, reason, NotSentReason.MISCONFIGURED));
      case "UNKNOWN" -> new PartnerResponse.Unknown(enumOf(UnknownReason.class, reason, UnknownReason.MALFORMED_REPLY));
      default -> new PartnerResponse.Unknown(UnknownReason.MALFORMED_REPLY);
    };
  }

  private static <E extends Enum<E>> E enumOf(Class<E> type, String name, E fallback) {
    if (name == null) {
      return fallback;
    }
    try {
      return Enum.valueOf(type, name);
    } catch (IllegalArgumentException e) {
      return fallback;
    }
  }
}
