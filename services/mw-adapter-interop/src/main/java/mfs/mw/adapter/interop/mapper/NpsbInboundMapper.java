package mfs.mw.adapter.interop.mapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.PartnerInbound;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import org.springframework.stereotype.Component;

/**
 * An accepted inbound pacs.008 to {@code mw.partner.inbound.v1} (ticket 121b part 2): family {@code
 * interop}, partner {@code npsb}, kind {@code NPSB_CREDIT}, txnType {@code NPSB_IN}, partnerRef = the
 * switch's EndToEndId, the beneficiary's customer wallet as creditParty; the ISO ids and the debtor
 * (name, account, participant) in {@code attributes} for the orchestrator's NpsbInFlow (121c).
 */
@Component
public class NpsbInboundMapper {

  public static final String FAMILY = "interop";
  public static final String PARTNER_ID = "npsb";
  public static final String KIND = "NPSB_CREDIT";
  public static final String TXN_TYPE = "NPSB_IN";

  public PartnerInbound toInbound(
      NpsbTransfer t, String msisdn, String txnId, String correlationId, Instant receivedAt) {
    Map<String, String> attributes = new LinkedHashMap<>();
    put(attributes, "endToEndId", t.endToEndId());
    put(attributes, "txId", t.txId());
    put(attributes, "msgId", t.msgId());
    put(attributes, "debtorAgent", t.debtor().agent());
    put(attributes, "debtorName", t.debtor().name());
    put(attributes, "debtorAccount", t.debtor().account());
    if (t.settlementDate() != null) {
      attributes.put("settlementDate", t.settlementDate().toString());
    }
    return new PartnerInbound(
        1,
        FAMILY,
        UUID.nameUUIDFromBytes((PARTNER_ID + "|" + t.debtor().agent() + "|" + t.endToEndId()).getBytes(
            java.nio.charset.StandardCharsets.UTF_8)).toString(),
        txnId,
        correlationId,
        PARTNER_ID,
        t.endToEndId(),
        receivedAt,
        new PartnerInbound.Payload(
            KIND,
            TXN_TYPE,
            new Money(t.amount().valueMinor(), t.amount().currency()),
            new PartnerInbound.Party("WALLET", msisdn, "CUSTOMER"),
            attributes));
  }

  private static void put(Map<String, String> m, String k, String v) {
    if (v != null && !v.isBlank()) {
      m.put(k, v);
    }
  }
}
