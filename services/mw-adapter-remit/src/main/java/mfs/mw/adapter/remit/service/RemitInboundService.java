package mfs.mw.adapter.remit.service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import mfs.mw.adapter.remit.dto.ErrorDetail;
import mfs.mw.adapter.remit.dto.Money;
import mfs.mw.adapter.remit.dto.PartnerInbound;
import mfs.mw.adapter.remit.dto.RemitCreditAck;
import mfs.mw.adapter.remit.exception.ErrorCodes;
import mfs.mw.adapter.remit.exception.MwException;
import mfs.mw.adapter.remit.mapper.PartnerInboundMapper;
import mfs.mw.adapter.remit.messaging.EventMetadata;
import mfs.mw.adapter.remit.messaging.OutboxWriter;
import mfs.mw.adapter.remit.partner.InboundCall;
import mfs.mw.adapter.remit.partner.InboundResult;
import mfs.mw.adapter.remit.partner.PartnerAuthenticationException;
import mfs.mw.adapter.remit.partner.PartnerInstanceNotFoundException;
import mfs.mw.adapter.remit.partner.mapping.CompiledMapping;
import mfs.mw.adapter.remit.partner.route.PartnerGateway;
import mfs.mw.adapter.remit.repository.RemittanceRepository;
import mfs.mw.adapter.remit.util.TxnIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Inbound remittance (ticket 119, FRS 5.3 steps 1-2, UC-16, FL-10 push, PLT-014): the partner pushes a
 * credit; this service
 *
 * <ol>
 *   <li>verifies it with the partner's own record (no alias): its auth scheme and secret, the
 *       timestamp and the once-only nonce ({@link PartnerGateway#inbound}; 401 {@code MW-AUTH-001}),
 *       then reads the body with the record's {@code CALLBACK.response} mapping;
 *   <li>checks the fields (400 {@code MW-VAL-001} with details: partner reference, beneficiary
 *       MSISDN, a positive BDT amount with at most 2 decimals, sender name and ISO country);
 *   <li>deduplicates on (partner, partnerRef) and records in one database transaction: the {@code
 *       remittance} row and the outbox row of {@code mw.partner.inbound.v1} (kind REMITTANCE, txnType
 *       REMIT_IN, a new UUIDv7 txnId, inboundId stable per partner reference) commit together (spec 6.4;
 *       M9 review: no dual write, so a lost Kafka ack can never leave two events for one reference), and
 *       the relay publishes it after the commit. A database failure is 503 with nothing recorded. A
 *       re-send answers the same Upay reference without a second event; a re-send that changes
 *       beneficiary or amount is refused 409 {@code MW-IDEM-002}. The comparison uses an HMAC-SHA256
 *       fingerprint under {@code mw.remit.fingerprint-key} (Vault), never a plain hash of the MSISDN.
 * </ol>
 *
 * The beneficiary, KYC, limit and AML checks and the core credit are mw-orchestrator's REMIT_IN flow
 * (ticket 119b). Runs on the HTTP request's thread; logs carry no MSISDN or name.
 */
@Service
public class RemitInboundService {

  public static final String TOPIC = "mw.partner.inbound.v1";
  static final Pattern PARTNER_REF = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
  static final Pattern MSISDN = Pattern.compile("^(?:\\+?8800?|0)(1[3-9][0-9]{8})$");
  static final Pattern AMOUNT = Pattern.compile("^[0-9]{1,12}(\\.[0-9]{1,2})?$");
  static final Pattern COUNTRY = Pattern.compile("^[A-Z]{2}$");
  static final int MAX_NAME = 140;

  private static final Logger log = LoggerFactory.getLogger(RemitInboundService.class);

  private final PartnerGateway partners;
  private final RemittanceRepository remittances;
  private final PartnerInboundMapper mapper;
  private final OutboxWriter outbox;
  private final TransactionTemplate tx;
  private final Clock clock;
  private final SecretKeySpec fingerprintKey;

  public RemitInboundService(
      PartnerGateway partners,
      RemittanceRepository remittances,
      PartnerInboundMapper mapper,
      OutboxWriter outbox,
      PlatformTransactionManager transactionManager,
      Clock clock,
      @Value("${mw.remit.fingerprint-key}") String fingerprintKey) {
    if (fingerprintKey == null || fingerprintKey.length() < 32) {
      throw new IllegalStateException("mw.remit.fingerprint-key must be at least 32 characters (Vault)");
    }
    this.fingerprintKey = new SecretKeySpec(fingerprintKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    this.partners = partners;
    this.remittances = remittances;
    this.mapper = mapper;
    this.outbox = outbox;
    this.tx = new TransactionTemplate(transactionManager);
    this.clock = clock;
  }

  /** A credit request (POST /remit/v1/credits). */
  public RemitCreditAck credit(InboundCall call) {
    InboundResult result = verify(call);
    Credit credit = validate(result);
    Instant now = clock.instant();
    String partnerId = call.partnerId();
    String candidate = TxnIds.newTxnId(now);
    String fingerprint = fingerprint(credit);
    boolean inserted;
    try {
      inserted =
          Boolean.TRUE.equals(
              tx.execute(
                  status -> {
                    if (!remittances.insertIfAbsent(partnerId, credit.partnerRef(), candidate, fingerprint, now)) {
                      return false;
                    }
                    PartnerInbound event =
                        mapper.toInbound(
                            partnerId,
                            new PartnerInboundMapper.Remittance(
                                credit.partnerRef(), credit.msisdn(), new Money(credit.valueMinor(), credit.currency()),
                                credit.senderName(), credit.senderCountry()),
                            candidate,
                            correlationId(),
                            now);
                    outbox.append(
                        TOPIC,
                        candidate,
                        new EventMetadata(event.correlationId(), candidate, event.inboundId(), 1),
                        event);
                    return true;
                  }));
    } catch (RuntimeException e) {
      log.atError()
          .addKeyValue("event", "remit.credit.not_written")
          .addKeyValue("partnerId", partnerId)
          .addKeyValue("errorClass", e.getClass().getName())
          .log("remittance could not be recorded; not accepted");
      throw new MwException(ErrorCodes.SYS_EVENT_NOT_WRITTEN, "The remittance could not be recorded; send it again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    if (inserted) {
      log.atInfo()
          .addKeyValue("event", "remit.credit.accepted")
          .addKeyValue("txnId", candidate)
          .addKeyValue("partnerId", partnerId)
          .log("inbound remittance accepted and handed to the orchestrator");
      return new RemitCreditAck(credit.partnerRef(), candidate, "ACCEPTED", false);
    }
    RemittanceRepository.Stored stored =
        remittances.find(partnerId, credit.partnerRef()).orElseThrow(() -> new IllegalStateException("remittance row vanished"));
    if (!stored.fingerprint().equals(fingerprint)) {
      log.atWarn()
          .addKeyValue("event", "remit.credit.conflict")
          .addKeyValue("txnId", stored.txnId())
          .addKeyValue("partnerId", partnerId)
          .log("re-sent remittance changes beneficiary or amount; refused");
      throw new MwException(ErrorCodes.IDEM_CONFLICT, "This partner reference was already used for another remittance")
          .withTxnId(stored.txnId())
          .withStatus(HttpStatus.CONFLICT);
    }
    log.atInfo()
        .addKeyValue("event", "remit.credit.duplicate")
        .addKeyValue("txnId", stored.txnId())
        .addKeyValue("partnerId", partnerId)
        .log("re-sent remittance answered with its first reference");
    return new RemitCreditAck(credit.partnerRef(), stored.txnId(), stored.status(), true);
  }

  /** A status query (GET /remit/v1/credits/{partnerRef}). */
  public RemitCreditAck status(InboundCall call, String partnerRef) {
    verify(call);
    return remittances
        .find(call.partnerId(), partnerRef)
        .map(r -> new RemitCreditAck(r.partnerRef(), r.txnId(), r.status(), false))
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No remittance with this partner reference")
            .withStatus(HttpStatus.NOT_FOUND));
  }

  private InboundResult verify(InboundCall call) {
    try {
      return partners.inbound(call);
    } catch (PartnerAuthenticationException e) {
      log.atWarn()
          .addKeyValue("event", "security.partner_callback_rejected")
          .addKeyValue("partnerId", call.partnerId())
          .log("remittance request signature or nonce did not verify; rejected, nothing published");
      throw new MwException(ErrorCodes.AUTH_SIGNATURE, "The request signature is not valid").withStatus(HttpStatus.UNAUTHORIZED);
    } catch (PartnerInstanceNotFoundException e) {
      // an unknown partner is refused like a forged signature: nothing tells a caller which ids exist
      throw new MwException(ErrorCodes.AUTH_SIGNATURE, "The request signature is not valid").withStatus(HttpStatus.UNAUTHORIZED);
    } catch (CompiledMapping.MappingException e) {
      throw new MwException(ErrorCodes.VAL_MALFORMED, "The request body is not understood").withStatus(HttpStatus.BAD_REQUEST);
    }
  }

  record Credit(String partnerRef, String msisdn, long valueMinor, String currency, String senderName, String senderCountry) {}

  static Credit validate(InboundResult r) {
    Map<String, String> a = r.attributes();
    List<ErrorDetail> problems = new ArrayList<>();
    String ref = r.partnerRef();
    if (ref == null || !PARTNER_REF.matcher(ref).matches()) {
      problems.add(new ErrorDetail("partnerRef", "1 to 64 of A-Z a-z 0-9 . _ -"));
    }
    String msisdn = a.get("beneficiaryMsisdn");
    var m = msisdn == null ? null : MSISDN.matcher(msisdn);
    if (m == null || !m.matches()) {
      problems.add(new ErrorDetail("beneficiaryMsisdn", "a Bangladeshi mobile number"));
    }
    String amount = a.get("amount");
    long minor = 0;
    if (amount == null || !AMOUNT.matcher(amount).matches()
        || (minor = new BigDecimal(amount).movePointRight(2).longValueExact()) <= 0) {
      problems.add(new ErrorDetail("amount", "a positive amount in major units, at most 2 decimals, as a string"));
    }
    if (!"BDT".equals(a.get("currency"))) {
      problems.add(new ErrorDetail("currency", "BDT"));
    }
    String name = a.get("senderName");
    if (name == null || name.isBlank() || name.length() > MAX_NAME) {
      problems.add(new ErrorDetail("senderName", "1 to " + MAX_NAME + " characters"));
    }
    String country = a.get("senderCountry");
    if (country == null || !COUNTRY.matcher(country).matches()) {
      problems.add(new ErrorDetail("senderCountry", "ISO 3166-1 alpha-2"));
    }
    if (!problems.isEmpty()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The remittance is not valid")
          .withDetails(problems)
          .withStatus(HttpStatus.BAD_REQUEST);
    }
    return new Credit(ref, "0" + m.group(1), minor, "BDT", name.strip(), country);
  }

  /** HMAC-SHA256 hex of beneficiary, amount and currency: comparable, but no MSISDN to brute-force. */
  String fingerprint(Credit c) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(fingerprintKey);
      return HexFormat.of().formatHex(
          mac.doFinal((c.msisdn() + "|" + c.valueMinor() + "|" + c.currency()).getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  private static String correlationId() {
    String c = MDC.get("correlationId");
    return c == null || c.isBlank() ? UUID.randomUUID().toString() : c;
  }
}
