package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.entity.DeviceOtp;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.repository.DeviceOtpRepository;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.OtpCodes;
import mfs.mw.auth.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The device binding OTP (spec 9.1, 18.6; review A5). An OTP is asked for a device ({@code POST
 * /auth/v1/otp}) and goes by SMS to the token's wallet through mw-notify, OTP priority.
 * It is published straight to Kafka, not through the outbox, so the code is never stored in
 * clear: the table keeps its hash only (bound to wallet and device). Limits are enforced by
 * conditional updates (as mw-checkout's ticket 31 fix): sends per wallet and window, and every
 * guess reserves one of the OTP's checks before it is compared, so parallel guesses never get
 * more than {@code maxAttempts} comparisons; the wrong guess that used the last check voids it.
 */
@Service
public class DeviceOtpService {

  private static final Logger log = LoggerFactory.getLogger(DeviceOtpService.class);

  /** An OTP that matched: its hash, spent by the binding, and the locale of its SMS. */
  public record Checked(String hash, String locale) {}

  private final DeviceOtpRepository otps;
  private final DeviceNotificationMapper notifications;
  private final EventPublisher publisher;
  private final DeviceBindingProperties props;
  private final Clock clock;

  public DeviceOtpService(
      DeviceOtpRepository otps,
      DeviceNotificationMapper notifications,
      EventPublisher publisher,
      DeviceBindingProperties props,
      Clock clock) {
    this.otps = otps;
    this.notifications = notifications;
    this.publisher = publisher;
    this.props = props;
    this.clock = clock;
  }

  /** OTP purposes (ticket 69): device binding, and the first-PIN login of a wallet without a PIN. */
  public static final String BIND = "BIND";
  public static final String LOGIN = "LOGIN";
  /** A PIN reset (ticket 70): self-service on the bound device, or a care reset on any device. */
  public static final String RESET = "RESET";

  /** A new binding OTP for {@code deviceId} by SMS to {@code wallet}; replaces the wallet's previous one. */
  public DeviceOtpSent send(String wallet, String deviceId, String locale) {
    return send(wallet, wallet, deviceId, locale, BIND);
  }

  /**
   * A new OTP of {@code purpose} for {@code deviceId} of wallet {@code wallet}, by SMS to its
   * {@code msisdn}; replaces the wallet's previous OTP (of any purpose).
   */
  public DeviceOtpSent send(String wallet, String msisdn, String deviceId, String locale, String purpose) {
    DeviceBindingProperties.Otp cfg = props.otp();
    Instant now = clock.instant();
    String otp = OtpCodes.newOtp();
    Instant expiresAt = now.plus(cfg.ttl());
    String lang = DeviceNotificationMapper.locale(locale);
    int sends = otps
        .issue(wallet, deviceId, purpose, OtpCodes.hash(wallet, deviceId, otp), lang, expiresAt, cfg.maxSends(),
            cfg.sendWindow(), now)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_OTP_LIMIT, "No more OTPs for now, try again later")
            .withStatus(HttpStatus.TOO_MANY_REQUESTS));
    String correlationId = correlationId();
    NotificationRequest sms = notifications.otp(msisdn, otp, lang, expiresAt, correlationId, purpose);
    try {
      // no transaction: the 6.1 txn and idempotency headers carry the messageId (as mw-notify does)
      publisher.publish(
          props.notifyTopic(), msisdn, new EventMetadata(correlationId, sms.messageId(), sms.messageId(), 1), sms);
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo().addKeyValue("event", "device.otp.sent").addKeyValue("purpose", purpose)
        .log("OTP sent for device {}", deviceId);
    return new DeviceOtpSent(MaskingPolicy.mask(SensitiveType.MSISDN, msisdn), expiresAt, cfg.maxSends() - sends);
  }

  /**
   * Checks {@code otp} for {@code deviceId}: 401 MW-AUTH-012 when its checks are used up,
   * MW-AUTH-011 when none was asked for this device (or it expired or was spent), MW-AUTH-010 when
   * wrong. A match is returned; the binding spends it with {@link #consume} in its transaction.
   */
  public Checked check(String wallet, String deviceId, String otp) {
    return check(wallet, deviceId, otp, BIND);
  }

  /** As {@link #check(String, String, String)}; an OTP sent for another purpose is MW-AUTH-011. */
  public Checked check(String wallet, String deviceId, String otp, String purpose) {
    int max = props.otp().maxAttempts();
    Instant now = clock.instant();
    DeviceOtp current = otps.find(wallet).orElseThrow(DeviceOtpService::expired);
    if (current.attempts() >= max) {
      throw locked();
    }
    if (current.otpHash() == null || !deviceId.equals(current.deviceId()) || !purpose.equals(current.purpose())
        || !now.isBefore(current.expiresAt())) {
      throw expired();
    }
    if (!otps.reserveAttempt(wallet, deviceId, current.otpHash(), max, now)) {
      throw refused(wallet);
    }
    if (!OtpCodes.matches(current.otpHash(), OtpCodes.hash(wallet, deviceId, otp))) {
      otps.voidIfExhausted(wallet, current.otpHash(), max, now);
      log.atInfo().addKeyValue("event", "device.otp.wrong").addKeyValue("errorCode", ErrorCodes.AUTH_OTP_WRONG)
          .log("Wrong binding OTP for device {}", deviceId);
      throw new MwException(ErrorCodes.AUTH_OTP_WRONG, "Wrong OTP");
    }
    return new Checked(current.otpHash(), current.locale());
  }

  /** Spends a checked OTP, once; refused when it was replaced, spent or expired meanwhile. */
  public void consume(String wallet, String deviceId, Checked checked) {
    if (!otps.consume(wallet, deviceId, checked.hash(), props.otp().maxAttempts(), clock.instant())) {
      throw refused(wallet);
    }
  }

  /** Why an atomic check or spend was refused: no check left (locked), else the OTP is gone. */
  private MwException refused(String wallet) {
    return otps.find(wallet).filter(o -> o.attempts() >= props.otp().maxAttempts()).isPresent()
        ? locked()
        : expired();
  }

  private static MwException expired() {
    return new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Ask for a new OTP for this device");
  }

  private static MwException locked() {
    return new MwException(ErrorCodes.AUTH_OTP_LOCKED, "Too many wrong OTPs, ask for a new one");
  }

  static String correlationId() {
    String id = MDC.get("correlationId");
    return id == null || id.isBlank() ? UUID.randomUUID().toString() : id;
  }
}
