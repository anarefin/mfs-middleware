package mfs.mw.adapter.remit.service;

import java.util.List;
import java.util.Optional;
import mfs.mw.adapter.remit.client.CoreAccountClient;
import mfs.mw.adapter.remit.client.CoreUnavailableException;
import mfs.mw.adapter.remit.dto.BeneficiaryLookupResult;
import mfs.mw.adapter.remit.dto.CoreAccount;
import mfs.mw.adapter.remit.dto.ErrorDetail;
import mfs.mw.adapter.remit.exception.ErrorCodes;
import mfs.mw.adapter.remit.exception.MwException;
import mfs.mw.adapter.remit.partner.InboundCall;
import mfs.mw.adapter.remit.partner.InboundResult;
import mfs.mw.adapter.remit.util.MaskingPolicy;
import mfs.mw.adapter.remit.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Beneficiary lookup before a remittance is sent (ticket 119c, FRS UC-16, 5.3 step 3): {@code POST
 * /remit/v1/beneficiary-lookup}. The request is verified like a credit (signature, timestamp, once-only
 * nonce: {@link RemitInboundService#authenticate}), then counted against the partner's quota ({@link
 * LookupRateLimiter}, 429 {@code MW-SYS-RATE_LIMITED}), then the wallet is read from the core (WAL-01).
 * The partner learns only the holder's first initial padded to the name's length and whether the wallet
 * can receive now (a CUSTOMER that is ACTIVE, the core's payee rule for REMIT_IN); an unknown number or a
 * wallet that is not a customer's is 404 {@code MW-VAL-003}. The core unreachable is 503 {@code
 * MW-SYS-CORE_UNAVAILABLE}, retryable. Logs carry no MSISDN and no name.
 */
@Service
public class BeneficiaryLookupService {

  private static final Logger log = LoggerFactory.getLogger(BeneficiaryLookupService.class);

  private final RemitInboundService inbound;
  private final CoreAccountClient core;
  private final LookupRateLimiter limiter;

  public BeneficiaryLookupService(RemitInboundService inbound, CoreAccountClient core, LookupRateLimiter limiter) {
    this.inbound = inbound;
    this.core = core;
    this.limiter = limiter;
  }

  public BeneficiaryLookupResult lookup(InboundCall call) {
    InboundResult verified = inbound.authenticate(call);
    if (!limiter.tryAcquire(call.partnerId())) {
      log.atWarn()
          .addKeyValue("event", "remit.lookup.rate_limited")
          .addKeyValue("partnerId", call.partnerId())
          .log("beneficiary lookup quota used up; refused");
      throw new MwException(ErrorCodes.SYS_RATE_LIMITED, "Too many lookups; try again later")
          .withStatus(HttpStatus.TOO_MANY_REQUESTS)
          .withRetryable(true);
    }
    String msisdn = msisdn(verified.attributes().get("beneficiaryMsisdn"));
    Optional<CoreAccount> account;
    try {
      account = core.account(msisdn);
    } catch (CoreUnavailableException | io.github.resilience4j.circuitbreaker.CallNotPermittedException e) {
      log.atWarn()
          .addKeyValue("event", "remit.lookup.core_unavailable")
          .addKeyValue("partnerId", call.partnerId())
          .addKeyValue("errorClass", e.getClass().getName())
          .log("core not available for a beneficiary lookup");
      throw new MwException(ErrorCodes.SYS_CORE_UNAVAILABLE, "The wallet system is not available; try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
          .withRetryable(true);
    }
    CoreAccount a = account.filter(x -> "CUSTOMER".equals(x.type()))
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_NOT_FOUND, "No customer wallet for this number")
            .withStatus(HttpStatus.NOT_FOUND));
    boolean eligible = "ACTIVE".equals(a.status());
    log.atInfo()
        .addKeyValue("event", "remit.lookup.answered")
        .addKeyValue("partnerId", call.partnerId())
        .addKeyValue("eligible", eligible)
        .log("beneficiary lookup answered");
    return new BeneficiaryLookupResult(
        maskName(a.holderName()), MaskingPolicy.mask(SensitiveType.MSISDN, msisdn), eligible);
  }

  /** The first initial, then one {@code *} per further character: "Rahim Uddin" -> "R**********". */
  static String maskName(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    String n = name.strip();
    int first = n.codePointAt(0);
    int rest = n.codePointCount(0, n.length()) - 1;
    return new StringBuilder().appendCodePoint(Character.toUpperCase(first)).append("*".repeat(rest)).toString();
  }

  private static String msisdn(String raw) {
    var m = raw == null ? null : RemitInboundService.MSISDN.matcher(raw);
    if (m == null || !m.matches()) {
      throw new MwException(ErrorCodes.VAL_INVALID, "The lookup is not valid")
          .withDetails(List.of(new ErrorDetail("beneficiaryMsisdn", "a Bangladeshi mobile number")))
          .withStatus(HttpStatus.BAD_REQUEST);
    }
    return "0" + m.group(1);
  }
}
