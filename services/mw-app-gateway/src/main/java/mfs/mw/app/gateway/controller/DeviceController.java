package mfs.mw.app.gateway.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mfs.mw.app.gateway.dto.DeviceBindingRequest;
import mfs.mw.app.gateway.dto.DeviceSession;
import mfs.mw.app.gateway.security.AppUserResolver;
import mfs.mw.app.gateway.security.DeviceGate;
import mfs.mw.app.gateway.security.SignedRequests;
import mfs.mw.app.gateway.service.DeviceBindingService;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device binding and sessions (spec 9.1, ticket 29; contract {@code app.v1.openapi.yaml}). Both
 * requests are signed with the device key ({@code X-Device-Id}, {@code X-Timestamp}, {@code
 * X-Nonce}, {@code X-Signature}).
 */
@RestController
public class DeviceController {

  private final DeviceBindingService devices;
  private final AppUserResolver users;

  public DeviceController(DeviceBindingService devices, AppUserResolver users) {
    this.devices = devices;
    this.users = users;
  }

  /** Binds the device to the token's wallet, signed with the key being registered; opens a session. */
  @PostMapping("/app/v1/devices")
  @ResponseStatus(HttpStatus.CREATED)
  @DeviceGate(DeviceGate.Level.NONE)
  public DeviceSession bind(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DeviceBindingRequest body, HttpServletRequest request) {
    return devices.bind(jwt.getSubject(), users.resolve(jwt), body.publicKey(), SignedRequests.from(request));
  }

  /** Opens a new session on a bound device (after the idle timeout, or to move to this device). */
  @PostMapping("/app/v1/sessions")
  @DeviceGate(DeviceGate.Level.NONE)
  public DeviceSession openSession(@AuthenticationPrincipal Jwt jwt, HttpServletRequest request) {
    return devices.openSession(jwt.getSubject(), users.resolve(jwt), SignedRequests.from(request));
  }
}
