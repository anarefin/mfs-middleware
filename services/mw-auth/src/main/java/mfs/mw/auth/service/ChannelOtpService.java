package mfs.mw.auth.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import mfs.mw.auth.config.ChannelOtpProperties;
import mfs.mw.auth.config.DeviceBindingProperties;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.InternalOtpRequest;
import mfs.mw.auth.dto.InternalOtpVerification;
import mfs.mw.auth.dto.NotificationRequest;
import mfs.mw.auth.entity.AuthWallet;
import mfs.mw.auth.exception.ErrorCodes;
import mfs.mw.auth.exception.MwException;
import mfs.mw.auth.mapper.DeviceNotificationMapper;
import mfs.mw.auth.messaging.EventMetadata;
import mfs.mw.auth.messaging.EventPublisher;
import mfs.mw.auth.repository.ChannelOtpRepository;
import mfs.mw.auth.util.MaskingPolicy;
import mfs.mw.auth.util.OtpCodes;
import mfs.mw.auth.util.SensitiveType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * A channel's OTP bound to its own binding instead of a device (ticket 72, spec 9.3): the hosted
 * checkout page's OTP, purpose {@code CHECKOUT}, bound to the checkout sessionId, asked by
 * mw-checkout only. Security review of ticket 72: its own slot ({@code channel_otp}, one OTP per
 * wallet, purpose and session) and its own send quota per wallet and purpose ({@code
 * mw.auth.channel-otp}), so it never replaces or uses up a device OTP (BIND/LOGIN/RESET), nor the
 * other way round, and a code only matches the same purpose and session. Otherwise the device OTP's
 * rules: hash only (bound to wallet, purpose and session), SMS straight to Kafka, 3 checks reserved
 * atomically, the wrong guess that uses the last one voids it. The wallet must be known and ACTIVE
 * without an ADMIN lock (a PIN lock is the assertion's to answer).
 */
@Service
public class ChannelOtpService {

  private static final Logger log = LoggerFactory.getLogger(ChannelOtpService.class);

  /** The one client that may ask a checkout OTP. */
  public static final String CHECKOUT_CALLER = "mw-checkout";
  /** The checkout OTP's purpose. */
  public static final String CHECKOUT = "CHECKOUT";

  private final WalletAccess wallets;
  private final ChannelOtpRepository otps;
  private final DeviceNotificationMapper notifications;
  private final EventPublisher publisher;
  private final ChannelOtpProperties props;
  private final DeviceBindingProperties deviceProps;
  private final Clock clock;

  public ChannelOtpService(
      WalletAccess wallets,
      ChannelOtpRepository otps,
      DeviceNotificationMapper notifications,
      EventPublisher publisher,
      ChannelOtpProperties props,
      DeviceBindingProperties deviceProps,
      Clock clock) {
    this.wallets = wallets;
    this.otps = otps;
    this.notifications = notifications;
    this.publisher = publisher;
    this.props = props;
    this.deviceProps = deviceProps;
    this.clock = clock;
  }

  public DeviceOtpSent send(String caller, InternalOtpRequest req) {
    requireCaller(caller);
    String msisdn = WalletAccess.msisdn(req.msisdn());
    AuthWallet w = known(msisdn);
    wallets.checkActive(w);
    String wallet = w.walletId();
    Instant now = clock.instant();
    String otp = OtpCodes.newOtp();
    Instant expiresAt = now.plus(props.ttl());
    String lang = DeviceNotificationMapper.locale(req.locale());
    int sends = otps
        .issue(wallet, CHECKOUT, req.bindingId(), hash(wallet, req.bindingId(), otp), lang, expiresAt,
            props.maxSends(), props.sendWindow(), now)
        .orElseThrow(() -> new MwException(ErrorCodes.VAL_OTP_LIMIT, "No more OTPs for now, try again later")
            .withStatus(HttpStatus.TOO_MANY_REQUESTS));
    String correlationId = DeviceOtpService.correlationId();
    NotificationRequest sms = notifications.otp(msisdn, otp, lang, expiresAt, correlationId, CHECKOUT,
        Map.of("amount", req.amount(), "merchant", req.merchant()));
    try {
      // no transaction: the 6.1 txn and idempotency headers carry the messageId (as the device OTP)
      publisher.publish(
          deviceProps.notifyTopic(), msisdn, new EventMetadata(correlationId, sms.messageId(), sms.messageId(), 1), sms);
    } catch (RuntimeException e) {
      throw new MwException(ErrorCodes.SYS_UNAVAILABLE, "Service temporarily unavailable, try again", e)
          .withStatus(HttpStatus.SERVICE_UNAVAILABLE);
    }
    log.atInfo().addKeyValue("event", "channel.otp.sent").addKeyValue("purpose", CHECKOUT).log("Checkout OTP sent");
    return new DeviceOtpSent(MaskingPolicy.mask(SensitiveType.MSISDN, msisdn), expiresAt, props.maxSends() - sends);
  }

  /**
   * Checks the OTP of the binding and spends it; returns only on a match. 401 MW-AUTH-012 when its
   * checks are used up, MW-AUTH-011 when this purpose and session have none (never asked, expired,
   * spent, void), MW-AUTH-010 when wrong.
   */
  public void verify(String caller, InternalOtpVerification req) {
    requireCaller(caller);
    String msisdn = WalletAccess.msisdn(req.msisdn());
    String wallet = known(msisdn).walletId();
    String binding = req.bindingId();
    int max = props.maxAttempts();
    Instant now = clock.instant();
    ChannelOtpRepository.Row current = otps.find(wallet, CHECKOUT, binding).orElseThrow(ChannelOtpService::expired);
    if (current.attempts() >= max) {
      throw locked();
    }
    if (current.otpHash() == null || !now.isBefore(current.expiresAt())) {
      throw expired();
    }
    if (!otps.reserveAttempt(wallet, CHECKOUT, binding, current.otpHash(), max, now)) {
      throw refused(wallet, binding);
    }
    if (!OtpCodes.matches(current.otpHash(), hash(wallet, binding, req.otp()))) {
      otps.voidIfExhausted(wallet, CHECKOUT, binding, current.otpHash(), max, now);
      log.atInfo().addKeyValue("event", "channel.otp.wrong").addKeyValue("errorCode", ErrorCodes.AUTH_OTP_WRONG)
          .log("Wrong checkout OTP");
      throw new MwException(ErrorCodes.AUTH_OTP_WRONG, "Wrong OTP");
    }
    if (!otps.consume(wallet, CHECKOUT, binding, current.otpHash(), max, now)) {
      throw refused(wallet, binding);
    }
  }

  /** The stored form: bound to wallet, purpose and session, so it fits no device OTP or other session. */
  static String hash(String wallet, String binding, String otp) {
    return OtpCodes.hash(wallet, CHECKOUT + ":" + binding, otp);
  }

  private MwException refused(String wallet, String binding) {
    return otps.find(wallet, CHECKOUT, binding).filter(o -> o.attempts() >= props.maxAttempts()).isPresent()
        ? locked()
        : expired();
  }

  private static MwException expired() {
    return new MwException(ErrorCodes.AUTH_OTP_EXPIRED, "Ask for a new OTP for this payment");
  }

  private static MwException locked() {
    return new MwException(ErrorCodes.AUTH_OTP_LOCKED, "Too many wrong OTPs, ask for a new one");
  }

  private static void requireCaller(String caller) {
    if (!CHECKOUT_CALLER.equals(caller)) {
      throw new MwException(ErrorCodes.AUTH_FORBIDDEN, "Not allowed").withStatus(HttpStatus.FORBIDDEN);
    }
  }

  private AuthWallet known(String msisdn) {
    return wallets.byMsisdn(msisdn).orElseThrow(() ->
        new MwException(ErrorCodes.AUTH_FORBIDDEN, "Wallet not known").withStatus(HttpStatus.FORBIDDEN));
  }
}
