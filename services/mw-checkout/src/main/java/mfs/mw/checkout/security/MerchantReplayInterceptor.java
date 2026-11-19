package mfs.mw.checkout.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import mfs.mw.checkout.controller.MerchantSessionController;
import mfs.mw.checkout.service.RequestReplayGuard;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Runs {@link RequestReplayGuard} on the merchant's session create ({@code POST
 * /checkout/v1/sessions}, ticket 99): the request that starts a payment. The session read is
 * signed too but harmless to replay, so it is not checked. Registered by {@code
 * config/ReplayProtectionConfig}.
 */
public class MerchantReplayInterceptor implements HandlerInterceptor {

  static final String NONCE_HEADER = "X-Nonce";
  static final String DATE_HEADER = "Date";

  private final RequestReplayGuard guard;

  public MerchantReplayInterceptor(RequestReplayGuard guard) {
    this.guard = guard;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    if ("POST".equals(request.getMethod()) && "/checkout/v1/sessions".equals(request.getRequestURI())) {
      guard.check(
          request.getHeader(MerchantSessionController.MERCHANT_HEADER),
          request.getHeader(DATE_HEADER),
          request.getHeader(NONCE_HEADER));
    }
    return true;
  }
}
