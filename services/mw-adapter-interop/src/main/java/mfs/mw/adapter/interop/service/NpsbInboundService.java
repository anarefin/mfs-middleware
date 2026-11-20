package mfs.mw.adapter.interop.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import mfs.mw.adapter.interop.client.CryptoXmlSigner;
import mfs.mw.adapter.interop.dto.PartnerInbound;
import mfs.mw.adapter.interop.dto.iso.NpsbStatus;
import mfs.mw.adapter.interop.dto.iso.NpsbTransfer;
import mfs.mw.adapter.interop.exception.MwException;
import mfs.mw.adapter.interop.mapper.NpsbInboundMapper;
import mfs.mw.adapter.interop.mapping.Iso20022Mapper;
import mfs.mw.adapter.interop.mapping.Iso20022Schemas;
import mfs.mw.adapter.interop.messaging.EventMetadata;
import mfs.mw.adapter.interop.messaging.OutboxWriter;
import mfs.mw.adapter.interop.repository.NpsbInboundRepository;
import mfs.mw.adapter.interop.util.TxnIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * NPSB_IN (ticket 121b part 2, FRS 5.3 steps 1-2, PLT-014): the switch pushes a signed pacs.008 to
 * {@code POST /partner/v1/npsb/inbound}; this service
 *
 * <ol>
 *   <li>verifies its XML-DSig through mw-crypto under the switch's certificate (401
 *       {@code MW-PTR-NPSB-SIGNATURE}; mw-crypto down: 503, fail closed), then reads it (400
 *       {@code MW-PTR-NPSB-INVALID_MESSAGE} when it fails the XSD);
 *   <li>checks it: fresh, i.e. {@code CreDtTm} within {@code mw.interop.npsb.inbound-max-age} (default 24 h,
 *       well under the 7 d inbox retention) and at most 5 min ahead, the settlement date not before that
 *       window ({@code DT01}; replay defence); the creditor's participant is us ({@code RC01}), a positive BDT amount
 *       ({@code AM03}), the creditor account a Bangladeshi wallet number ({@code AC01}); a refusal is
 *       answered with a signed pacs.002 {@code RJCT} and nothing is published;
 *   <li>deduplicates on (debtor participant, EndToEndId) in table {@code npsb_inbound}, never purged
 *       (a replay at any time credits once), and writes {@code mw.partner.inbound.v1} (kind {@code
 *       NPSB_CREDIT}, txnType {@code NPSB_IN}, a new UUIDv7 txnId) through the outbox in the same
 *       transaction: a re-sent pacs.008 is answered {@code ACSP} again and publishes nothing.
 * </ol>
 *
 * The answer is a pacs.002 signed through mw-crypto. The beneficiary check against the core and the
 * credit (MANUAL_REVIEW when it fails after acceptance) are mw-orchestrator's NpsbInFlow (ticket 121c).
 */
@Service
public class NpsbInboundService {

  public static final String TOPIC = "mw.partner.inbound.v1";
  /** How far ahead of our clock a {@code CreDtTm} may be (clock skew). */
  static final Duration MAX_SKEW = Duration.ofMinutes(5);
  static final Pattern MSISDN = Pattern.compile("^(?:\\+?8800?|0)(1[3-9][0-9]{8})$");

  private static final Logger log = LoggerFactory.getLogger(NpsbInboundService.class);

  private final Iso20022Mapper iso;
  private final Iso20022Schemas schemas;
  private final ObjectProvider<CryptoXmlSigner> signer;
  private final NpsbInboundMapper mapper;
  private final NpsbInboundRepository credits;
  private final TransactionTemplate tx;
  private final OutboxWriter outbox;
  private final Clock clock;
  private final String ownParticipant;
  private final Duration maxAge;

  public NpsbInboundService(
      Iso20022Mapper iso,
      Iso20022Schemas schemas,
      ObjectProvider<CryptoXmlSigner> signer,
      NpsbInboundMapper mapper,
      NpsbInboundRepository credits,
      PlatformTransactionManager transactions,
      OutboxWriter outbox,
      Clock clock,
      @Value("${mw.interop.npsb.own-participant:BITSBDDH}") String ownParticipant,
      @Value("${mw.interop.npsb.inbound-max-age:PT24H}") Duration maxAge) {
    this.iso = iso;
    this.schemas = schemas;
    this.signer = signer;
    this.mapper = mapper;
    this.credits = credits;
    this.tx = new TransactionTemplate(transactions);
    this.outbox = outbox;
    this.clock = clock;
    this.ownParticipant = ownParticipant;
    this.maxAge = maxAge;
  }

  /** The outcome of one inbound pacs.008: the signed pacs.002 and whether it was new. */
  public record Answer(String pacs002, String status, boolean duplicate, String txnId) {}

  public Answer receive(String xml) {
    CryptoXmlSigner s = signer.getIfAvailable();
    if (s == null) {
      throw new MwException(CryptoXmlSigner.CRYPTO_UNAVAILABLE, "XML-DSig verification is off")
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
    }
    try {
      s.verifyFromSwitch(xml);
    } catch (MwException e) {
      if (CryptoXmlSigner.SIGNATURE_INVALID.equals(e.code())) {
        throw e.withStatus(HttpStatus.UNAUTHORIZED);
      }
      throw e.withStatus(HttpStatus.SERVICE_UNAVAILABLE).withRetryable(true);
    }
    NpsbTransfer t;
    try {
      t = iso.readPacs008(schemas.unsigned(xml));
    } catch (MwException e) {
      throw e.withStatus(HttpStatus.BAD_REQUEST);
    }
    String refusal = refusal(t);
    if (refusal != null) {
      log.atInfo()
          .addKeyValue("event", "npsb.inbound.rejected")
          .addKeyValue("partnerId", NpsbInboundMapper.PARTNER_ID)
          .addKeyValue("errorCode", refusal)
          .log("inbound NPSB credit refused; nothing published");
      return new Answer(answer(s, t, "RJCT", refusal), "RJCT", false, null);
    }
    Matcher m = MSISDN.matcher(t.creditor().account());
    m.matches();
    String msisdn = "0" + m.group(1);
    Instant now = clock.instant();
    String txnId = TxnIds.newTxnId(now);
    String correlationId = correlationId();
    PartnerInbound event = mapper.toInbound(t, msisdn, txnId, correlationId, now);
    String agent = t.debtor() == null || t.debtor().agent() == null ? "" : t.debtor().agent();
    boolean fresh = Boolean.TRUE.equals(tx.execute(status -> {
      if (!credits.insertIfAbsent(agent, t.endToEndId(), txnId, now)) {
        return false;
      }
      outbox.append(TOPIC, txnId, new EventMetadata(correlationId, txnId, event.inboundId(), 1), event);
      return true;
    }));
    log.atInfo()
        .addKeyValue("event", fresh ? "npsb.inbound.accepted" : "npsb.inbound.duplicate")
        .addKeyValue("txnId", fresh ? txnId : null)
        .addKeyValue("partnerId", NpsbInboundMapper.PARTNER_ID)
        .log(fresh ? "inbound NPSB credit accepted and handed to the orchestrator" : "re-sent NPSB credit; not published again");
    return new Answer(answer(s, t, "ACSP", null), "ACSP", !fresh, fresh ? txnId : null);
  }

  /** The ISO reason code of a refusal, or null when the credit can be accepted. */
  String refusal(NpsbTransfer t) {
    if (stale(t)) {
      return "DT01";
    }
    if (t.creditor() == null || !ownParticipant.equalsIgnoreCase(t.creditor().agent())) {
      return "RC01";
    }
    if (t.amount() == null || !"BDT".equals(t.amount().currency()) || t.amount().valueMinor() <= 0) {
      return "AM03";
    }
    if (t.creditor().account() == null || !MSISDN.matcher(t.creditor().account()).matches()) {
      return "AC01";
    }
    if (t.endToEndId() == null || t.endToEndId().isBlank()) {
      return "FF01";
    }
    return null;
  }

  /** Outside the freshness window: refused, so a captured message cannot be replayed late. */
  boolean stale(NpsbTransfer t) {
    Instant now = clock.instant();
    if (t.createdAt() == null) {
      return true;
    }
    Instant created = t.createdAt().toInstant();
    if (created.isBefore(now.minus(maxAge)) || created.isAfter(now.plus(MAX_SKEW))) {
      return true;
    }
    return t.settlementDate() != null
        && t.settlementDate().isBefore(now.minus(maxAge).atZone(NpsbWireService.DHAKA).toLocalDate());
  }

  private String answer(CryptoXmlSigner s, NpsbTransfer t, String status, String reason) {
    OffsetDateTime now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC).withNano(0);
    String msgId = "ST" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    return s.sign(iso.pacs002(new NpsbStatus(msgId, now, t.msgId(), t.endToEndId(), t.txId(), status, reason, null)));
  }

  private static String correlationId() {
    String c = MDC.get("correlationId");
    return c == null || c.isBlank() ? UUID.randomUUID().toString() : c;
  }
}
