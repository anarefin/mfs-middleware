package mfs.mw.adapter.bank.mapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.bank.dto.PartnerInbound;
import mfs.mw.adapter.bank.partner.InboundResult;
import mfs.mw.adapter.bank.partner.config.PartnerConfig;
import org.springframework.stereotype.Component;

/**
 * A verified, mapped callback ({@link InboundResult}) to {@code mw.partner.inbound.v1} (ticket 22):
 *
 * <ul>
 *   <li>{@code inboundId}: name-based UUID of {@code <partnerId>:<our reference>:<outcome>}, so the
 *       same callback sent twice has the same id (the dedup key here and in the orchestrator), while
 *       a PENDING and the final outcome for one reference stay distinct;
 *   <li>FAILED: {@code errorCode} {@code MW-PTR-<PARTNER>-<suffix>} from the partner's error-code
 *       map ({@code UNMAPPED} for an unlisted code); a code listed as ambiguous is not a definite
 *       failure and is published as PENDING (fail closed: the status inquiry decides);
 *   <li>{@code partnerRef}: the partner's own reference, else our reference;
 *   <li>the partner's message travels in {@code attributes.message}.
 * </ul>
 */
@Component
public class PartnerInboundMapper {

  public static final String FAMILY = "bank";
  public static final String KIND = "CALLBACK";

  public PartnerInbound toInbound(
      InboundResult result, PartnerConfig config, String partnerId, String txnId, String correlationId, Instant now) {
    String outcome = result.outcome();
    String errorCode = null;
    if ("FAILED".equals(outcome)) {
      if (config.ambiguousCodes().contains(result.code())) {
        outcome = "PENDING";
      } else {
        errorCode = "MW-PTR-" + segment(partnerId) + "-" + config.errorMapping(result.code()).suffix();
      }
    }
    Map<String, String> attributes = new LinkedHashMap<>(result.attributes());
    if (result.message() != null && !result.message().isBlank()) {
      attributes.put("message", result.message());
    }
    String partnerRef = isBlank(result.partnerRef()) ? result.reference() : result.partnerRef();
    return new PartnerInbound(
        1,
        FAMILY,
        inboundId(partnerId, result.reference(), outcome),
        txnId,
        correlationId,
        partnerId,
        partnerRef,
        now,
        new PartnerInbound.Payload(KIND, outcome, result.code(), errorCode, attributes));
  }

  /** Stable id of one callback: partner, our reference, outcome. */
  public static String inboundId(String partnerId, String reference, String outcome) {
    return UUID.nameUUIDFromBytes((partnerId + ":" + reference + ":" + outcome).getBytes(StandardCharsets.UTF_8))
        .toString();
  }

  private static String segment(String partnerId) {
    return partnerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }

  private static boolean isBlank(String s) {
    return s == null || s.isBlank();
  }
}
