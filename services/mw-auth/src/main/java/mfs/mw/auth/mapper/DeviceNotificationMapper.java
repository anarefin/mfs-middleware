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
  /** The PIN reset OTP (ticket 70; mw-notify V12). */
  public static final String RESET_OTP_TEMPLATE = "PIN_RESET_OTP";
  /** The hosted checkout page's OTP (ticket 72; mw-notify V8, params otp, amount, merchant). */
  public static final String CHECKOUT_OTP_TEMPLATE = "CHECKOUT_OTP";
  /** A care PIN reset was approved: ask for a reset OTP in the app (ticket 70; mw-notify V12). */
  public static final String RESET_APPROVED_TEMPLATE = "PIN_RESET_APPROVED";
  /** The wallet's PIN was changed or reset (ticket 70; mw-notify V12). */
  public static final String PIN_CHANGED_TEMPLATE = "PIN_CHANGED";
  /** mw-notify's default locale, used when the app names none. */
  public static final String DEFAULT_LOCALE = "bn";

  public NotificationRequest otp(String wallet, String otp, String locale, Instant expiresAt, String correlationId) {
    return otp(wallet, otp, locale, expiresAt, correlationId, "BIND");
  }

  /** The OTP SMS of a purpose: {@code BIND} (device binding), {@code LOGIN} (first PIN) or {@code RESET}. */
  public NotificationRequest otp(
      String wallet, String otp, String locale, Instant expiresAt, String correlationId, String purpose) {
    return otp(wallet, otp, locale, expiresAt, correlationId, purpose, Map.of());
  }

  /**
   * As above with extra template parameters ({@code CHECKOUT}: {@code amount}, {@code merchant} of
   * template {@code CHECKOUT_OTP}, ticket 72); {@code otp} always wins over a same-named extra.
   */
  public NotificationRequest otp(
      String wallet, String otp, String locale, Instant expiresAt, String correlationId, String purpose,
      Map<String, String> extra) {
    String template = switch (purpose) {
      case "LOGIN" -> LOGIN_OTP_TEMPLATE;
      case "RESET" -> RESET_OTP_TEMPLATE;
      case "CHECKOUT" -> CHECKOUT_OTP_TEMPLATE;
      default -> OTP_TEMPLATE;
    };
    Map<String, String> params = new java.util.LinkedHashMap<>(extra == null ? Map.of() : extra);
    params.put("otp", otp);
    return new NotificationRequest(
        1, "device-otp:" + UUID.randomUUID(), correlationId, new NotificationRequest.Recipient(wallet),
        template, locale(locale), Map.copyOf(params), List.of("SMS"), "OTP", expiresAt);
  }

  public NotificationRequest bound(String wallet, String locale, String correlationId) {
    return new NotificationRequest(
        1, "device-bound:" + UUID.randomUUID(), correlationId, new NotificationRequest.Recipient(wallet),
        BOUND_TEMPLATE, locale(locale), Map.of(), List.of("SMS"), "TXN", null);
  }

  /** A notice without parameters (template {@code PIN_RESET_APPROVED}, {@code PIN_CHANGED}), priority TXN. */
  public NotificationRequest notice(String wallet, String template, String locale, String correlationId) {
    return new NotificationRequest(
        1, "pin-notice:" + UUID.randomUUID(), correlationId, new NotificationRequest.Recipient(wallet),
        template, locale(locale), Map.of(), List.of("SMS"), "TXN", null);
  }

  public static String locale(String locale) {
    return locale == null || locale.isBlank() ? DEFAULT_LOCALE : locale;
  }
}
