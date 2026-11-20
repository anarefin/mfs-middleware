package mfs.mw.adapter.interop.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import mfs.mw.adapter.interop.client.CryptoXmlSigner;
import mfs.mw.adapter.interop.dto.Money;
import mfs.mw.adapter.interop.dto.PartnerRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbParty;
import mfs.mw.adapter.interop.dto.iso.NpsbResolution;
import mfs.mw.adapter.interop.dto.iso.NpsbReturnRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.dto.iso.NpsbStatusRequest;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import mfs.mw.adapter.interop.exception.MwException;
import mfs.mw.adapter.interop.mapping.Iso20022Mapper;
import mfs.mw.adapter.interop.mapping.Iso20022Schemas;
import mfs.mw.adapter.interop.mapping.IsoMessage;
import mfs.mw.adapter.interop.partner.PartnerCall;
import mfs.mw.adapter.interop.partner.PartnerResponse;
import mfs.mw.adapter.interop.partner.config.PartnerConfig;
import mfs.mw.adapter.interop.partner.config.PartnerConfigStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * The ISO 20022 step of the NPSB switch (ticket 121b part 2), around the shared partner engine (the
 * engine copies stay identical across the adapters): {@link #outbound} turns the canonical call into
 * the signed pacs.008 (PAY), pacs.028 (STATUS_QUERY) or camt.056 (REVERSE) the {@code npsb} route
 * sends as {@code {messageType, document}}; {@link #resolve} verifies the switch's signed answer the
 * route passed on as the attribute {@code document} and reads it: pacs.002 {@code ACSP}/{@code ACSC}/
 * {@code ACCC} = SUCCESS, {@code RJCT} = FAILED through the record's error-map, {@code PDNG} or
 * anything unreadable / not verifying = UNKNOWN (the pacs.028 inquiry resolves it); camt.029
 * {@code CNCL} = SUCCESS, {@code RJCR} = FAILED.
 *
 * <p>EndToEndId (ISO {@code Max35Text}): our stable reference without its {@code BITS} prefix, the
 * txnId's 32 hex digits ({@link #endToEndId}); {@link #referenceOf} turns it back. Not signing
 * (mw-crypto off or down) sends nothing: NOT_SENT {@code MISCONFIGURED} / {@code CONNECT_FAILED}.
 */
@Service
public class NpsbWireService {

  public static final String PARTNER_ID = "npsb";
  static final String PREFIX = "BITS";
  static final ZoneId DHAKA = ZoneId.of("Asia/Dhaka");
  static final Set<String> ACCEPTED = Set.of("ACSP", "ACSC", "ACCC");

  private static final Logger log = LoggerFactory.getLogger(NpsbWireService.class);

  private final Iso20022Mapper iso;
  private final Iso20022Schemas schemas;
  private final ObjectProvider<CryptoXmlSigner> signer;
  private final PartnerConfigStore configs;
  private final Clock clock;
  private final String ownParticipant;
  private final String switchParticipant;

  public NpsbWireService(
      Iso20022Mapper iso,
      Iso20022Schemas schemas,
      ObjectProvider<CryptoXmlSigner> signer,
      PartnerConfigStore configs,
      Clock clock,
      @Value("${mw.interop.npsb.own-participant:BITSBDDH}") String ownParticipant,
      @Value("${mw.interop.npsb.switch-participant:NPSBBDDH}") String switchParticipant) {
    this.iso = iso;
    this.schemas = schemas;
    this.signer = signer;
    this.configs = configs;
    this.clock = clock;
    this.ownParticipant = ownParticipant;
    this.switchParticipant = switchParticipant;
  }

  /** True for a request to the NPSB switch (partner id {@value #PARTNER_ID}). */
  public boolean handles(PartnerRequest request) {
    return request.payload() != null
        && request.payload().partner() != null
        && PARTNER_ID.equalsIgnoreCase(request.payload().partner().partnerId());
  }

  /** Our stable reference ({@code BITS} + 32 hex) as the ISO EndToEndId (32 hex, fits Max35Text). */
  public static String endToEndId(String reference) {
    return reference.startsWith(PREFIX) ? reference.substring(PREFIX.length()) : reference;
  }

  /** The stable reference of an EndToEndId of ours. */
  public static String referenceOf(String endToEndId) {
    return PREFIX + endToEndId.toUpperCase(Locale.ROOT);
  }

  /**
   * The engine call carrying the signed ISO message. Throws {@link MwException} when it cannot be
   * built or signed: nothing is sent.
   */
  public PartnerCall outbound(PartnerCall base, PartnerRequest request, String reference) {
    String e2e = endToEndId(reference);
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0);
    PartnerRequest.Payload p = request.payload();
    IsoMessage type;
    String xml;
    switch (p.operation()) {
      case "PAY" -> {
        type = IsoMessage.PACS_008;
        xml = iso.pacs008(new NpsbTransfer(e2e, now, e2e, e2e, money(p), LocalDate.now(clock.withZone(DHAKA)),
            new NpsbParty(name(p.debitParty()), id(p.debitParty()), ownParticipant),
            new NpsbParty(name(p.creditParty()), id(p.creditParty()), creditorAgent(p))));
      }
      case "STATUS_QUERY" -> {
        type = IsoMessage.PACS_028;
        xml = iso.pacs028(new NpsbStatusRequest("Q" + e2e, now, e2e, e2e, e2e, ownParticipant));
      }
      case "REVERSE" -> {
        type = IsoMessage.CAMT_056;
        String reason = p.attributes() == null ? null : p.attributes().get("reasonCode");
        xml = iso.camt056(new NpsbReturnRequest("R" + e2e, now, e2e, e2e, e2e, money(p),
            reason == null || reason.isBlank() ? "CUST" : reason, ownParticipant, creditorAgent(p)));
      }
      default -> throw new MwException("MW-VAL-INVALID_PARTNER_REQUEST", "operation not on the NPSB wire");
    }
    CryptoXmlSigner s = signer.getIfAvailable();
    if (s == null) {
      throw new MwException(CryptoXmlSigner.CRYPTO_UNAVAILABLE, "XML-DSig signing is off (mw.interop.crypto.enabled)");
    }
    Map<String, Object> input = new LinkedHashMap<>(base.input());
    input.put("messageType", type.messageName());
    input.put("document", s.sign(xml));
    return new PartnerCall(base.partnerId(), base.operation(), base.txnType(), base.txnId(), base.correlationId(),
        base.reference(), input);
  }

  /** The engine's answer with the switch's signed document read; anything else as it is. */
  public PartnerResponse resolve(PartnerRequest request, String reference, PartnerResponse engine) {
    if (!(engine instanceof PartnerResponse.Success success)) {
      return engine;
    }
    String document = success.attributes().get("document");
    if (document == null || document.isBlank()) {
      return malformed("no document in the switch's answer");
    }
    try {
      CryptoXmlSigner s = signer.getIfAvailable();
      if (s == null) {
        return malformed("signature cannot be verified (mw.interop.crypto.enabled)");
      }
      s.verifyFromSwitch(document);
      String plain = schemas.unsigned(document);
      String e2e = endToEndId(reference);
      return "REVERSE".equals(request.payload().operation()) ? resolution(iso.readCamt029(plain), e2e)
          : status(iso.readPacs002(plain), e2e);
    } catch (MwException e) {
      return malformed(e.code());
    }
  }

  private PartnerResponse status(NpsbStatus st, String e2e) {
    if (!e2e.equalsIgnoreCase(st.originalEndToEndId())) {
      return malformed("pacs.002 for another EndToEndId");
    }
    String status = st.status() == null ? "" : st.status();
    if (ACCEPTED.contains(status)) {
      String ref = st.clearingRef() == null || st.clearingRef().isBlank() ? st.msgId() : st.clearingRef();
      return new PartnerResponse.Success(ref, status, Map.of("endToEndId", e2e));
    }
    if ("RJCT".equals(status)) {
      return rejected(st.reasonCode() == null ? "RJCT" : st.reasonCode(), "rejected by the switch");
    }
    // PDNG or anything else: not known yet
    return new PartnerResponse.Unknown(PartnerResponse.UnknownReason.UNEXPECTED_STATUS);
  }

  private PartnerResponse resolution(NpsbResolution r, String e2e) {
    if (!e2e.equalsIgnoreCase(r.originalEndToEndId())) {
      return malformed("camt.029 for another EndToEndId");
    }
    if ("CNCL".equals(r.confirmation())) {
      return new PartnerResponse.Success(r.assignmentId(), "CNCL", Map.of("endToEndId", e2e));
    }
    if ("RJCR".equals(r.confirmation())) {
      return rejected("RJCR", "return refused by the creditor's participant");
    }
    return new PartnerResponse.Unknown(PartnerResponse.UnknownReason.UNEXPECTED_STATUS);
  }

  private PartnerResponse rejected(String code, String message) {
    PartnerConfig.ErrorMapping m = configs.instance(PARTNER_ID)
        .map(rt -> rt.config().errorMapping(code))
        .orElse(PartnerConfig.ErrorMapping.UNMAPPED);
    return new PartnerResponse.Rejected(code, message, m.suffix(), m.retryable());
  }

  private static PartnerResponse malformed(String why) {
    log.atWarn()
        .addKeyValue("event", "partner.npsb.answer_refused")
        .addKeyValue("partnerId", PARTNER_ID)
        .addKeyValue("reason", why)
        .log("NPSB answer not accepted; outcome unknown");
    return new PartnerResponse.Unknown(PartnerResponse.UnknownReason.MALFORMED_REPLY);
  }

  private String creditorAgent(PartnerRequest.Payload p) {
    String participant = p.attributes() == null ? null : p.attributes().get("participantId");
    return participant == null || participant.isBlank() ? switchParticipant : participant;
  }

  private static Money money(PartnerRequest.Payload p) {
    return new Money(p.amount().valueMinor(), p.amount().currency());
  }

  private static String name(PartnerRequest.Party party) {
    return party == null ? null : party.name();
  }

  private static String id(PartnerRequest.Party party) {
    return party == null ? null : party.id();
  }
}
