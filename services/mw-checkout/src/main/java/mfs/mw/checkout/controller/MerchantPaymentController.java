package mfs.mw.checkout.controller;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import mfs.mw.checkout.dto.PaymentList;
import mfs.mw.checkout.dto.SessionStatus;
import mfs.mw.checkout.service.MerchantSessionService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The merchant's payment list (FRS CH-WEB-04, ticket 116), behind the APISIX route {@code
 * checkout-v1-payments-get} (hmac-auth, the consumer name in {@code X-Mw-Merchant-Id}).
 */
@RestController
@RequestMapping("/checkout/v1/payments")
public class MerchantPaymentController {

  private final MerchantSessionService sessions;

  public MerchantPaymentController(MerchantSessionService sessions) {
    this.sessions = sessions;
  }

  @GetMapping
  public PaymentList list(
      @RequestHeader(name = MerchantSessionController.MERCHANT_HEADER, required = false) String merchantId,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(required = false) SessionStatus status,
      @RequestParam(defaultValue = "0") @Min(0) @Max(10_000) int page,
      @RequestParam(defaultValue = "50") @Min(1) @Max(200) int size) {
    return sessions.list(merchantId, from, to, status, page, size);
  }
}
