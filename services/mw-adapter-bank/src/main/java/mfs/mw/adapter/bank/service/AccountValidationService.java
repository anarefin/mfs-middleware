package mfs.mw.adapter.bank.service;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import mfs.mw.adapter.bank.dto.AccountValidationView;
import mfs.mw.adapter.bank.exception.MwException;
import mfs.mw.adapter.bank.partner.PartnerCall;
import mfs.mw.adapter.bank.partner.PartnerResponse;
import mfs.mw.adapter.bank.partner.route.PartnerGateway;
import mfs.mw.adapter.bank.util.HolderNameMatch;
import mfs.mw.adapter.bank.util.MaskingPolicy;
import mfs.mw.adapter.bank.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * One account validation (AVL, ticket 117b, FRS UC-14) through the bank profile {@code
 * bank-<code>}: an INQUIRY with the account as the {@code BANK_ACCOUNT} credit party (the same input
 * {@code mapper/PartnerCallMapper} builds for a Kafka INQUIRY). Runs on the HTTP request's thread;
 * nothing is written. The answer carries the last 4 digits and the holder's first initial only. A
 * definite refusal is {@code MW-PTR-BANK_<CODE>-<suffix>} (502); no usable answer {@code
 * MW-TMO-PARTNER_*} (504); a call that never left {@code MW-SYS-PARTNER_*} (503). Telco's
 * PostpaidBillService has the same shape (D14: copied, not shared).
 */
@Service
public class AccountValidationService {

  private static final Logger log = LoggerFactory.getLogger(AccountValidationService.class);

  /** The profile of a bank code (ticket 117a: {@code bank-<code>}). */
  public static final String PROFILE_PREFIX = "bank-";

  /** The INQUIRY attribute with the bank's whole holder name, compared here and never answered. */
  static final String FULL_NAME = "holderFullName";

  private final PartnerGateway partners;

  public AccountValidationService(PartnerGateway partners) {
    this.partners = partners;
  }

  public AccountValidationView validate(String bankCode, String accountNumber) {
    return validate(bankCode, accountNumber, null);
  }

  /**
   * As above; with a {@code matchName} (the wallet's KYC name, M9 review owner binding) the answer
   * also says whether the bank's full holder name ({@code attributes.holderFullName} of the profile's
   * INQUIRY mapping) is that person. No full name from the bank is a mismatch (fail closed).
   */
  public AccountValidationView validate(String bankCode, String accountNumber, String matchName) {
    String partnerId = PROFILE_PREFIX + bankCode;
    PartnerResponse response = partners.call(call(partnerId, accountNumber));
    return switch (response) {
      case PartnerResponse.Success s -> view(bankCode, partnerId, accountNumber, s, matchName);
      case PartnerResponse.Rejected r ->
          throw new MwException("MW-PTR-" + segment(partnerId) + "-" + r.errorSuffix(),
                  r.message() == null ? "Account validation refused" : MaskingPolicy.scrub(r.message()))
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(r.retryable());
      case PartnerResponse.Ambiguous a -> throw timeout("AMBIGUOUS_CODE");
      case PartnerResponse.Unknown u -> throw timeout(u.reason().name());
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.UNKNOWN_PARTNER ->
          throw new MwException("MW-PTR-UNKNOWN", "No partner serves this bank")
              .withStatus(HttpStatus.BAD_GATEWAY)
              .withRetryable(false);
      case PartnerResponse.NotSent n when n.reason() == PartnerResponse.NotSentReason.MAINTENANCE ->
          throw maintenance(n);
      case PartnerResponse.NotSent n ->
          throw new MwException("MW-SYS-PARTNER_" + notSent(n.reason()), "Bank not reachable, try again")
              .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
              .withRetryable(true);
      case PartnerResponse.NotFound n -> throw timeout("MALFORMED_REPLY");
      case PartnerResponse.Pending p -> throw timeout("MALFORMED_REPLY");
    };
  }

  static PartnerCall call(String partnerId, String accountNumber) {
    Map<String, Object> account = Map.of("type", "BANK_ACCOUNT", "id", accountNumber);
    Map<String, Object> input = new LinkedHashMap<>();
    input.put("creditParty", account);
    input.put("account", accountNumber);
    input.put("partner", Map.of("partnerId", partnerId));
    return new PartnerCall(partnerId, "INQUIRY", null, null, MDC.get("correlationId"), null, input);
  }

  private static AccountValidationView view(
      String bankCode, String partnerId, String accountNumber, PartnerResponse.Success success, String matchName) {
    String holder = success.attributes() == null ? null : success.attributes().get("holderName");
    if (holder == null || holder.isBlank()) {
      throw timeout("MALFORMED_REPLY");
    }
    log.atInfo()
        .addKeyValue("event", "partner.call.completed")
        .addKeyValue("partnerId", partnerId)
        .addKeyValue("operation", "INQUIRY")
        .log("bank account validated");
    Boolean nameMatch = matchName == null
        ? null
        : HolderNameMatch.matches(matchName, success.attributes().get(FULL_NAME));
    return new AccountValidationView(
        bankCode, accountNumber.substring(accountNumber.length() - 4), MaskingPolicy.mask(SensitiveType.NAME, holder),
        nameMatch);
  }

  private static MwException timeout(String reason) {
    return new MwException("MW-TMO-PARTNER_" + reason, "No usable answer from the bank, try again")
        .withStatus(HttpStatus.GATEWAY_TIMEOUT)
        .withRetryable(true);
  }

  private static MwException maintenance(PartnerResponse.NotSent sent) {
    PartnerResponse.Notice notice =
        PartnerResponse.Notice.orDefault(
            sent.notice() == null ? null : sent.notice().en(), sent.notice() == null ? null : sent.notice().bn());
    return new MwException("MW-SYS-PARTNER_MAINTENANCE", notice.en())
        .withStatus(HttpStatus.SERVICE_UNAVAILABLE)
        .withRetryable(true)
        .withMessageBn(notice.bn());
  }

  private static String notSent(PartnerResponse.NotSentReason reason) {
    return switch (reason) {
      case CIRCUIT_OPEN -> "CIRCUIT_OPEN";
      case BULKHEAD_FULL -> "BUSY";
      case CONNECT_FAILED -> "UNAVAILABLE";
      case DISABLED -> "DISABLED";
      case MISCONFIGURED, UNKNOWN_PARTNER -> "MISCONFIGURED";
      case MAINTENANCE -> throw new IllegalStateException("maintenance is answered by its own arm");
    };
  }

  private static String segment(String partnerId) {
    return partnerId.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
  }
}
