package mfs.mw.app.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.service.DeviceVerifier;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The device gate on {@code /app/**} (spec 9.1, tickets 29 and 68; {@code service/DeviceVerifier}), after the bearer token was verified:
 * per handler {@link DeviceGate} level, a bound device with a live session, plus a valid signature
 * and fresh nonce for sensitive operations. Failures are {@link MwException}s, so the global
 * handler answers with the 5.3 envelope and the handler never runs.
 */
public class DeviceBindingInterceptor implements HandlerInterceptor {

  private final DeviceVerifier devices;
  private final AppUserResolver users;

  public DeviceBindingInterceptor(DeviceVerifier devices, AppUserResolver users) {
    this.devices = devices;
    this.users = users;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod method)) {
      return true;
    }
    DeviceGate gate = method.getMethodAnnotation(DeviceGate.class);
    DeviceGate.Level level = gate == null ? DeviceGate.Level.SESSION : gate.value();
    if (level == DeviceGate.Level.NONE) {
      return true;
    }
    if (level == DeviceGate.Level.SIGNED) {
      requireSigned(request);
    } else {
      requireSession(request);
    }
    return true;
  }

  /**
   * Sensitive operation (REST confirm and balance, and the SOAP operations of the same name): a
   * bound device, a live session, a valid signature and a fresh nonce. SOAP runs on its own servlet,
   * so the MVC interceptor above never sees it; the SOAP endpoint calls this with the same request.
   */
  public void requireSigned(HttpServletRequest request) {
    Jwt jwt = jwt();
    AppUser user = users.resolve(jwt);
    rejectForeignToken(jwt, request);
    devices.verifySigned(user, SignedRequests.from(request));
  }

  private void requireSession(HttpServletRequest request) {
    Jwt jwt = jwt();
    AppUser user = users.resolve(jwt);
    rejectForeignToken(jwt, request);
    devices.checkSession(user, request.getHeader(SignedRequests.DEVICE_ID));
  }

  /** An mw-auth token is issued to one device (ticket 69): it serves no other. */
  private static void rejectForeignToken(Jwt jwt, HttpServletRequest request) {
    String tokenDevice = jwt.getClaimAsString(AppUserResolver.DEVICE_CLAIM);
    if (tokenDevice != null && !tokenDevice.equals(request.getHeader(SignedRequests.DEVICE_ID))) {
      throw new MwException(ErrorCodes.AUTH_DEVICE_NOT_BOUND, "Token not issued to this device");
    }
  }

  private static Jwt jwt() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required");
  }
}
