package mfs.mw.adapter.remit.mapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.remit.dto.Money;
import mfs.mw.adapter.remit.dto.PartnerInbound;
import org.springframework.stereotype.Component;

/**
 * A checked inbound remittance as {@code mw.partner.inbound.v1} (family {@code remit}, kind REMITTANCE,
 * txnType REMIT_IN, credit party the beneficiary's customer wallet; sender name and country in {@code
 * attributes} for the orchestrator's AML screen).
 */
@Component
public class PartnerInboundMapper {

  public static final String FAMILY = "remit";

  /** The values of one remittance, already checked. */
  public record Remittance(
      String partnerRef, String msisdn, Money amount, String senderName, String senderCountry) {}

  public PartnerInbound toInbound(
      String partnerId, Remittance r, String txnId, String correlationId, Instant receivedAt) {
    Map<String, String> attributes = new LinkedHashMap<>();
    attributes.put("senderName", r.senderName());
    attributes.put("senderCountry", r.senderCountry());
    return new PartnerInbound(
        1,
        FAMILY,
        inboundId(partnerId, r.partnerRef()),
        txnId,
        correlationId,
        partnerId,
        r.partnerRef(),
        receivedAt,
        new PartnerInbound.Payload(
            "REMITTANCE",
            "REMIT_IN",
            r.amount(),
            new PartnerInbound.Party("WALLET", r.msisdn(), "CUSTOMER"),
            attributes));
  }

  /** Stable per partner reference, so the orchestrator drops a second event for one remittance. */
  public static String inboundId(String partnerId, String partnerRef) {
    return UUID.nameUUIDFromBytes(("remit:" + partnerId + ":" + partnerRef).getBytes(StandardCharsets.UTF_8))
        .toString();
  }
}
