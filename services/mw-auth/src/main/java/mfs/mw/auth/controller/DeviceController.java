package mfs.mw.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mfs.mw.auth.dto.DeviceBindingRequest;
import mfs.mw.auth.dto.DeviceOtpRequest;
import mfs.mw.auth.dto.DeviceOtpSent;
import mfs.mw.auth.dto.DeviceSession;
import mfs.mw.auth.security.AppUserResolver;
import mfs.mw.auth.security.SignedRequests;
import mfs.mw.auth.service.DeviceBindingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device binding and sessions (spec 9.1, 18.7; contract {@code auth.v1.openapi.yaml}; moved from
 * mw-app-gateway's {@code /app/v1/devices*} in ticket 68). The binding OTP request is not signed
 * (the key is not bound yet); binding and session open are signed with the device key ({@code
 * X-Device-Id}, {@code X-Timestamp}, {@code X-Nonce}, {@code X-Signature}). The bearer token is the
 * Keycloak realm {@code customers} token until ticket 69.
 */
@RestController
public class DeviceController {

  private final DeviceBindingService devices;
  private final AppUserResolver users;

  public DeviceController(DeviceBindingService devices, AppUserResolver users) {
    this.devices = devices;
    this.users = users;
  }

  /** Sends a binding OTP for the device in {@code X-Device-Id} by SMS to the token's wallet. */
  @PostMapping("/auth/v1/otp")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public DeviceOtpSent sendOtp(
      @AuthenticationPrincipal Jwt jwt,
      @Valid @RequestBody(required = false) DeviceOtpRequest body,
      @RequestHeader(name = SignedRequests.DEVICE_ID, required = false) String deviceId) {
    return devices.sendOtp(users.resolve(jwt), deviceId, body == null ? null : body.locale());
  }

  /**
   * Binds the device to the token's wallet, signed with the key being registered and with the OTP
   * sent for it; opens a session.
   */
  @PostMapping("/auth/v1/devices")
  @ResponseStatus(HttpStatus.CREATED)
  public DeviceSession bind(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceBindingRequest body, HttpServletRequest request) {
    return devices.bind(users.resolve(jwt), body.publicKey(), body.otp(), SignedRequests.from(request));
  }

  /** Opens a new session on a bound device (after the idle timeout). */
  @PostMapping("/auth/v1/sessions")
  public DeviceSession openSession(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return devices.openSession(users.resolve(jwt), SignedRequests.from(request));
  }
}
