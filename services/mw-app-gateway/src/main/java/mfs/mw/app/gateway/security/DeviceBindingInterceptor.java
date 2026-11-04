package mfs.mw.app.gateway.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mfs.mw.app.gateway.dto.AppUser;
import mfs.mw.app.gateway.exception.ErrorCodes;
import mfs.mw.app.gateway.exception.MwException;
import mfs.mw.app.gateway.service.DeviceBindingService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The device gate on {@code /app/**} (spec 9.1, ticket 29), after the bearer token was verified:
 * per handler {@link DeviceGate} level, a bound device with a live session, plus a valid signature
 * and fresh nonce for sensitive operations. Failures are {@link MwException}s, so the global
 * handler answers with the 5.3 envelope and the handler never runs.
 */
public class DeviceBindingInterceptor implements HandlerInterceptor {

  private final DeviceBindingService devices;
  private final AppUserResolver users;

  public DeviceBindingInterceptor(DeviceBindingService devices, AppUserResolver users) {
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
    Jwt jwt = jwt();
    AppUser user = users.resolve(jwt);
    if (level == DeviceGate.Level.SIGNED) {
      devices.verifySigned(jwt.getSubject(), user, SignedRequests.from(request));
    } else {
      devices.checkSession(jwt.getSubject(), user, request.getHeader(SignedRequests.DEVICE_ID));
    }
    return true;
  }

  private static Jwt jwt() {
    Authentication auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof Jwt jwt) {
      return jwt;
    }
    throw new MwException(ErrorCodes.AUTH_UNAUTHENTICATED, "Authentication required");
  }
}
