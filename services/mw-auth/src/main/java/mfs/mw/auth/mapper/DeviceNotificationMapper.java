package mfs.mw.auth.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mfs.mw.auth.dto.NotificationRequest;
import org.springframework.stereotype.Component;

/**
 * The SMS of device binding (review A5) as mw-notify requests ({@code mw.notify.request.v1}): the
 * OTP (template {@code DEVICE_BIND_OTP}, priority OTP, expiring with the OTP) and, after a binding,
 * the notice that a new device was bound (template {@code DEVICE_BOUND}, priority TXN). Each is a
 * new message (fresh messageId), so a resend is never dropped by mw-notify as a duplicate.
 */
@Component
public class DeviceNotificationMapper {

  public static final String OTP_TEMPLATE = "DEVICE_BIND_OTP";
  public static final String BOUND_TEMPLATE = "DEVICE_BOUND";
  /** The first-PIN login OTP (ticket 69; mw-notify V11). */
  public static final String LOGIN_OTP_TEMPLATE = "LOGIN_OTP";
  /** mw-notify's default locale, used when the app names none. */
  public static final String DEFAULT_LOCALE = "bn";

  public NotificationRequest otp(String wallet, String otp, String locale, Instant expiresAt, String correlationId) {
    return otp(wallet, otp, locale, expiresAt, correlationId, "BIND");
  }

  /** The OTP SMS of a purpose: {@code BIND} (device binding) or {@code LOGIN} (first PIN). */
  public NotificationRequest otp(
      String wallet, String otp, String locale, Instant expiresAt, String correlationId, String purpose) {
    String template = "LOGIN".equals(purpose) ? LOGIN_OTP_TEMPLATE : OTP_TEMPLATE;
    return new NotificationRequest(
        1, "device-otp:" + UUID.randomUUID(), correlationId, new NotificationRequest.Recipient(wallet),
        template, locale(locale), Map.of("otp", otp), List.of("SMS"), "OTP", expiresAt);
  }

  public NotificationRequest bound(String wallet, String locale, String correlationId) {
    return new NotificationRequest(
        1, "device-bound:" + UUID.randomUUID(), correlationId, new NotificationRequest.Recipient(wallet),
        BOUND_TEMPLATE, locale(locale), Map.of(), List.of("SMS"), "TXN", null);
  }

  public static String locale(String locale) {
    return locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
  }
}
