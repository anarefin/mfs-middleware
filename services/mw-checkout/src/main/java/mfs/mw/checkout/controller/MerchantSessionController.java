package mfs.mw.checkout.controller;

import jakarta.validation.Valid;
import mfs.mw.checkout.dto.CreateSessionRequest;
import mfs.mw.checkout.dto.MerchantSessionView;
import mfs.mw.checkout.dto.SessionCreated;
import mfs.mw.checkout.service.MerchantSessionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Merchant API {@code /checkout/v1} (spec 9.3), behind the APISIX route {@code checkout-v1-*}:
 * {@code hmac-auth} verifies the merchant's signature and forwards its consumer name in {@code
 * X-Mw-Merchant-Id} (a client's own {@code X-Mw-*} headers are stripped at the edge).
 */
@RestController
@RequestMapping("/checkout/v1/sessions")
public class MerchantSessionController {

  public static final String MERCHANT_HEADER = "X-Mw-Merchant-Id";

  private final MerchantSessionService sessions;

  public MerchantSessionController(MerchantSessionService sessions) {
    this.sessions = sessions;
  }

  @PostMapping
  public ResponseEntity<SessionCreated> create(
      @RequestHeader(name = MERCHANT_HEADER, required = false) String merchantId,
      @Valid @RequestBody CreateSessionRequest body) {
    MerchantSessionService.Created c = sessions.create(merchantId, body);
    return ResponseEntity.status(c.isNew() ? HttpStatus.CREATED : HttpStatus.OK).body(c.session());
  }

  @GetMapping("/{sessionId}")
  public MerchantSessionView get(
      @RequestHeader(name = MERCHANT_HEADER, required = false) String merchantId,
      @PathVariable String sessionId) {
    return sessions.find(merchantId, sessionId);
  }
}
