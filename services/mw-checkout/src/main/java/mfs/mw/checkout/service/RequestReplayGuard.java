package mfs.mw.checkout.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import mfs.mw.checkout.exception.ErrorCodes;
import mfs.mw.checkout.exception.MwException;
import mfs.mw.checkout.repository.MerchantNonceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Replay protection of the merchant's HMAC-signed requests (ticket 99, FRS PLT-008). APISIX
 * {@code hmac-auth} verifies the signature over {@code @request-target}, {@code date}, {@code
 * digest} and {@code x-nonce}; this checks again that the signed {@code Date} is less than {@link
 * #MAX_SKEW} away (the direct dev port skips the edge) and that the merchant has not used the
 * {@code X-Nonce} (16 to 128 of {@code A-Z a-z 0-9 . _ -}) within twice the skew plus 30 s (so a
 * nonce is never forgotten while its date is acceptable). Stale or missing date 401 {@code
 * MW-AUTH-005}; missing, malformed or reused nonce 401 {@code MW-AUTH-006}. The date is checked
 * first, so a stale request uses up no nonce.
 */
@Service
public class RequestReplayGuard {

  private static final Logger log = LoggerFactory.getLogger(RequestReplayGuard.class);

  public static final Duration MAX_SKEW = Duration.ofMinutes(5);
  static final Duration WINDOW = MAX_SKEW.multipliedBy(2).plusSeconds(30);
  private static final Pattern NONCE = Pattern.compile("[A-Za-z0-9._-]{16,128}");

  private final MerchantNonceRepository nonces;
  private final Clock clock;

  public RequestReplayGuard(MerchantNonceRepository nonces, Clock clock) {
    this.nonces = nonces;
    this.clock = clock;
  }

  public void check(String merchantId, String date, String nonce) {
    Instant now = clock.instant();
    Instant signedAt = parse(date);
    if (signedAt == null || Duration.between(signedAt, now).abs().compareTo(MAX_SKEW) >= 0) {
      throw refused(ErrorCodes.AUTH_STALE_REQUEST, "Request date missing or more than 5 minutes away", merchantId);
    }
    if (nonce == null || !NONCE.matcher(nonce).matches()
        || !nonces.firstUse(merchantId == null ? "" : merchantId, nonce, now, WINDOW)) {
      throw refused(ErrorCodes.AUTH_NONCE_REUSED, "Request nonce missing or already used", merchantId);
    }
  }

  private static Instant parse(String date) {
    if (date == null || date.isBlank()) {
      return null;
    }
    try {
      return ZonedDateTime.parse(date.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      return null;
    }
  }

  private static MwException refused(String code, String message, String merchantId) {
    log.atWarn()
        .addKeyValue("event", "security.merchant_request_replay")
        .addKeyValue("errorCode", code)
        .addKeyValue("merchantId", merchantId)
        .log("merchant request refused by replay protection");
    return new MwException(code, message);
  }
}
